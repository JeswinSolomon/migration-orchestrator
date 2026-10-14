package com.example.migration.run;

import com.example.migration.load.BulkLoader;
import com.example.migration.load.LoadSummary;
import com.example.migration.load.ShadowVerifier;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RunService {

  public record Run(long runId, String tenantId, RunState state, String approvedBy) {
  }

  // Moving into these states is only allowed when reconciliation proves the data is complete.
  private static final Set<RunState> NEEDS_BALANCED_RECON = Set.of(RunState.CDC_SYNC, RunState.READY);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ReconciliationService reconciliation;
  private final BulkLoader loader;
  private final ShadowVerifier shadow;
  private final Path legacyDir;
  private final MeterRegistry metrics;

  public RunService(JdbcTemplate jdbc, PlatformTransactionManager tm, ReconciliationService reconciliation,
                    BulkLoader loader, ShadowVerifier shadow, @Value("${migration.legacy-dir}") String legacyDir,
                    MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(tm);
    this.reconciliation = reconciliation;
    this.loader = loader;
    this.shadow = shadow;
    this.legacyDir = Paths.get(legacyDir);
    this.metrics = metrics;
  }

  private RunException precheckFailed(String check, String message) {
    metrics.counter("migration.precheck.failed", "check", check).increment();
    return new RunException(HttpStatus.CONFLICT, "Pre-check failed: " + message);
  }

  public Run create(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) {
      throw new RunException(HttpStatus.BAD_REQUEST, "tenantId is required");
    }
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(con -> {
      var ps = con.prepareStatement("INSERT INTO MIG_RUN (TENANT_ID, STATE) VALUES (?, ?)", new String[] {"RUN_ID"});
      ps.setString(1, tenantId.trim());
      ps.setString(2, RunState.PLANNED.name());
      return ps;
    }, keys);
    long runId = keys.getKey().longValue();
    audit(runId, "system", "CREATED", tenantId);
    return get(runId);
  }

  public Run get(long runId) {
    List<Run> found = jdbc.query("SELECT RUN_ID, TENANT_ID, STATE, APPROVED_BY FROM MIG_RUN WHERE RUN_ID = ?",
      (rs, i) -> new Run(rs.getLong(1), rs.getString(2), RunState.valueOf(rs.getString(3)), rs.getString(4)), runId);
    if (found.isEmpty()) {
      throw new RunException(HttpStatus.NOT_FOUND, "Run " + runId + " not found");
    }
    return found.get(0);
  }

  public List<Run> list() {
    return jdbc.query("SELECT RUN_ID, TENANT_ID, STATE, APPROVED_BY FROM MIG_RUN ORDER BY RUN_ID",
      (rs, i) -> new Run(rs.getLong(1), rs.getString(2), RunState.valueOf(rs.getString(3)), rs.getString(4)));
  }

  public Run transition(long runId, String targetName, String actor, String approvedBy) {
    RunState target;
    try {
      target = RunState.valueOf(targetName == null ? "" : targetName.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new RunException(HttpStatus.BAD_REQUEST, "Unknown state: " + targetName);
    }
    Run run = get(runId);
    if (!run.state().canMoveTo(target)) {
      throw new RunException(HttpStatus.CONFLICT, "Illegal transition " + run.state() + " -> " + target);
    }
    if (target == RunState.SWITCHED && (approvedBy == null || approvedBy.isBlank())) {
      throw new RunException(HttpStatus.FORBIDDEN, "SWITCHED requires approvedBy");
    }
    if (target == RunState.INITIAL_LOAD) {
      Integer pending = jdbc.queryForObject(
        "SELECT COUNT(*) FROM MIG_MAPPING WHERE RUN_ID = ? AND DECISION = 'PENDING'", Integer.class, runId);
      if (pending != null && pending > 0) {
        throw precheckFailed("mappings", pending + " mapping(s) still need review");
      }
    }
    if (target == RunState.READY) {
      int mismatches = shadowDiff(runId).mismatchCount();
      if (mismatches > 0) {
        throw precheckFailed("shadow", "shadow read found " + mismatches + " mismatch(es)");
      }
    }
    if (NEEDS_BALANCED_RECON.contains(target) && !reconciliation.reconcile(run.tenantId()).balanced()) {
      throw precheckFailed("reconciliation", "reconciliation is not balanced");
    }

    tx.executeWithoutResult(status -> {
      // The state in the WHERE clause stops two callers racing through the same transition.
      int updated = jdbc.update("UPDATE MIG_RUN SET STATE = ?, UPDATED_AT = SYSTIMESTAMP, "
          + "APPROVED_BY = COALESCE(?, APPROVED_BY) WHERE RUN_ID = ? AND STATE = ?",
        target.name(), blankToNull(approvedBy), runId, run.state().name());
      if (updated == 0) {
        throw new RunException(HttpStatus.CONFLICT, "Run changed concurrently; retry");
      }
      audit(runId, actor == null || actor.isBlank() ? "system" : actor, "TRANSITION", run.state() + " -> " + target);
    });
    metrics.counter("migration.transitions", "to", target.name()).increment();
    return get(runId);
  }

  public LoadSummary load(long runId) {
    Run run = get(runId);
    if (run.state() != RunState.INITIAL_LOAD) {
      throw new RunException(HttpStatus.CONFLICT, "Load is only allowed in INITIAL_LOAD, run is " + run.state());
    }
    try {
      return metrics.timer("migration.load.duration").recordCallable(() -> loader.load(runId, run.tenantId(), legacyDir));
    } catch (IllegalArgumentException e) {
      throw new RunException(HttpStatus.CONFLICT, "Load blocked: " + e.getMessage());
    } catch (IOException e) {
      throw new RunException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read legacy files: " + e.getMessage());
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RunException(HttpStatus.INTERNAL_SERVER_ERROR, "Load failed: " + e.getMessage());
    }
  }

  public ShadowVerifier.Report shadowDiff(long runId) {
    Run run = get(runId);
    try {
      return shadow.verify(runId, run.tenantId(), legacyDir);
    } catch (IOException e) {
      throw new RunException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read legacy files: " + e.getMessage());
    }
  }

  public List<java.util.Map<String, Object>> audit(long runId) {
    get(runId);
    return jdbc.queryForList("SELECT AUDIT_ID, ACTOR, ACTION, DETAIL, CREATED_AT FROM MIG_AUDIT "
      + "WHERE RUN_ID = ? ORDER BY AUDIT_ID", runId);
  }

  public List<java.util.Map<String, Object>> exceptions(long runId) {
    get(runId);
    return jdbc.queryForList("SELECT EXCEPTION_ID, ENTITY, LEGACY_ID, REASON_CODE, RESOLVED FROM MIG_EXCEPTION "
      + "WHERE RUN_ID = ? ORDER BY EXCEPTION_ID", runId);
  }

  private void audit(long runId, String actor, String action, String detail) {
    jdbc.update("INSERT INTO MIG_AUDIT (RUN_ID, ACTOR, ACTION, DETAIL) VALUES (?, ?, ?, ?)", runId, actor, action, detail);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
