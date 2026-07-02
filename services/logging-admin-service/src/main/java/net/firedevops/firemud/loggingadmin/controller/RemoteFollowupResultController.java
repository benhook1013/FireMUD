package net.firedevops.firemud.loggingadmin.controller;

import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupResultDto;
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupResultListRequest;
import net.firedevops.firemud.loggingadmin.service.RemoteFollowupResultService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/remote-followup-results")
public class RemoteFollowupResultController {
  private final RemoteFollowupResultService remoteFollowupResultService;

  public RemoteFollowupResultController(RemoteFollowupResultService remoteFollowupResultService) {
    this.remoteFollowupResultService = remoteFollowupResultService;
  }

  @GetMapping("/{tenantId}/{resultId}")
  @Timed(
      value = "getRemoteFollowupResult",
      description = "Read canonical remote followup result by tenant-qualified result id")
  public ResponseEntity<ApiResponse<RemoteFollowupResultDto>> getRemoteFollowupResult(
      @PathVariable long tenantId, @PathVariable String resultId) {
    SessionContext.requireTenantAccess(tenantId);
    return ResponseEntity.ok(
        ApiResponse.success(
            remoteFollowupResultService.getRemoteFollowupResult(tenantId, resultId)));
  }

  @GetMapping("/{tenantId}")
  @Timed(
      value = "listRemoteFollowupResults",
      description =
          "List canonical remote followup results for a tenant with durable control-plane filters")
  public ResponseEntity<ApiResponse<java.util.List<RemoteFollowupResultDto>>>
      listRemoteFollowupResults(
          @PathVariable long tenantId, @ModelAttribute RemoteFollowupResultListRequest request) {
    SessionContext.requireTenantAccess(tenantId);
    return ResponseEntity.ok(
        ApiResponse.success(
            remoteFollowupResultService.listRemoteFollowupResults(tenantId, request)));
  }
}
