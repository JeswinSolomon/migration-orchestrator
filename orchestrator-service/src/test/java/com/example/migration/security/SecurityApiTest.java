package com.example.migration.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
  "spring.datasource.url=jdbc:h2:mem:sec;MODE=Oracle;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password="
})
class SecurityApiTest {

  @Autowired MockMvc mvc;

  @Test
  void unauthenticatedRequestsAreRejectedButHealthIsOpen() throws Exception {
    mvc.perform(get("/api/runs/1")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
  }

  @Test
  void basicAuthChecksThePassword() throws Exception {
    mvc.perform(get("/api/runs/999").with(httpBasic("tester", "wrong"))).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/runs/999").with(httpBasic("tester", "test-pass"))).andExpect(status().isNotFound());
  }

  @Test
  void aReaderCanLookButNotTouch() throws Exception {
    var reader = user("rita").roles("READER");
    mvc.perform(get("/api/runs/999").with(reader)).andExpect(status().isNotFound());
    mvc.perform(post("/api/runs").with(reader).contentType(MediaType.APPLICATION_JSON)
      .content("{\"tenantId\":\"x\"}")).andExpect(status().isForbidden());
    mvc.perform(post("/api/runs/1/transitions").with(reader).contentType(MediaType.APPLICATION_JSON)
      .content("{\"target\":\"READY\"}")).andExpect(status().isForbidden());
  }

  @Test
  void onlyAReviewerDecidesMappingsAndOnlyOperatorOrApproverTransitions() throws Exception {
    mvc.perform(put("/api/runs/1/mappings/full_name").with(user("op").roles("OPERATOR", "APPROVER"))
      .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\"}"))
      .andExpect(status().isForbidden());
    mvc.perform(post("/api/runs/1/transitions").with(user("rev").roles("REVIEWER"))
      .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"READY\"}"))
      .andExpect(status().isForbidden());
  }

  @Test
  void unknownPathsAreDenied() throws Exception {
    mvc.perform(get("/internal/secret").with(user("tester").roles("OPERATOR", "REVIEWER", "APPROVER", "READER")))
      .andExpect(status().isForbidden());
  }
}
