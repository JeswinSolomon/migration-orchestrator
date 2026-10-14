package com.example.migration.mapping;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.migration.run.RunException;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:mappingtest;MODE=Oracle;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "migration.legacy-dir=src/test/resources/legacy"
})
@AutoConfigureMockMvc
@org.springframework.security.test.context.support.WithMockUser(username = "ana", roles = {"OPERATOR", "REVIEWER", "APPROVER", "READER"})
class MappingApiTest {

  private static final String OPERATOR_ONLY = "operator-only";

  private static final String SUGGEST_BODY = "{\"sources\":[{\"name\":\"full_name\",\"samples\":[\"ANA\"]},"
    + "{\"name\":\"birthdate\",\"samples\":[\"1990-05-01\"]}],"
    + "\"targets\":[{\"name\":\"FULL_NAME\",\"type\":\"VARCHAR2\"},{\"name\":\"DOB\",\"type\":\"DATE\"}]}";

  @Autowired
  MockMvc mvc;

  @MockBean
  AiClient ai;

  private void stubAi() {
    when(ai.suggest(anyList(), anyList())).thenReturn(new AiClient.MappingResult(List.of(
      new AiClient.Suggestion("full_name", "FULL_NAME", 0.95, "exact", "same name", false),
      new AiClient.Suggestion("birthdate", "DOB", 0.85, "synonym", "synonym", true)),
      List.of(), List.of(), false, List.of()));
  }

  private long createRun() throws Exception {
    String body = mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
        .content("{\"tenantId\":\"northwind\"}"))
      .andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(body, "$.runId")).longValue();
  }

  private void transition(long id, String target, int expected) throws Exception {
    mvc.perform(post("/api/runs/" + id + "/transitions").contentType(MediaType.APPLICATION_JSON)
        .content("{\"target\":\"" + target + "\"}"))
      .andExpect(status().is(expected));
  }

  private void decide(long id, String source, String json, int expected) throws Exception {
    mvc.perform(put("/api/runs/" + id + "/mappings/" + source).contentType(MediaType.APPLICATION_JSON).content(json))
      .andExpect(status().is(expected));
  }

  @Test
  void pendingMappingsBlockLoadUntilAHumanDecidesEach() throws Exception {
    stubAi();
    long id = createRun();
    transition(id, "PROFILED", 200);

    mvc.perform(post("/api/runs/" + id + "/mappings/suggest").contentType(MediaType.APPLICATION_JSON)
        .content(SUGGEST_BODY))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.suggestions.length()").value(2));
    mvc.perform(get("/api/runs/" + id + "/mappings"))
      .andExpect(jsonPath("$[0].DECISION").value("PENDING"));

    transition(id, "INITIAL_LOAD", 409);

    mvc.perform(put("/api/runs/" + id + "/mappings/full_name").contentType(MediaType.APPLICATION_JSON)
        .content("{\"decision\":\"APPROVED\"}").with(user(OPERATOR_ONLY).roles("OPERATOR")))
      .andExpect(status().isForbidden());   // only a reviewer may decide
    decide(id, "full_name", "{\"decision\":\"MAYBE\"}", 400);
    decide(id, "nope", "{\"decision\":\"APPROVED\"}", 404);
    decide(id, "full_name", "{\"decision\":\"APPROVED\",\"decidedBy\":\"mallory\"}", 200);
    mvc.perform(get("/api/runs/" + id + "/mappings"))
      .andExpect(jsonPath("$[0].DECIDED_BY").value("ana"));   // the login wins over the body
    transition(id, "INITIAL_LOAD", 409);
    decide(id, "birthdate", "{\"decision\":\"REJECTED\"}", 200);

    transition(id, "INITIAL_LOAD", 200);
    decide(id, "birthdate", "{\"decision\":\"APPROVED\"}", 409);
  }

  @Test
  void aiOutageIsReportedAsBadGatewayAndStoresNothing() throws Exception {
    when(ai.suggest(anyList(), anyList()))
      .thenThrow(new RunException(HttpStatus.BAD_GATEWAY, "AI service unavailable"));
    long id = createRun();

    mvc.perform(post("/api/runs/" + id + "/mappings/suggest").contentType(MediaType.APPLICATION_JSON)
        .content(SUGGEST_BODY))
      .andExpect(status().isBadGateway());
    mvc.perform(get("/api/runs/" + id + "/mappings"))
      .andExpect(jsonPath("$.length()").value(0));
    transition(id, "PROFILED", 200);
  }

  @Test
  void suggestRejectsEmptyInput() throws Exception {
    long id = createRun();
    mvc.perform(post("/api/runs/" + id + "/mappings/suggest").contentType(MediaType.APPLICATION_JSON)
        .content("{\"sources\":[],\"targets\":[]}"))
      .andExpect(status().isBadRequest());
  }
}
