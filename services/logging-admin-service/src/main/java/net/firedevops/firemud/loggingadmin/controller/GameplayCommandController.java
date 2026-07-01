package net.firedevops.firemud.loggingadmin.controller;

import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.GameplayCommandStatusDto;
import net.firedevops.firemud.loggingadmin.service.GameplayCommandStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/gameplay-commands")
public class GameplayCommandController {
  private final GameplayCommandStatusService gameplayCommandStatusService;

  public GameplayCommandController(GameplayCommandStatusService gameplayCommandStatusService) {
    this.gameplayCommandStatusService = gameplayCommandStatusService;
  }

  @GetMapping("/{tenantId}/{commandId}")
  @Timed(
      value = "getGameplayCommandStatus",
      description = "Read canonical gameplay command status by tenant-qualified command id")
  public ResponseEntity<ApiResponse<GameplayCommandStatusDto>> getGameplayCommandStatus(
      @PathVariable long tenantId, @PathVariable String commandId) {
    SessionContext.requireTenantAccess(tenantId);
    return ResponseEntity.ok(
        ApiResponse.success(
            gameplayCommandStatusService.getGameplayCommandStatus(tenantId, commandId)));
  }
}
