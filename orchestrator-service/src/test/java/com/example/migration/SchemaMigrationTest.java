package com.example.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class SchemaMigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void liquibaseCreatesAllTables() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME IN "
                        + "('BORROWER','LOAN','PAYMENT','MIG_RUN','MIG_BATCH','MIG_EXCEPTION','MIG_ID_XREF','MIG_AUDIT')",
                Integer.class);
        assertThat(count).isEqualTo(8);
    }
}
