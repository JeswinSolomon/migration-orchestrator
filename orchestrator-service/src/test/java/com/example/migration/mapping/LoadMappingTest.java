package com.example.migration.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The legacy file uses different column names, so loading only works through approved mappings. */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:loadmappingtest;MODE=Oracle;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "migration.legacy-dir=src/test/resources/legacy-renamed"
})
@AutoConfigureMockMvc
@org.springframework.security.test.context.support.WithMockUser(username = "ana", roles = {"OPERATOR", "REVIEWER", "APPROVER", "READER"})
class LoadMappingTest {

  private static final String BODY = "{\"sources\":[{\"name\":\"customer_name\",\"samples\":[\"ANA\"]}],"
    + "\"targets\":[{\"name\":\"FULL_NAME\",\"type\":\"VARCHAR2\"}]}";

  @Autowired
  MockMvc mvc;

  @Autowired
  JdbcTemplate jdbc;

  @MockBean
  AiClient ai;

  private long runReadyForLoad(String tenant, String... approvedSources) throws Exception {
    String body = mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
        .content("{\"tenantId\":\"" + tenant + "\"}")).andReturn().getResponse().getContentAsString();
    long id = ((Number) JsonPath.read(body, "$.runId")).longValue();
    transition(id, "PROFILED");
    if (approvedSources.length > 0) {
      // Suggestion table: source -> target
      var suggestions = new java.util.ArrayList<AiClient.Suggestion>();
      for (String s : approvedSources) {
        String target = switch (s) {
          case "customer_name" -> "FULL_NAME";
          case "email_address" -> "EMAIL";
          case "cust_ph" -> "PHONE";
          case "birthdate" -> "DOB";
          default -> "FULL_NAME";
        };
        suggestions.add(new AiClient.Suggestion(s, target, 0.9, "synonym", "test", false));
      }
      when(ai.suggest(anyList(), anyList()))
        .thenReturn(new AiClient.MappingResult(suggestions, List.of(), List.of(), false, List.of()));
      mvc.perform(post("/api/runs/" + id + "/mappings/suggest").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk());
      for (String s : approvedSources) {
        mvc.perform(put("/api/runs/" + id + "/mappings/" + s).contentType(MediaType.APPLICATION_JSON)
            .content("{\"decision\":\"APPROVED\",\"decidedBy\":\"ana\"}"))
          .andExpect(status().isOk());
      }
    }
    transition(id, "INITIAL_LOAD");
    return id;
  }

  private void transition(long id, String target) throws Exception {
    mvc.perform(post("/api/runs/" + id + "/transitions").contentType(MediaType.APPLICATION_JSON)
        .content("{\"target\":\"" + target + "\"}"))
      .andExpect(status().isOk());
  }

  @Test
  void approvedMappingsDecideWhichLegacyColumnsFeedEachTarget() throws Exception {
    long id = runReadyForLoad("mapped", "customer_name", "email_address", "cust_ph", "birthdate");

    mvc.perform(post("/api/runs/" + id + "/load"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.entities.borrowers.loaded").value(3));

    assertThat(jdbc.queryForObject("SELECT FULL_NAME FROM BORROWER WHERE TENANT_ID = 'mapped' AND EMAIL = 'ana@example.com'",
      String.class)).isEqualTo("Ana Smith");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BORROWER WHERE TENANT_ID = 'mapped' AND DOB IS NOT NULL",
      Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM MIG_AUDIT WHERE RUN_ID = ? AND ACTION = 'LOAD_COLUMNS'",
      Integer.class, id)).isEqualTo(1);
  }

  @Test
  void loadIsBlockedWhenNoColumnMapsToFullName() throws Exception {
    long id = runReadyForLoad("unmapped");

    mvc.perform(post("/api/runs/" + id + "/load"))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.error").value("Load blocked: No source column is mapped to FULL_NAME"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BORROWER WHERE TENANT_ID = 'unmapped'", Integer.class)).isZero();
  }

  @Test
  void approvedMappingToMissingColumnBlocksTheLoad() throws Exception {
    long id = runReadyForLoad("badmap", "nonexistent_col");

    mvc.perform(post("/api/runs/" + id + "/load"))
      .andExpect(status().isConflict());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BORROWER WHERE TENANT_ID = 'badmap'", Integer.class)).isZero();
  }
}
