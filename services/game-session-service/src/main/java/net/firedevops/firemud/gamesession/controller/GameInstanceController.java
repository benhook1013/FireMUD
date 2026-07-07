package net.firedevops.firemud.gamesession.controller;

import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.SessionIdParsing;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for managing game instances.
 *
 * <p>These routes are an operator/bootstrap convenience, not the canonical gameplay admission seam.
 * Player-facing admission policy lives on the gRPC `StartSession` path.
 */
@RestController
@RequestMapping("/sessions")
public class GameInstanceController {
  private final GameInstanceService gameInstanceService;

  public GameInstanceController(GameInstanceService gameInstanceService) {
    this.gameInstanceService = gameInstanceService;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<GameInstanceDto>> startSession(
      @Valid @RequestBody StartSessionRequest request) {
    SessionContext.requireGlobalPrivilegedRole();
    GameInstanceDto dto = gameInstanceService.startSession(request, false);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @PostMapping("/{sessionId}/stop")
  public ResponseEntity<ApiResponse<GameInstanceDto>> stopSession(@PathVariable String sessionId) {
    SessionContext.requireGlobalPrivilegedRole();
    GameInstanceDto dto = gameInstanceService.stopSession(SessionIdParsing.require(sessionId));
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @PostMapping("/{sessionId}/restart")
  public ResponseEntity<ApiResponse<GameInstanceDto>> restartSession(
      @PathVariable String sessionId) {
    SessionContext.requireGlobalPrivilegedRole();
    GameInstanceDto dto = gameInstanceService.restartSession(SessionIdParsing.require(sessionId));
    return ResponseEntity.ok(ApiResponse.success(dto));
  }
}
