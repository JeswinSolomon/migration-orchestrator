package com.example.migration.mapping;

import com.example.migration.run.RunException;
import com.example.migration.run.RunService;
import com.example.migration.run.RunState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MappingService {

  private static final Set<String> DECISIONS = Set.of("APPROVED", "REJECTED");
  // Mappings can only change before data starts loading.
  private static final Set<RunState> EDITABLE = Set.of(RunState.PLANNED, RunState.PROFILED);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final RunService runs;
  private final AiClient ai;

  public MappingService(JdbcTemplate jdbc, PlatformTransactionManager tm, RunService runs, AiClient ai) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(tm);
    this.runs = runs;
    this.ai = ai;
  }

  /** Asks the AI for suggestions and stores them as PENDING; replaces any earlier suggestions for the run. */
  public AiClient.MappingResult suggest(long runId, List<AiClient.SourceColumn> sources,
                                        List<AiClient.TargetColumn> targets) {
    requireEditable(runId);
    if (sources == null || sources.isEmpty() || targets == null || targets.isEmpty()) {
      throw new RunException(HttpStatus.BAD_REQUEST, "sources and targets are required");
    }
    AiClient.MappingResult result = ai.suggest(sources, targets);
    tx.executeWithoutResult(status -> {
      jdbc.update("DELETE FROM MIG_MAPPING WHERE RUN_ID = ?", runId);
      for (AiClient.Suggestion s : result.suggestions()) {
        jdbc.update("INSERT INTO MIG_MAPPING (RUN_ID, SOURCE_COL, TARGET_COL, CONFIDENCE, METHOD, REASON, "
            + "REQUIRES_REVIEW, DECISION) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')",
          runId, s.source(), s.target(), s.confidence(), s.method(), s.reason(), s.requiresReview() ? 1 : 0);
      }
      audit(runId, "ai-service", "MAPPING_SUGGESTED",
        result.suggestions().size() + " suggestions, llmUsed=" + result.llmUsed());
    });
    return result;
  }

  public List<Map<String, Object>> list(long runId) {
    runs.get(runId);
    return jdbc.queryForList("SELECT SOURCE_COL, TARGET_COL, CONFIDENCE, METHOD, REASON, REQUIRES_REVIEW, "
      + "DECISION, DECIDED_BY FROM MIG_MAPPING WHERE RUN_ID = ? ORDER BY MAPPING_ID", runId);
  }

  public void decide(long runId, String source, String decision, String decidedBy) {
    requireEditable(runId);
    String normalized = decision == null ? "" : decision.trim().toUpperCase();
    if (!DECISIONS.contains(normalized)) {
      throw new RunException(HttpStatus.BAD_REQUEST, "decision must be APPROVED or REJECTED");
    }
    if (decidedBy == null || decidedBy.isBlank()) {
      throw new RunException(HttpStatus.FORBIDDEN, "A named human must decide (decidedBy is required)");
    }
    tx.executeWithoutResult(status -> {
      int updated = jdbc.update("UPDATE MIG_MAPPING SET DECISION = ?, DECIDED_BY = ?, DECIDED_AT = SYSTIMESTAMP "
        + "WHERE RUN_ID = ? AND SOURCE_COL = ?", normalized, decidedBy.trim(), runId, source);
      if (updated == 0) {
        throw new RunException(HttpStatus.NOT_FOUND, "No suggestion for source column " + source);
      }
      audit(runId, decidedBy.trim(), "MAPPING_" + normalized, source);
    });
  }

  private void requireEditable(long runId) {
    RunState state = runs.get(runId).state();
    if (!EDITABLE.contains(state)) {
      throw new RunException(HttpStatus.CONFLICT, "Mappings are locked once the run is " + state);
    }
  }

  private void audit(long runId, String actor, String action, String detail) {
    jdbc.update("INSERT INTO MIG_AUDIT (RUN_ID, ACTOR, ACTION, DETAIL) VALUES (?, ?, ?, ?)", runId, actor, action, detail);
  }
}
