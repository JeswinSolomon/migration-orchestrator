package com.example.migration.load;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.StatementCreatorUtils;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Initial load of legacy CSVs. Restartable: ids already in MIG_ID_XREF are skipped.
 * Rows that break a cleansing rule are quarantined in MIG_EXCEPTION, never dropped silently.
 */
@Service
public class BulkLoader {

  static final int BATCH_SIZE = 1000;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final boolean batchInsert;
  private final MeterRegistry metrics;

  public BulkLoader(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                    @Value("${migration.load.batch-insert:true}") boolean batchInsert, MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.batchInsert = batchInsert;
    this.metrics = metrics;
  }

  /** Turns one CSV row into the INSERT arguments, or throws Rejected / IllegalArgumentException to quarantine it. */
  @FunctionalInterface
  private interface RowArgs {
    Object[] args(String tenant, CSVRecord record);
  }

  /** What differs per entity: its id column in the CSV, its INSERT, and how a row maps to arguments. */
  private record Spec(String entity, String csvIdColumn, String sql, String keyColumn, RowArgs args) {
  }

  private static final String BORROWER_SQL =
    "INSERT INTO BORROWER (TENANT_ID, FULL_NAME, EMAIL, PHONE, DOB) VALUES (?, ?, ?, ?, ?)";
  private static final String LOAN_SQL =
    "INSERT INTO LOAN (TENANT_ID, BORROWER_ID, PRINCIPAL, RATE_PCT, START_DATE, STATUS, BALANCE, BALANCE_DERIVED) "
      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
  private static final String PAYMENT_SQL = "INSERT INTO PAYMENT (TENANT_ID, LOAN_ID, AMOUNT, PAID_ON) VALUES (?, ?, ?, ?)";

  private static final class Rejected extends RuntimeException {
    Rejected(String reason) {
      super(reason, null, false, false);
    }
  }

  public LoadSummary load(String tenant, Path dir) throws IOException {
    long runId = insert("INSERT INTO MIG_RUN (TENANT_ID, STATE) VALUES (?, ?)", "RUN_ID", tenant, "INITIAL_LOAD");
    return load(runId, tenant, dir);
  }

  public LoadSummary load(long runId, String tenant, Path dir) throws IOException {
    // Resolve and validate the approved mappings before touching any data.
    Map<String, String> borrowerColumns = borrowerColumns(runId, dir.resolve("borrowers.csv"));
    Map<String, Long> borrowerIds = loadXref(tenant, "BORROWER");
    Map<String, Long> loanIds = loadXref(tenant, "LOAN");
    Map<String, LoadSummary.EntityResult> results = new LinkedHashMap<>();

    results.put("borrowers", loadEntity(runId, tenant, dir.resolve("borrowers.csv"), borrowerIds,
      new Spec("BORROWER", "borrower_id", BORROWER_SQL, "BORROWER_ID", (t, r) -> borrowerArgs(t, r, borrowerColumns))));
    results.put("loans", loadEntity(runId, tenant, dir.resolve("loans.csv"), loanIds,
      new Spec("LOAN", "loan_id", LOAN_SQL, "LOAN_ID", (t, r) -> loanArgs(t, r, borrowerIds))));
    results.put("payments", loadEntity(runId, tenant, dir.resolve("payments.csv"),
      new HashMap<>(loadXref(tenant, "PAYMENT")),
      new Spec("PAYMENT", "payment_id", PAYMENT_SQL, "PAYMENT_ID", (t, r) -> paymentArgs(t, r, loanIds))));

    deriveMissingBalances(tenant);
    jdbc.update("UPDATE MIG_RUN SET UPDATED_AT = SYSTIMESTAMP WHERE RUN_ID = ?", runId);
    return new LoadSummary(runId, results);
  }

