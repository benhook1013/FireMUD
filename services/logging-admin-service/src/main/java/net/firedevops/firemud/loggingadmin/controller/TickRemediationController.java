package net.firedevops.firemud.loggingadmin.controller;

import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.TickRemediationActionDto;
import net.firedevops.firemud.loggingadmin.dto.TickRemediationRequest;
import net.firedevops.firemud.loggingadmin.service.TickRemediationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tick-remediation")
public class TickRemediationController {
  private final TickRemediationService tickRemediationService;

  public TickRemediationController(TickRemediationService tickRemediationService) {
    this.tickRemediationService = tickRemediationService;
  }

  @PostMapping("/pause")
  @Timed(value = "tickRemediationPause", description = "Pause ticks for a scoped runtime target")
  public ResponseEntity<ApiResponse<TickRemediationActionDto>> pause(
      @Valid @RequestBody TickRemediationRequest request) {
    SessionContext.requireTenantAccess(request.tenantId());
    return ResponseEntity.ok(
        ApiResponse.success(tickRemediationService.pauseTicksForScope(request)));
  }

  @PostMapping("/resume")
  @Timed(value = "tickRemediationResume", description = "Resume ticks for a scoped runtime target")
  public ResponseEntity<ApiResponse<TickRemediationActionDto>> resume(
      @Valid @RequestBody TickRemediationRequest request) {
    SessionContext.requireTenantAccess(request.tenantId());
    return ResponseEntity.ok(
        ApiResponse.success(tickRemediationService.resumeTicksForScope(request)));
  }
}
