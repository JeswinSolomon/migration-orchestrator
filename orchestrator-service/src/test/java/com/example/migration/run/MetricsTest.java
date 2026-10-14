package com.example.migration.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:metricstest;MODE=Oracle;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "migration.legacy-dir=src/test/resources/legacy"
})
@AutoConfigureMockMvc
@org.springframework.security.test.context.support.WithMockUser(username = "tester", roles = {"OPERATOR", "REVIEWER", "APPROVER", "READER"})
class MetricsTest {

  @Autowired
  MockMvc mvc;

  @Autowired
  MeterRegistry registry;

  private long newRun(String tenant) throws Exception {
    String body = mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
        .content("{\"tenantId\":\"" + tenant + "\"}")).andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(body, "$.runId")).longValue();
  }

  private void transition(long id, String target, int expected) throws Exception {
    mvc.perform(post("/api/runs/" + id + "/transitions").contentType(MediaType.APPLICATION_JSON)
      .content("{\"target\":\"" + target + "\"}")).andExpect(status().is(expected));
  }

  @Test
  void loadAndTransitionsAreCounted() throws Exception {
    double loadedBefore = registry.counter("migration.rows", "entity", "BORROWER", "outcome", "loaded").count();
    double toProfiled = registry.counter("migration.transitions", "to", "PROFILED").count();
    long id = newRun("metrics-ok");
    transition(id, "PROFILED", 200);
    transition(id, "INITIAL_LOAD", 200);
    mvc.perform(post("/api/runs/" + id + "/load")).andExpect(status().isOk());

    assertThat(registry.counter("migration.rows", "entity", "BORROWER", "outcome", "loaded").count() - loadedBefore)
      .isEqualTo(3.0);
    assertThat(registry.counter("migration.transitions", "to", "PROFILED").count() - toProfiled).isEqualTo(1.0);
    assertThat(registry.timer("migration.load.duration").count()).isGreaterThanOrEqualTo(1);
    assertThat(registry.find("migration.rows.quarantined").counters()).isNotEmpty();
  }

  @Test
  void exceptionsApiNeverExposesTheRawLegacyRow() throws Exception {
    long id = newRun("metrics-pii");
    transition(id, "PROFILED", 200);
    transition(id, "INITIAL_LOAD", 200);
    mvc.perform(post("/api/runs/" + id + "/load")).andExpect(status().isOk());
    String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
        .get("/api/runs/" + id + "/exceptions")).andReturn().getResponse().getContentAsString();
    assertThat(body).contains("REASON_CODE").doesNotContain("RAW_PAYLOAD").doesNotContain("@");
  }

  @Test
  void failedPrechecksAreCountedByCheck() throws Exception {
    double before = registry.counter("migration.precheck.failed", "check", "reconciliation").count();
    long id = newRun("metrics-fail");
    transition(id, "PROFILED", 200);
    transition(id, "INITIAL_LOAD", 200);
    transition(id, "CDC_SYNC", 409);
    assertThat(registry.counter("migration.precheck.failed", "check", "reconciliation").count() - before)
      .isEqualTo(1.0);
  }
}