  private LoadSummary.EntityResult loadEntity(long runId, String tenant, Path file, Map<String, Long> xref, Spec spec)
    throws IOException {
    LoadSummary.EntityResult total = new LoadSummary.EntityResult();
    CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1);
         CSVParser parser = format.parse(reader)) {
      List<CSVRecord> batch = new ArrayList<>(BATCH_SIZE);
      for (CSVRecord record : parser) {
        batch.add(record);
        if (batch.size() == BATCH_SIZE) {
          processBatch(runId, tenant, spec, batch, xref, total);
          batch.clear();
        }
      }
      if (!batch.isEmpty()) {
        processBatch(runId, tenant, spec, batch, xref, total);
      }
    }
    return total;
  }

  private void processBatch(long runId, String tenant, Spec spec, List<CSVRecord> batch,
                            Map<String, Long> xref, LoadSummary.EntityResult total) {
    LoadSummary.EntityResult batchResult = new LoadSummary.EntityResult();
    tx.executeWithoutResult(status -> {
      // Step 1: decide in memory which rows are valid; invalid ones are quarantined.
      List<String> legacyIds = new ArrayList<>();
      List<Object[]> rows = new ArrayList<>();
      List<Object[]> quarantined = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (CSVRecord record : batch) {
        batchResult.read++;
        String legacyId = record.get(spec.csvIdColumn());
        if (xref.containsKey(legacyId) || seen.contains(legacyId)) {
          batchResult.skipped++;
          continue;
        }
        try {
          rows.add(spec.args().args(tenant, record));
          legacyIds.add(legacyId);
          seen.add(legacyId);
        } catch (Rejected e) {
          quarantined.add(new Object[] {runId, spec.entity(), legacyId, e.getMessage(), String.join(",", record)});
          batchResult.quarantine(e.getMessage());
        } catch (IllegalArgumentException e) {
          quarantined.add(new Object[] {runId, spec.entity(), legacyId, "INVALID_VALUE", String.join(",", record)});
          batchResult.quarantine("INVALID_VALUE");
        }
      }

      // Step 2: write everything for the batch in a few round trips.
      List<Long> newIds = batchInsert ? insertBatch(spec, rows) : insertOneByOne(spec, rows);
      List<Object[]> xrefRows = new ArrayList<>();
      for (int i = 0; i < legacyIds.size(); i++) {
        xrefRows.add(new Object[] {tenant, spec.entity(), legacyIds.get(i), newIds.get(i)});
        xref.put(legacyIds.get(i), newIds.get(i));
      }
      batchResult.loaded += legacyIds.size();
      jdbc.batchUpdate("INSERT INTO MIG_ID_XREF (TENANT_ID, ENTITY, LEGACY_ID, NEW_ID, SOURCE_VERSION) VALUES (?, ?, ?, ?, 0)",
        xrefRows);
      jdbc.batchUpdate("INSERT INTO MIG_EXCEPTION (RUN_ID, ENTITY, LEGACY_ID, REASON_CODE, RAW_PAYLOAD) VALUES (?, ?, ?, ?, ?)",
        quarantined);
      jdbc.update("INSERT INTO MIG_BATCH (RUN_ID, ENTITY, FROM_ID, TO_ID, ROWS_READ, ROWS_LOADED, ROWS_QUARANTINED, STATUS) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, 'COMPLETE')",
        runId, spec.entity(), Long.valueOf(batch.get(0).get(spec.csvIdColumn())),
        Long.valueOf(batch.get(batch.size() - 1).get(spec.csvIdColumn())),
        batchResult.read, batchResult.loaded, batchResult.quarantined);
    });
    total.read += batchResult.read;
    total.loaded += batchResult.loaded;
    total.skipped += batchResult.skipped;
    // Counted after the batch committed, so the metric never claims rows that were rolled back.
    metrics.counter("migration.rows", "entity", spec.entity(), "outcome", "loaded").increment(batchResult.loaded);
    batchResult.reasons.forEach((reason, n) ->
      metrics.counter("migration.rows.quarantined", "entity", spec.entity(), "reason", reason).increment(n));
    batchResult.reasons.forEach((reason, n) -> {
      total.quarantined += n;
      total.reasons.merge(reason, n, Integer::sum);
    });
  }

  private List<Long> insertOneByOne(Spec spec, List<Object[]> rows) {
    List<Long> ids = new ArrayList<>(rows.size());
    for (Object[] args : rows) {
      ids.add(insert(spec.sql(), spec.keyColumn(), args));
    }
    return ids;
  }

  /** One JDBC batch for the whole group; generated keys come back in the same order as the rows. */
  private List<Long> insertBatch(Spec spec, List<Object[]> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.batchUpdate(con -> con.prepareStatement(spec.sql(), new String[] {spec.keyColumn()}),
      new BatchPreparedStatementSetter() {
        @Override
        public void setValues(PreparedStatement ps, int i) throws SQLException {
          setArgs(ps, rows.get(i));
        }

        @Override
        public int getBatchSize() {
          return rows.size();
        }
      }, keys);
    List<Long> ids = new ArrayList<>(rows.size());
    for (Map<String, Object> key : keys.getKeyList()) {
      ids.add(((Number) key.values().iterator().next()).longValue());
    }
    if (ids.size() != rows.size()) {
      // Wrong mapping of ids to rows would silently corrupt the xref, so stop instead.
      throw new IllegalStateException("Expected " + rows.size() + " generated keys, got " + ids.size());
    }
    return ids;
  }

  private static void setArgs(PreparedStatement ps, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++) {
      Object arg = args[i] instanceof LocalDate d ? Date.valueOf(d) : args[i];
      StatementCreatorUtils.setParameterValue(ps, i + 1, SqlTypeValue.TYPE_UNKNOWN, arg);
    }
  }

  private void quarantine(long runId, String entity, String legacyId, String reason, CSVRecord record,
                          LoadSummary.EntityResult batchResult) {
    jdbc.update("INSERT INTO MIG_EXCEPTION (RUN_ID, ENTITY, LEGACY_ID, REASON_CODE, RAW_PAYLOAD) VALUES (?, ?, ?, ?, ?)",
      runId, entity, legacyId, reason, String.join(",", record));
    batchResult.quarantine(reason);
  }

  private static final Map<String, String> BORROWER_DEFAULTS = Map.of(
    "FULL_NAME", "full_name", "EMAIL", "email", "PHONE", "phone", "DOB", "dob");

  /**
   * Target column -> legacy CSV header for BORROWER. Starts from the defaults, then applies the mappings a human
   * approved for this run. Fails fast when an approved mapping points at a column the file does not have.
   */
  private Map<String, String> borrowerColumns(long runId, Path file) throws IOException {
    Map<String, String> columns = resolveBorrowerColumns(runId, file);
    jdbc.update("INSERT INTO MIG_AUDIT (RUN_ID, ACTOR, ACTION, DETAIL) VALUES (?, 'system', 'LOAD_COLUMNS', ?)",
      runId, "BORROWER " + new java.util.TreeMap<>(columns));
    return columns;
  }

  // Package-private so the shadow verifier reads the legacy file with exactly the columns the loader used.
  Map<String, String> resolveBorrowerColumns(long runId, Path file) throws IOException {
    List<String> headers;
    CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1);
         CSVParser parser = format.parse(reader)) {
      headers = parser.getHeaderNames();
    }
    Map<String, String> columns = new HashMap<>(BORROWER_DEFAULTS);
    List<Map<String, Object>> approved = jdbc.queryForList(
      "SELECT TARGET_COL, SOURCE_COL FROM MIG_MAPPING WHERE RUN_ID = ? AND DECISION = 'APPROVED'", runId);
    for (Map<String, Object> row : approved) {
      String target = String.valueOf(row.get("TARGET_COL")).toUpperCase();
      String source = String.valueOf(row.get("SOURCE_COL"));
      if (!BORROWER_DEFAULTS.containsKey(target)) {
        throw new IllegalArgumentException("Approved mapping targets unknown BORROWER column " + target);
      }
      if (!headers.contains(source)) {
        throw new IllegalArgumentException("Approved mapping " + source + " -> " + target
          + " but borrowers.csv has no column " + source);
      }
      columns.put(target, source);
    }
    columns.values().removeIf(source -> !headers.contains(source));
    if (!columns.containsKey("FULL_NAME")) {
      throw new IllegalArgumentException("No source column is mapped to FULL_NAME");
    }
    return columns;
  }

  static String column(CSVRecord r, Map<String, String> columns, String target) {
    String source = columns.get(target);
    return source == null ? null : r.get(source);
  }

  private Object[] borrowerArgs(String tenant, CSVRecord r, Map<String, String> columns) {
    String name = LegacyValueParser.blankToNull(column(r, columns, "FULL_NAME"));
    if (name == null) {
      throw new IllegalArgumentException("name required");
    }
    return new Object[] {tenant, LegacyValueParser.titleCase(name),
      LegacyValueParser.blankToNull(column(r, columns, "EMAIL")),
      LegacyValueParser.digitsOnly(column(r, columns, "PHONE")),
      LegacyValueParser.parseDate(column(r, columns, "DOB"))};
  }

  private Object[] loanArgs(String tenant, CSVRecord r, Map<String, Long> borrowerIds) {
    Long borrowerId = borrowerIds.get(r.get("borrower_id"));
    if (borrowerId == null) {
      throw new Rejected("ORPHAN_BORROWER");
    }
    BigDecimal principal = required(LegacyValueParser.parseDecimal(r.get("principal")));
    BigDecimal rate = required(LegacyValueParser.parseDecimal(r.get("rate_pct")));
    BigDecimal balance = LegacyValueParser.parseDecimal(r.get("balance"));
    return new Object[] {tenant, borrowerId, principal, rate, LegacyValueParser.parseDate(r.get("start_date")),
      LegacyValueParser.mapStatus(r.get("status")), balance, balance == null ? 1 : 0};
  }

  private Object[] paymentArgs(String tenant, CSVRecord r, Map<String, Long> loanIds) {
    Long loanId = loanIds.get(r.get("loan_id"));
    if (loanId == null) {
      throw new Rejected("ORPHAN_LOAN");
    }
    BigDecimal amount = required(LegacyValueParser.parseDecimal(r.get("amount")));
    if (amount.signum() < 0) {
      throw new Rejected("NEGATIVE_AMOUNT_UNCONFIRMED");
    }
    LocalDate paidOn = LegacyValueParser.parseDate(r.get("paid_on"));
    if (paidOn == null) {
      throw new Rejected("NULL_PAID_ON");
    }
    return new Object[] {tenant, loanId, amount, paidOn};
  }

  private void deriveMissingBalances(String tenant) {
    jdbc.update("UPDATE LOAN l SET BALANCE = PRINCIPAL - NVL((SELECT SUM(p.AMOUNT) FROM PAYMENT p WHERE p.LOAN_ID = l.LOAN_ID), 0) "
      + "WHERE l.BALANCE IS NULL AND l.TENANT_ID = ?", tenant);
  }

  private Map<String, Long> loadXref(String tenant, String entity) {
    Map<String, Long> map = new HashMap<>();
    jdbc.query("SELECT LEGACY_ID, NEW_ID FROM MIG_ID_XREF WHERE TENANT_ID = ? AND ENTITY = ?",
      rs -> {
        map.put(rs.getString(1), rs.getLong(2));
      }, tenant, entity);
    return map;
  }

  private static BigDecimal required(BigDecimal value) {
    if (value == null) {
      throw new IllegalArgumentException("value required");
    }
    return value;
  }

  private long insert(String sql, String idColumn, Object... args) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(con -> {
      PreparedStatement ps = con.prepareStatement(sql, new String[] {idColumn});
      setArgs(ps, args);
      return ps;
    }, keys);
    return keys.getKey().longValue();
  }
}
