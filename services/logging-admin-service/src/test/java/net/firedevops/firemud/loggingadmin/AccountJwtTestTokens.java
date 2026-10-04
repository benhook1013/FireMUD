package net.firedevops.firemud.loggingadmin;

import java.util.HashMap;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;

/** Explicit ordinary Account JWT fixtures for Logging & Admin caller tests. */
public final class AccountJwtTestTokens {
  public static final String ACCOUNT_ID = "018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0a";

  private AccountJwtTestTokens() {}

  public static String accountToken(JwtUtil jwtUtil, Map<String, Object> claims) {
    if (claims.containsKey("accountId")) {
      throw new IllegalArgumentException("accountToken claims must not provide accountId");
    }
    Map<String, Object> accountClaims = new HashMap<>(claims);
    accountClaims.put("accountId", ACCOUNT_ID);
    return jwtUtil.generateToken(ACCOUNT_ID, accountClaims);
  }
}
