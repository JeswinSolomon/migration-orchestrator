package com.example.migration.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:loadtest;MODE=Oracle;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password="
})
class BulkLoaderTest {

  @Autowired
  BulkLoader loader;

  @Autowired
  JdbcTemplate jdbc;

  private final Path dir = Paths.get("src/test/resources/legacy");

  @Test
  void loadsCleansesQuarantinesAndIsRestartable() throws Exception {
    LoadSummary first = loader.load("northwind", dir);

    var borrowers = first.entities().get("borrowers");
    var loans = first.entities().get("loans");
    var payments = first.entities().get("payments");
    assertThat(borrowers.loaded).isEqualTo(3);
    assertThat(loans.loaded).isEqualTo(3);
    assertThat(loans.reasons).containsEntry("ORPHAN_BORROWER", 1);
    assertThat(payments.loaded).isEqualTo(2);
    assertThat(payments.reasons)
      .containsEntry("NEGATIVE_AMOUNT_UNCONFIRMED", 1)
      .containsEntry("NULL_PAID_ON", 1)
      .containsEntry("ORPHAN_LOAN", 1);

    // reconciliation invariant: read = loaded + quarantined
    assertThat(payments.read).isEqualTo(payments.loaded + payments.quarantined);

    assertThat(jdbc.queryForObject("SELECT FULL_NAME FROM BORROWER WHERE PHONE = '5550101'", String.class))
      .isEqualTo("Ana Smith");
    assertThat(jdbc.queryForObject("SELECT RATE_PCT FROM LOAN WHERE PRINCIPAL = 10000", BigDecimal.class))
      .isEqualByComparingTo("5.1");
    // loan 11 had no balance: derived = 5000 - 300 (only the valid payment)
    assertThat(jdbc.queryForObject("SELECT BALANCE FROM LOAN WHERE PRINCIPAL = 5000", BigDecimal.class))
      .isEqualByComparingTo("4700");
    assertThat(jdbc.queryForObject("SELECT BALANCE_DERIVED FROM LOAN WHERE PRINCIPAL = 5000", Integer.class))
      .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM MIG_EXCEPTION WHERE RUN_ID = ?", Integer.class, first.runId()))
      .isEqualTo(4);

    // second run must not duplicate anything
    LoadSummary second = loader.load("northwind", dir);
    assertThat(second.entities().get("borrowers").loaded).isZero();
    assertThat(second.entities().get("borrowers").skipped).isEqualTo(3);
    assertThat(second.entities().get("loans").loaded).isZero();
    assertThat(second.entities().get("payments").loaded).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BORROWER", Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM PAYMENT", Integer.class)).isEqualTo(2);
  }
}
