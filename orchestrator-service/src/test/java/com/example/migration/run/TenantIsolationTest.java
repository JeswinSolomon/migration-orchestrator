package com.example.migration.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:tenantisolation;MODE=Oracle;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "migration.legacy-dir=src/test/resources/legacy"
})
@AutoConfigureMockMvc
@org.springframework.security.test.context.support.WithMockUser(username = "jeswin", roles = {"OPERATOR", "REVIEWER", "APPROVER", "READER"})
class TenantIsolationTest {

  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate jdbc;

  private long loadedRun(String tenant) throws Exception {
    String body = mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
        .content("{\"tenantId\":\"" + tenant + "\"}")).andReturn().getResponse().getContentAsString();
    long id = ((Number) JsonPath.read(body, "$.runId")).longValue();
    for (String target : new String[] {"PROFILED", "INITIAL_LOAD"}) {
      mvc.perform(post("/api/runs/" + id + "/transitions").contentType(MediaType.APPLICATION_JSON)
        .content("{\"target\":\"" + target + "\"}")).andExpect(status().isOk());
    }
    mvc.perform(post("/api/runs/" + id + "/load")).andExpect(status().isOk());
    return id;
  }

  @Test
  void twoTenantsLoadingTheSameLegacyFilesDoNotMixRows() throws Exception {
    long a = loadedRun("tenant-a");
    long b = loadedRun("tenant-b");

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BORROWER WHERE TENANT_ID = 'tenant-a'", Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BORROWER WHERE TENANT_ID = 'tenant-b'", Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM MIG_ID_XREF WHERE TENANT_ID = 'tenant-a'", Integer.class))
      .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM MIG_ID_XREF WHERE TENANT_ID = 'tenant-b'", Integer.class));

    mvc.perform(get("/api/runs/" + a + "/reconciliation"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.tenant").value("tenant-a"))
      .andExpect(jsonPath("$.balanced").value(true));
    mvc.perform(get("/api/runs/" + b + "/reconciliation"))
      .andExpect(jsonPath("$.tenant").value("tenant-b"))
      .andExpect(jsonPath("$.balanced").value(true));
  }

  @Test
  void tamperingWithOneTenantNeverShowsUpInAnothersShadowDiff() throws Exception {
    long clean = loadedRun("tenant-clean");
    loadedRun("tenant-dirty");
    jdbc.update("UPDATE BORROWER SET FULL_NAME = 'Tampered' WHERE TENANT_ID = 'tenant-dirty'");

    mvc.perform(get("/api/runs/" + clean + "/shadow-diff"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.clean").value(true))
      .andExpect(jsonPath("$.mismatchCount").value(0));
  }
}
