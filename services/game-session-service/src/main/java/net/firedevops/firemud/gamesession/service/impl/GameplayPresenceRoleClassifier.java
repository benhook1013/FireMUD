package net.firedevops.firemud.gamesession.service.impl;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;
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
      Claims claims = jwtUtil.parseToken(context.jwt()).getPayload();
      if (hasElevatedRole(claims.get("globalRoles", List.class))) {
        return GameplayPresenceRole.GOD;
      }
      Object scopedRoles = claims.get("scopedRoles");
      if (scopedRoles instanceof Map<?, ?> scopedMap) {
        Object tenantRoles = scopedMap.get(Long.toString(context.tenantId()));
        if (hasElevatedRole(tenantRoles)) {
          return GameplayPresenceRole.GOD;
        }
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

  private static boolean hasElevatedRole(Object rolesRaw) {
    if (!(rolesRaw instanceof List<?> roles)) {
      return false;
    }
    for (Object role : roles) {
      String normalized = String.valueOf(role).trim().toLowerCase(Locale.ROOT);
      if (normalized.equals("platformadmin")
          || normalized.equals("tenantadmin")
          || normalized.equals("god")) {
        return true;
      }
    }
    return false;
  }
}
