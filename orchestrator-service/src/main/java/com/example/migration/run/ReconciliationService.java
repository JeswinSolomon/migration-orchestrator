package com.example.migration.run;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Proves a load is complete: every source row is either loaded or quarantined, and money adds up. */
@Service
public class ReconciliationService {

  public record EntityRecon(String entity, int source, int loaded, int quarantined, boolean balanced) {
  }

  public record Report(String tenant, List<EntityRecon> entities, BigDecimal paymentSourceSum,
                       BigDecimal paymentLoadedSum, BigDecimal paymentQuarantinedSum, boolean balanced) {
  }

  private static final Map<String, String> FILES = Map.of(
    "BORROWER", "borrowers.csv", "LOAN", "loans.csv", "PAYMENT", "payments.csv");
  private static final List<String> ORDER = List.of("BORROWER", "LOAN", "PAYMENT");

  private final JdbcTemplate jdbc;
  private final Path legacyDir;

  public ReconciliationService(JdbcTemplate jdbc, @Value("${migration.legacy-dir}") String legacyDir) {
    this.jdbc = jdbc;
    this.legacyDir = Paths.get(legacyDir);
  }

  public Report reconcile(String tenant) {
    try {
      List<EntityRecon> entities = new ArrayList<>();
      for (String entity : ORDER) {
        int source = readCsv(entity).size();
        int loaded = jdbc.queryForObject("SELECT COUNT(*) FROM MIG_ID_XREF WHERE TENANT_ID = ? AND ENTITY = ?",
          Integer.class, tenant, entity);
        int quarantined = quarantinedPayloads(tenant, entity).size();
        entities.add(new EntityRecon(entity, source, loaded, quarantined, source == loaded + quarantined));
      }

      BigDecimal sourceSum = BigDecimal.ZERO;
      for (CSVRecord r : readCsv("PAYMENT")) {
        sourceSum = sourceSum.add(amountOrZero(r.get("amount")));
      }
      BigDecimal loadedSum = jdbc.queryForObject("SELECT NVL(SUM(AMOUNT), 0) FROM PAYMENT WHERE TENANT_ID = ?",
        BigDecimal.class, tenant);
      BigDecimal quarantinedSum = BigDecimal.ZERO;
      for (String payload : quarantinedPayloads(tenant, "PAYMENT").values()) {
        try (CSVParser p = CSVParser.parse(payload, CSVFormat.DEFAULT)) {
          quarantinedSum = quarantinedSum.add(amountOrZero(p.getRecords().get(0).get(2)));
        }
      }

      boolean balanced = entities.stream().allMatch(EntityRecon::balanced)
        && sourceSum.compareTo(loadedSum.add(quarantinedSum)) == 0;
      return new Report(tenant, entities, sourceSum, loadedSum, quarantinedSum, balanced);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read legacy files from " + legacyDir, e);
    }
  }

  // Distinct by legacy id: re-runs re-quarantine the same row, which must not be counted twice.
  private Map<String, String> quarantinedPayloads(String tenant, String entity) {
    Map<String, String> payloads = new HashMap<>();
    jdbc.query("SELECT e.LEGACY_ID, e.RAW_PAYLOAD FROM MIG_EXCEPTION e JOIN MIG_RUN r ON r.RUN_ID = e.RUN_ID "
        + "WHERE r.TENANT_ID = ? AND e.ENTITY = ? AND e.LEGACY_ID NOT IN "
        + "(SELECT x.LEGACY_ID FROM MIG_ID_XREF x WHERE x.TENANT_ID = ? AND x.ENTITY = ?)",
      rs -> {
        payloads.put(rs.getString(1), rs.getString(2));
      }, tenant, entity, tenant, entity);
    return payloads;
  }

  private List<CSVRecord> readCsv(String entity) throws IOException {
    CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
    try (var reader = Files.newBufferedReader(legacyDir.resolve(FILES.get(entity)), StandardCharsets.ISO_8859_1);
         CSVParser parser = format.parse(reader)) {
      return parser.getRecords();
    }
  }

  private static BigDecimal amountOrZero(String raw) {
    try {
      return new BigDecimal(raw.trim());
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }
}
