package net.firedevops.firemud.gamesession.service.impl;

import io.jsonwebtoken.JwtException;
import java.util.List;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionClaims;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.springframework.util.StringUtils;

final class GameplayPresenceRoleClassifier {
  private GameplayPresenceRoleClassifier() {}

  static GameplayPresenceRole classifyRole(SessionContext context, JwtUtil jwtUtil, Logger logger) {
    if (!StringUtils.hasText(context.jwt())) {
      return GameplayPresenceRole.PLAYER;
    }
    try {
      SessionClaims claims = SessionClaims.fromJwt(jwtUtil.parseToken(context.jwt()));
      String tenantId = Long.toString(context.tenantId());
      List<String> gameplayRoles = claims.scopedRoles().getOrDefault(tenantId, List.of());
      if (gameplayRoles.stream().anyMatch("god"::equalsIgnoreCase)) {
        return GameplayPresenceRole.GOD;
      }
      if (gameplayRoles.stream().anyMatch("tenantAdmin"::equalsIgnoreCase)) {
        return GameplayPresenceRole.ADMIN;
      }
      if (gameplayRoles.stream().anyMatch("moderator"::equalsIgnoreCase)) {
        return GameplayPresenceRole.MODERATOR;
      }
    } catch (IllegalArgumentException | JwtException ex) {
      logger.debug(
          "Failed to classify WHO role from JWT for session {} tenant {}",
          context.sessionId(),
          context.tenantId(),
          ex);
    }
    return GameplayPresenceRole.PLAYER;
  }
}
