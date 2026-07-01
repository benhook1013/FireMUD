package net.firedevops.firemud.loggingadmin.controller;

import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.RemoteCommandCoordinatorDto;
import net.firedevops.firemud.loggingadmin.service.RemoteCommandCoordinatorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/remote-command-coordinators")
public class RemoteCommandCoordinatorController {
  private final RemoteCommandCoordinatorService remoteCommandCoordinatorService;

  public RemoteCommandCoordinatorController(
      RemoteCommandCoordinatorService remoteCommandCoordinatorService) {
    this.remoteCommandCoordinatorService = remoteCommandCoordinatorService;
  }

  @GetMapping("/{tenantId}/{coordinatorId}")
  @Timed(
      value = "getRemoteCommandCoordinator",
      description = "Read canonical remote command coordinator by tenant-qualified coordinator id")
  public ResponseEntity<ApiResponse<RemoteCommandCoordinatorDto>> getRemoteCommandCoordinator(
      @PathVariable long tenantId, @PathVariable String coordinatorId) {
    SessionContext.requireTenantAccess(tenantId);
    return ResponseEntity.ok(
        ApiResponse.success(
            remoteCommandCoordinatorService.getRemoteCommandCoordinator(tenantId, coordinatorId)));
  }
}
