package net.firedevops.firemud.gamesession.controller;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;

final class PlatformAdminJwtTestSupport {
  private PlatformAdminJwtTestSupport() {}

  static String privilegedToken(JwtUtil jwtUtil) {
    String accountId = "018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0a";
    return jwtUtil.generateToken(
        accountId, Map.of("accountId", accountId, "globalRoles", List.of("platformAdmin")));
  }
}
