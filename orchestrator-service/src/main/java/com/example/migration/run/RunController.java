package com.example.migration.run;

import com.example.migration.load.LoadSummary;
import com.example.migration.load.ShadowVerifier;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runs")
public class RunController {

  public record CreateRun(String tenantId) {
  }

  public record TransitionRequest(String target, String approvedBy) {
  }

  private final RunService runs;
  private final ReconciliationService reconciliation;

  public RunController(RunService runs, ReconciliationService reconciliation) {
    this.runs = runs;
    this.reconciliation = reconciliation;
  }

  @PostMapping
  public ResponseEntity<RunService.Run> create(@RequestBody CreateRun request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(runs.create(request.tenantId()));
  }

  @GetMapping
  public List<RunService.Run> list() {
    return runs.list();
  }

  @GetMapping("/{runId}")
  public RunService.Run get(@PathVariable long runId) {
    return runs.get(runId);
  }

  @PostMapping("/{runId}/transitions")
  public RunService.Run transition(@PathVariable long runId, @RequestBody TransitionRequest request,
                                   Authentication authentication) {
    boolean switching = request.target() != null && "SWITCHED".equalsIgnoreCase(request.target().trim());
    if (switching && !hasRole(authentication, "APPROVER")) {
      throw new RunException(HttpStatus.FORBIDDEN, "Only an approver can switch the run");
    }
    // The approver is whoever is logged in; the name in the request body is ignored so it cannot be spoofed.
    return runs.transition(runId, request.target(), authentication.getName(),
      switching ? authentication.getName() : null);
  }

  private static boolean hasRole(Authentication authentication, String role) {
    return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
  }

  @PostMapping("/{runId}/load")
  public LoadSummary load(@PathVariable long runId) {
    return runs.load(runId);
  }

  @GetMapping("/{runId}/exceptions")
  public List<Map<String, Object>> exceptions(@PathVariable long runId) {
    return runs.exceptions(runId);
  }

  @GetMapping("/{runId}/shadow-diff")
  public ShadowVerifier.Report shadowDiff(@PathVariable long runId) {
    return runs.shadowDiff(runId);
  }

  @GetMapping("/{runId}/audit")
  public List<Map<String, Object>> audit(@PathVariable long runId) {
    return runs.audit(runId);
  }

  @GetMapping("/{runId}/reconciliation")
  public ReconciliationService.Report reconciliation(@PathVariable long runId) {
    return reconciliation.reconcile(runs.get(runId).tenantId());
  }
}
