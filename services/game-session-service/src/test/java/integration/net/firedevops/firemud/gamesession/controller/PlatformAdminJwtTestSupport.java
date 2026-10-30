package net.firedevops.firemud.gamesession.controller;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;

final class PlatformAdminJwtTestSupport {
  static final String TEST_ACCOUNT_ID = "4f5c31f3-fac5-4cad-b8f2-bb53e5c75f01";

  private PlatformAdminJwtTestSupport() {}

  static String privilegedToken(JwtUtil jwtUtil) {
    return jwtUtil.generateToken(
        TEST_ACCOUNT_ID,
        Map.of("accountId", TEST_ACCOUNT_ID, "globalRoles", List.of("platformAdmin")));
  }
}
