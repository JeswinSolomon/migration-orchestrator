package com.example.migration.load;

import java.util.LinkedHashMap;
import java.util.Map;

public record LoadSummary(long runId, Map<String, EntityResult> entities) {

  public static class EntityResult {
    public int read;
    public int loaded;
    public int skipped;
    public int quarantined;
    public final Map<String, Integer> reasons = new LinkedHashMap<>();

    void quarantine(String reason) {
      quarantined++;
      reasons.merge(reason, 1, Integer::sum);
    }
  }
}
