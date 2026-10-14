package com.example.migration.load;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Shadow read: re-reads every legacy row that was loaded, applies the same cleansing rules and compares it
 * field by field with what is in the target. Reconciliation proves counts and sums; this proves the values.
 */
@Service
public class ShadowVerifier {

  static final int MAX_SAMPLES = 50;

  public record Mismatch(String entity, String legacyId, String field, String expected, String actual) {
  }

  public record Report(Map<String, Integer> compared, int mismatchCount, List<Mismatch> samples, boolean clean) {
  }

  private final JdbcTemplate jdbc;
  private final BulkLoader loader;

  public ShadowVerifier(JdbcTemplate jdbc, BulkLoader loader) {
    this.jdbc = jdbc;
    this.loader = loader;
  }

  public Report verify(long runId, String tenant, Path dir) throws IOException {
    Tally tally = new Tally();

    Map<String, String> columns = loader.resolveBorrowerColumns(runId, dir.resolve("borrowers.csv"));
    Map<String, Map<String, Object>> borrowers = targetRows(tenant, "BORROWER", "BORROWER", "BORROWER_ID",
      "FULL_NAME, EMAIL, PHONE, DOB");
    forEachRecord(dir.resolve("borrowers.csv"), "borrower_id", borrowers, (rec) -> {
      Map<String, Object> t = borrowers.get(rec.get("borrower_id"));
      String id = rec.get("borrower_id");
      tally.compared("BORROWER");
      String name = LegacyValueParser.blankToNull(BulkLoader.column(rec, columns, "FULL_NAME"));
      tally.check("BORROWER", id, "FULL_NAME", name == null ? null : LegacyValueParser.titleCase(name), t.get("FULL_NAME"));
      tally.check("BORROWER", id, "EMAIL", LegacyValueParser.blankToNull(BulkLoader.column(rec, columns, "EMAIL")), t.get("EMAIL"));
      tally.check("BORROWER", id, "PHONE", LegacyValueParser.digitsOnly(BulkLoader.column(rec, columns, "PHONE")), t.get("PHONE"));
      tally.check("BORROWER", id, "DOB", LegacyValueParser.parseDate(BulkLoader.column(rec, columns, "DOB")), date(t.get("DOB")));
    });

    Map<String, Map<String, Object>> loans = targetRows(tenant, "LOAN", "LOAN", "LOAN_ID",
      "PRINCIPAL, RATE_PCT, START_DATE, STATUS, BALANCE, BALANCE_DERIVED");
    forEachRecord(dir.resolve("loans.csv"), "loan_id", loans, (rec) -> {
      Map<String, Object> t = loans.get(rec.get("loan_id"));
      String id = rec.get("loan_id");
      tally.compared("LOAN");
      tally.check("LOAN", id, "PRINCIPAL", LegacyValueParser.parseDecimal(rec.get("principal")), t.get("PRINCIPAL"));
      tally.check("LOAN", id, "RATE_PCT", LegacyValueParser.parseDecimal(rec.get("rate_pct")), t.get("RATE_PCT"));
      tally.check("LOAN", id, "START_DATE", LegacyValueParser.parseDate(rec.get("start_date")), date(t.get("START_DATE")));
      tally.check("LOAN", id, "STATUS", LegacyValueParser.mapStatus(rec.get("status")), t.get("STATUS"));
      BigDecimal balance = LegacyValueParser.parseDecimal(rec.get("balance"));
      if (balance != null) { // missing balances were derived by the loader, so there is nothing to compare
        tally.check("LOAN", id, "BALANCE", balance, t.get("BALANCE"));
      }
    });

    Map<String, Map<String, Object>> payments = targetRows(tenant, "PAYMENT", "PAYMENT", "PAYMENT_ID",
      "AMOUNT, PAID_ON");
    forEachRecord(dir.resolve("payments.csv"), "payment_id", payments, (rec) -> {
      Map<String, Object> t = payments.get(rec.get("payment_id"));
      String id = rec.get("payment_id");
      tally.compared("PAYMENT");
      tally.check("PAYMENT", id, "AMOUNT", LegacyValueParser.parseDecimal(rec.get("amount")), t.get("AMOUNT"));
      tally.check("PAYMENT", id, "PAID_ON", LegacyValueParser.parseDate(rec.get("paid_on")), date(t.get("PAID_ON")));
    });

    return new Report(tally.compared, tally.count, tally.samples, tally.count == 0);
  }

  /** Loaded rows by legacy id. Quarantined rows have no xref entry, so they are not compared here. */
  private Map<String, Map<String, Object>> targetRows(String tenant, String entity, String table, String idColumn,
                                                       String columns) {
    Map<String, Map<String, Object>> rows = new HashMap<>();
    jdbc.queryForList("SELECT x.LEGACY_ID AS LEGACY_ID, " + columns.replaceAll("(\\w+)", "t.$1")
        + " FROM MIG_ID_XREF x JOIN " + table + " t ON t." + idColumn + " = x.NEW_ID "
        + "WHERE x.TENANT_ID = ? AND x.ENTITY = ?", tenant, entity)
      .forEach(row -> rows.put(String.valueOf(row.get("LEGACY_ID")), row));
    return rows;
  }

  private void forEachRecord(Path file, String idColumn, Map<String, Map<String, Object>> loaded,
                             Consumer<CSVRecord> action) throws IOException {
    CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1);
         CSVParser parser = format.parse(reader)) {
      for (CSVRecord record : parser) {
        if (loaded.containsKey(record.get(idColumn))) {
          action.accept(record);
        }
      }
    }
  }

  private static LocalDate date(Object value) {
    return value instanceof java.sql.Date d ? d.toLocalDate()
      : value instanceof java.sql.Timestamp ts ? ts.toLocalDateTime().toLocalDate() : null;
  }

  private static final class Tally {
    final Map<String, Integer> compared = new LinkedHashMap<>();
    final List<Mismatch> samples = new ArrayList<>();
    int count;

    void compared(String entity) {
      compared.merge(entity, 1, Integer::sum);
    }

    void check(String entity, String id, String field, Object expected, Object actual) {
      if (!same(expected, actual)) {
        count++;
        if (samples.size() < MAX_SAMPLES) {
          samples.add(new Mismatch(entity, id, field, String.valueOf(expected), String.valueOf(actual)));
        }
      }
    }

    private static boolean same(Object expected, Object actual) {
      if (expected instanceof BigDecimal e && actual instanceof Number a) {
        return e.compareTo(new BigDecimal(a.toString())) == 0;
      }
      return Objects.equals(expected, actual);
    }
  }
}
