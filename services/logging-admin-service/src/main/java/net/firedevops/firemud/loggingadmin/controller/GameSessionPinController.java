package net.firedevops.firemud.loggingadmin.controller;

import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.loggingadmin.dto.GameSessionPinConvergenceDto;
import net.firedevops.firemud.loggingadmin.dto.PinnedScriptPatchVersionDto;
import net.firedevops.firemud.loggingadmin.service.GameSessionPinService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/game-session-pins")
public class GameSessionPinController {
  private final GameSessionPinService gameSessionPinService;

  public GameSessionPinController(GameSessionPinService gameSessionPinService) {
    this.gameSessionPinService = gameSessionPinService;
  }

  @GetMapping("/{tenantId}/{gameInstanceId}")
  @Timed(
      value = "getPinnedScriptPatchVersion",
      description = "Read current pinned script patch version for one runtime")
  public ResponseEntity<ApiResponse<PinnedScriptPatchVersionDto>> getPinnedScriptPatchVersion(
      @PathVariable String tenantId, @PathVariable String gameInstanceId) {
    return LoggingAdminRequestReaders.withBadRequest(
        () -> {
          long parsedTenantId = LoggingAdminRequestReaders.requireTenantAccess(tenantId);
          long parsedGameInstanceId =
              LoggingAdminRequestReaders.requirePositiveLong(gameInstanceId, "gameInstanceId");
          return ResponseEntity.ok(
              ApiResponse.success(
                  gameSessionPinService.getPinnedScriptPatchVersion(
                      parsedTenantId, parsedGameInstanceId)));
        });
  }

  @GetMapping("/{tenantId}/{gameInstanceId}/convergence")
  @Timed(
      value = "getGameSessionPinConvergence",
      description = "Read persisted Game Session pin convergence for one runtime")
  public ResponseEntity<ApiResponse<GameSessionPinConvergenceDto>> getGameSessionPinConvergence(
      @PathVariable String tenantId, @PathVariable String gameInstanceId) {
    return LoggingAdminRequestReaders.withBadRequest(
        () -> {
          long parsedTenantId = LoggingAdminRequestReaders.requireTenantAccess(tenantId);
          long parsedGameInstanceId =
              LoggingAdminRequestReaders.requirePositiveLong(gameInstanceId, "gameInstanceId");
          return ResponseEntity.ok(
              ApiResponse.success(
                  gameSessionPinService.getGameSessionPinConvergence(
                      parsedTenantId, parsedGameInstanceId)));
        });
  }
}
