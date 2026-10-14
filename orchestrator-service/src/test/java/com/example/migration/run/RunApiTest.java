package com.example.migration.run;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:apitest;MODE=Oracle;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "migration.legacy-dir=src/test/resources/legacy"
})
@AutoConfigureMockMvc
@WithMockUser(username = "jeswin", roles = {"OPERATOR", "REVIEWER", "APPROVER", "READER"})
class RunApiTest {

  @Autowired
  MockMvc mvc;

  private long createRun(String tenant) throws Exception {
    String body = mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
        .content("{\"tenantId\":\"" + tenant + "\"}"))
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.state").value("PLANNED"))
      .andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(body, "$.runId")).longValue();
  }

  private void transition(long id, String target, String approvedBy, int expectedStatus) throws Exception {
    String json = "{\"target\":\"" + target + "\""
      + (approvedBy == null ? "" : ",\"approvedBy\":\"" + approvedBy + "\"") + "}";
    mvc.perform(post("/api/runs/" + id + "/transitions").contentType(MediaType.APPLICATION_JSON).content(json))
      .andExpect(status().is(expectedStatus));
  }

  @Test
  void fullHappyPathWithApprovalGateAndRollback() throws Exception {
    long id = createRun("northwind");

    transition(id, "READY", null, 409);          // cannot skip stages
    transition(id, "PROFILED", null, 200);
    transition(id, "INITIAL_LOAD", null, 200);

    mvc.perform(post("/api/runs/" + id + "/load")).andExpect(status().isOk())
      .andExpect(jsonPath("$.entities.loans.loaded").value(3));

    mvc.perform(get("/api/runs/" + id + "/reconciliation")).andExpect(status().isOk())
      .andExpect(jsonPath("$.balanced").value(true))
      .andExpect(jsonPath("$.paymentSourceSum").value(1010.00));
    mvc.perform(get("/api/runs/" + id + "/exceptions")).andExpect(status().isOk())
      .andExpect(jsonPath("$.length()").value(4));

    transition(id, "CDC_SYNC", null, 200);       // allowed: reconciliation balanced
    transition(id, "SHADOW_VERIFY", null, 200);
    transition(id, "READY", null, 200);
    transition(id, "FREEZE", null, 200);
    transition(id, "FINAL_SYNC", null, 200);
    mvc.perform(post("/api/runs/" + id + "/transitions").contentType(MediaType.APPLICATION_JSON)
        .content("{\"target\":\"SWITCHED\"}").with(user("op").roles("OPERATOR")))
      .andExpect(status().isForbidden());   // an operator cannot switch
    transition(id, "SWITCHED", "mallory", 200);  // the approver is the login (jeswin), not the body
    transition(id, "ROLLED_BACK", null, 200);

    mvc.perform(get("/api/runs/" + id)).andExpect(jsonPath("$.state").value("ROLLED_BACK"))
      .andExpect(jsonPath("$.approvedBy").value("jeswin"));
    transition(id, "MONITOR", null, 409);        // terminal
  }

  @Test
  void blocksProgressWhenNothingWasLoaded() throws Exception {
    long id = createRun("acme");                 // tenant with no loaded data
    transition(id, "PROFILED", null, 200);
    transition(id, "INITIAL_LOAD", null, 200);
    transition(id, "CDC_SYNC", null, 409);       // reconciliation unbalanced
    mvc.perform(post("/api/runs/" + id + "/load")).andExpect(status().isOk());
    transition(id, "CDC_SYNC", null, 200);       // now balanced
  }

  @Test
  void rejectsBadInput() throws Exception {
    mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("{\"tenantId\":\"\"}"))
      .andExpect(status().isBadRequest());
    mvc.perform(get("/api/runs/99999")).andExpect(status().isNotFound());
    long id = createRun("globex");
    transition(id, "NOT_A_STATE", null, 400);
    mvc.perform(post("/api/runs/" + id + "/load")).andExpect(status().isConflict());
  }
}
