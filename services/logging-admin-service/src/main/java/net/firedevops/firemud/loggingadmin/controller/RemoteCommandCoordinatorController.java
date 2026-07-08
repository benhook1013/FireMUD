package net.firedevops.firemud.loggingadmin.controller;

import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.loggingadmin.dto.RemoteCommandCoordinatorDto;
import net.firedevops.firemud.loggingadmin.dto.RemoteCommandCoordinatorListRequest;
import net.firedevops.firemud.loggingadmin.service.RemoteCommandCoordinatorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
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

  @GetMapping("/{tenantId}")
  @Timed(
      value = "listRemoteCommandCoordinators",
      description = "List canonical remote command coordinators for a tenant with bounded filters")
  public ResponseEntity<ApiResponse<java.util.List<RemoteCommandCoordinatorDto>>>
      listRemoteCommandCoordinators(
          @PathVariable String tenantId,
          @ModelAttribute RemoteCommandCoordinatorListRequest request) {
    return LoggingAdminRequestReaders.withBadRequest(
        () -> {
          long parsedTenantId = LoggingAdminRequestReaders.requireTenantAccess(tenantId);
          return ResponseEntity.ok(
              ApiResponse.success(
                  remoteCommandCoordinatorService.listRemoteCommandCoordinators(
                      parsedTenantId, request)));
        });
  }

  @GetMapping("/{tenantId}/{coordinatorId}")
  @Timed(
      value = "getRemoteCommandCoordinator",
      description = "Read canonical remote command coordinator by tenant-qualified coordinator id")
  public ResponseEntity<ApiResponse<RemoteCommandCoordinatorDto>> getRemoteCommandCoordinator(
      @PathVariable String tenantId, @PathVariable String coordinatorId) {
    return LoggingAdminRequestReaders.withBadRequest(
        () -> {
          long parsedTenantId = LoggingAdminRequestReaders.requireTenantAccess(tenantId);
          return ResponseEntity.ok(
              ApiResponse.success(
                  remoteCommandCoordinatorService.getRemoteCommandCoordinator(
                      parsedTenantId, coordinatorId)));
        });
  }
}
