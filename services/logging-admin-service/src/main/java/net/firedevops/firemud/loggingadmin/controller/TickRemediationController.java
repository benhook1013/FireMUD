package net.firedevops.firemud.loggingadmin.controller;

import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.RuntimeOwnershipStatusDto;
import net.firedevops.firemud.loggingadmin.dto.TickRemediationActionDto;
import net.firedevops.firemud.loggingadmin.dto.TickRemediationRequest;
import net.firedevops.firemud.loggingadmin.service.TickRemediationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tick-remediation")
public class TickRemediationController {
  private final TickRemediationService tickRemediationService;

  public TickRemediationController(TickRemediationService tickRemediationService) {
    this.tickRemediationService = tickRemediationService;
  }

  @GetMapping("/status/{tenantId}")
  @Timed(
      value = "tickRemediationStatus",
      description = "Read durable runtime ownership status for a scoped runtime target")
  public ResponseEntity<ApiResponse<RuntimeOwnershipStatusDto>> getRuntimeOwnershipStatus(
      @PathVariable String tenantId,
      @RequestParam(required = false) String gameInstanceId,
      @RequestParam(required = false) String regionId) {
    return LoggingAdminRequestReaders.withBadRequest(
        () -> {
          long parsedTenantId = LoggingAdminRequestReaders.requireTenantAccess(tenantId);
          String normalizedGameInstanceId =
              LoggingAdminRequestReaders.requireOptionalPositiveLongText(
                  gameInstanceId, "gameInstanceId");
          return ResponseEntity.ok(
              ApiResponse.success(
                  tickRemediationService.getRuntimeOwnershipStatus(
                      parsedTenantId, normalizedGameInstanceId, regionId)));
        });
  }

  @PostMapping("/pause")
  @Timed(value = "tickRemediationPause", description = "Pause ticks for a scoped runtime target")
  public ResponseEntity<ApiResponse<TickRemediationActionDto>> pause(
      @Valid @RequestBody TickRemediationRequest request) {
    return LoggingAdminRequestReaders.withBadRequest(
        () ->
            ResponseEntity.ok(
                ApiResponse.success(
                    tickRemediationService.pauseTicksForScope(
                        requestWithAuthorizedTenant(request, request.tenantId())))));
  }

  @PostMapping("/resume")
  @Timed(value = "tickRemediationResume", description = "Resume ticks for a scoped runtime target")
  public ResponseEntity<ApiResponse<TickRemediationActionDto>> resume(
      @Valid @RequestBody TickRemediationRequest request) {
    return LoggingAdminRequestReaders.withBadRequest(
        () ->
            ResponseEntity.ok(
                ApiResponse.success(
                    tickRemediationService.resumeTicksForScope(
                        requestWithAuthorizedTenant(request, request.tenantId())))));
  }

  private TickRemediationRequest requestWithAuthorizedTenant(
      TickRemediationRequest request, Long tenantId) {
    SessionContext.requireTenantAccess(tenantId);
    return new TickRemediationRequest(
        request.tenantId(),
        LoggingAdminRequestReaders.requireOptionalPositiveLongText(
            request.gameInstanceId(), "gameInstanceId"),
        request.regionId(),
        request.reason());
  }
}
