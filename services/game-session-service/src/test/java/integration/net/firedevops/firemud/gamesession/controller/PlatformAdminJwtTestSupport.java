package net.firedevops.firemud.gamesession.controller;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;

final class PlatformAdminJwtTestSupport {
  private PlatformAdminJwtTestSupport() {}

  static String privilegedToken(JwtUtil jwtUtil) {
    return jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));
  }
}
