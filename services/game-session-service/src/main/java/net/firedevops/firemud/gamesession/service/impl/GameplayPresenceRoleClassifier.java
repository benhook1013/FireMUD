package net.firedevops.firemud.gamesession.service.impl;

import io.jsonwebtoken.JwtException;
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
      if (claims.hasGameplayElevatedRole(Long.toString(context.tenantId()))) {
        return GameplayPresenceRole.GOD;
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
