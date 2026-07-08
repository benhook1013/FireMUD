package net.firedevops.firemud.loggingadmin.controller;

import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupDto;
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupListRequest;
import net.firedevops.firemud.loggingadmin.service.RemoteFollowupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/remote-followups")
public class RemoteFollowupController {
  private final RemoteFollowupService remoteFollowupService;

  public RemoteFollowupController(RemoteFollowupService remoteFollowupService) {
    this.remoteFollowupService = remoteFollowupService;
  }

  @GetMapping("/{tenantId}/{followupId}")
  @Timed(
      value = "getRemoteFollowup",
      description = "Read canonical remote followup by tenant-qualified followup id")
  public ResponseEntity<ApiResponse<RemoteFollowupDto>> getRemoteFollowup(
      @PathVariable String tenantId, @PathVariable String followupId) {
    return LoggingAdminRequestReaders.withBadRequest(
        () -> {
          long parsedTenantId = LoggingAdminRequestReaders.requireTenantAccess(tenantId);
          return ResponseEntity.ok(
              ApiResponse.success(
                  remoteFollowupService.getRemoteFollowup(parsedTenantId, followupId)));
        });
  }

  @GetMapping("/{tenantId}")
  @Timed(
      value = "listRemoteFollowups",
      description =
          "List canonical remote followups for a tenant with durable control-plane filters")
  public ResponseEntity<ApiResponse<java.util.List<RemoteFollowupDto>>> listRemoteFollowups(
      @PathVariable String tenantId, @ModelAttribute RemoteFollowupListRequest request) {
    return LoggingAdminRequestReaders.withBadRequest(
        () -> {
          long parsedTenantId = LoggingAdminRequestReaders.requireTenantAccess(tenantId);
          return ResponseEntity.ok(
              ApiResponse.success(
                  remoteFollowupService.listRemoteFollowups(parsedTenantId, request)));
        });
  }
}
