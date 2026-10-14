package com.example.migration.mapping;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Port to the AI service, so tests and other providers can replace the HTTP implementation. */
public interface AiClient {

  record SourceColumn(String name, List<String> samples) {
  }

  record TargetColumn(String name, String type) {
  }

  record Suggestion(String source, String target, double confidence, String method, String reason,
                    @JsonProperty("requires_review") boolean requiresReview) {
  }

  record MappingResult(List<Suggestion> suggestions,
                       @JsonProperty("unmapped_sources") List<String> unmappedSources,
                       @JsonProperty("unmapped_targets") List<String> unmappedTargets,
                       @JsonProperty("llm_used") boolean llmUsed,
                       List<String> warnings) {
  }

  MappingResult suggest(List<SourceColumn> sources, List<TargetColumn> targets);
}
