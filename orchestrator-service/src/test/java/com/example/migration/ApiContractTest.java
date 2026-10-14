package com.example.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileReader;
import java.io.Reader;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:contract;MODE=Oracle;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password="
})
class ApiContractTest {

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping handlerMapping;

  @SuppressWarnings("unchecked")
  private Set<String> documented() throws Exception {
    try (Reader reader = new FileReader("../docs/contracts/orchestrator-openapi.yaml")) {
      Map<String, Object> spec = new Yaml().load(reader);
      Set<String> found = new HashSet<>();
      ((Map<String, Map<String, Object>>) spec.get("paths")).forEach((path, ops) ->
        ops.keySet().forEach(method -> found.add(method.toUpperCase() + " " + path)));
      return found;
    }
  }

  private Set<String> implemented() {
    Set<String> found = new HashSet<>();
    for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
      for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
        if (!pattern.startsWith("/api/")) {
          continue;
        }
        for (RequestMethod method : info.getMethodsCondition().getMethods()) {
          found.add(method + " " + pattern);
        }
      }
    }
    return found;
  }

  @Test
  void documentedEndpointsMatchTheRealOnes() throws Exception {
    Set<String> documented = documented();
    Set<String> implemented = implemented();

    Set<String> missingFromContract = new HashSet<>(implemented);
    missingFromContract.removeAll(documented);
    Set<String> notImplemented = new HashSet<>(documented);
    notImplemented.removeAll(implemented);

    assertThat(missingFromContract).as("endpoints in code but not in the OpenAPI contract").isEmpty();
    assertThat(notImplemented).as("endpoints in the OpenAPI contract but not in code").isEmpty();
  }
}
