package com.example.migration.mapping;

import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runs/{runId}/mappings")
public class MappingController {

  public record SuggestRequest(List<AiClient.SourceColumn> sources, List<AiClient.TargetColumn> targets) {
  }

  public record DecisionRequest(String decision, String decidedBy) {
  }

  private final MappingService mappings;

  public MappingController(MappingService mappings) {
    this.mappings = mappings;
  }

  @PostMapping("/suggest")
  public AiClient.MappingResult suggest(@PathVariable long runId, @RequestBody SuggestRequest request) {
    return mappings.suggest(runId, request.sources(), request.targets());
  }

  @GetMapping
  public List<Map<String, Object>> list(@PathVariable long runId) {
    return mappings.list(runId);
  }

  @PutMapping("/{source}")
  public List<Map<String, Object>> decide(@PathVariable long runId, @PathVariable String source,
                                          @RequestBody DecisionRequest request, Authentication authentication) {
    // decidedBy in the body is ignored: the reviewer is whoever is logged in.
    mappings.decide(runId, source, request.decision(), authentication.getName());
    return mappings.list(runId);
  }
}
