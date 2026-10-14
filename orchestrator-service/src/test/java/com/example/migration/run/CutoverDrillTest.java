package com.example.migration.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:cutoverdrill;MODE=Oracle;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "migration.legacy-dir=src/test/resources/legacy"
})
@AutoConfigureMockMvc
@org.springframework.security.test.context.support.WithMockUser(username = "jeswin", roles = {"OPERATOR", "REVIEWER", "APPROVER", "READER"})
class CutoverDrillTest {

  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate jdbc;

  private long loadedRun(String tenant) throws Exception {
    String body = mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
        .content("{\"tenantId\":\"" + tenant + "\"}")).andReturn().getResponse().getContentAsString();
    long id = ((Number) JsonPath.read(body, "$.runId")).longValue();
    transition(id, "PROFILED", null, 200);
    transition(id, "INITIAL_LOAD", null, 200);
    mvc.perform(post("/api/runs/" + id + "/load")).andExpect(status().isOk());
    transition(id, "CDC_SYNC", null, 200);
    transition(id, "SHADOW_VERIFY", null, 200);
    return id;
  }

  private void transition(long id, String target, String approvedBy, int expected) throws Exception {
    String json = "{\"target\":\"" + target + "\""
      + (approvedBy == null ? "" : ",\"approvedBy\":\"" + approvedBy + "\"") + "}";
    mvc.perform(post("/api/runs/" + id + "/transitions").contentType(MediaType.APPLICATION_JSON).content(json))
      .andExpect(status().is(expected));
  }

  @Test
  void shadowReadIsCleanAfterAnUntouchedLoad() throws Exception {
    long id = loadedRun("shadow-clean");
    mvc.perform(get("/api/runs/" + id + "/shadow-diff"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.clean").value(true))
      .andExpect(jsonPath("$.compared.BORROWER").value(3))
      .andExpect(jsonPath("$.compared.LOAN").value(3))
      .andExpect(jsonPath("$.compared.PAYMENT").value(2));
  }

  @Test
  void shadowReadCatchesDriftAndBlocksReadyUntilFixed() throws Exception {
    long id = loadedRun("shadow-drift");
    jdbc.update("UPDATE BORROWER SET FULL_NAME = 'Tampered' WHERE TENANT_ID = 'shadow-drift' AND EMAIL = 'ana@example.com'");
    jdbc.update("UPDATE PAYMENT SET AMOUNT = AMOUNT + 1 WHERE TENANT_ID = 'shadow-drift' AND PAYMENT_ID = "
      + "(SELECT MIN(PAYMENT_ID) FROM PAYMENT WHERE TENANT_ID = 'shadow-drift')");

    mvc.perform(get("/api/runs/" + id + "/shadow-diff"))
      .andExpect(jsonPath("$.clean").value(false))
      .andExpect(jsonPath("$.mismatchCount").value(2))
      .andExpect(jsonPath("$.samples[?(@.field == 'FULL_NAME')].expected").value("Ana Smith"))
      .andExpect(jsonPath("$.samples[?(@.field == 'FULL_NAME')].actual").value("Tampered"));
    transition(id, "READY", null, 409);

    jdbc.update("UPDATE BORROWER SET FULL_NAME = 'Ana Smith' WHERE TENANT_ID = 'shadow-drift' AND FULL_NAME = 'Tampered'");
    jdbc.update("UPDATE PAYMENT SET AMOUNT = AMOUNT - 1 WHERE TENANT_ID = 'shadow-drift' AND PAYMENT_ID = "
      + "(SELECT MIN(PAYMENT_ID) FROM PAYMENT WHERE TENANT_ID = 'shadow-drift')");
    transition(id, "READY", null, 200);
  }

  @Test
  void rollbackDrillLeavesAFullAuditTrail() throws Exception {
    long id = loadedRun("drill");
    transition(id, "ROLLED_BACK", null, 409);   // too early: legacy is still the only writer, nothing to undo
    transition(id, "READY", null, 200);
    transition(id, "FREEZE", null, 200);
    transition(id, "FINAL_SYNC", null, 200);
    transition(id, "SWITCHED", "jeswin", 200);
    transition(id, "ROLLED_BACK", "jeswin", 200);

    mvc.perform(get("/api/runs/" + id)).andExpect(jsonPath("$.state").value("ROLLED_BACK"));
    transition(id, "MONITOR", null, 409);       // terminal

    List<Map<String, Object>> trail = jdbc.queryForList(
      "SELECT ACTION, DETAIL FROM MIG_AUDIT WHERE RUN_ID = ? AND ACTION = 'TRANSITION' ORDER BY AUDIT_ID", id);
    assertThat(trail).extracting(r -> r.get("DETAIL").toString()).endsWith(
      "FREEZE -> FINAL_SYNC", "FINAL_SYNC -> SWITCHED", "SWITCHED -> ROLLED_BACK");
    mvc.perform(get("/api/runs/" + id + "/audit"))
      .andExpect(jsonPath("$[?(@.ACTION == 'TRANSITION' && @.DETAIL == 'SWITCHED -> ROLLED_BACK')].ACTOR")
        .value("jeswin"));
  }
}
