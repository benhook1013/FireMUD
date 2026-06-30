package net.firedevops.firemud.gamesession.controller;

import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionRoleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sessions")
public class SessionRoleController {
  private final SessionRoleService sessionRoleService;

  public SessionRoleController(SessionRoleService sessionRoleService) {
    this.sessionRoleService = sessionRoleService;
  }

  @PostMapping("/{sessionId}/refresh-roles")
  public ResponseEntity<ApiResponse<String>> refreshRoles(@PathVariable long sessionId) {
    SessionContext.requireGlobalPrivilegedRole();
    String result = sessionRoleService.refreshRoles(sessionId);
    return ResponseEntity.ok(ApiResponse.success(result));
  }
}
