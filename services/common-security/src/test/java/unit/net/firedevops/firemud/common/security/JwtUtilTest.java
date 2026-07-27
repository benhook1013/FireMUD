package net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JwtUtilTest {
  @Test
  void generateAndParseTokenReturnsClaims() {
    JwtUtil util = new JwtUtil("mysecretkey123456789012345678901", 3600000L);
    String token = util.generateToken("demo", Map.of("role", "admin"));

    Jws<Claims> parsed = util.parseToken(token);
    Claims payload = parsed.getPayload();

    assertEquals("demo", payload.getSubject());
    assertEquals("admin", payload.get("role"));
  }

  @Test
  void generateTokenWithExplicitExpirationUsesSuppliedLifetime() {
    long defaultExpirationMillis = 3_600_000L;
    long requestedExpirationMillis = 4_000L;
    JwtUtil util = new JwtUtil("mysecretkey123456789012345678901", defaultExpirationMillis);
    Map<String, Object> suppliedClaims = Map.of("role", "admin", "tenantId", "7");

    long beforeGeneration = System.currentTimeMillis();
    String token = util.generateToken("demo", requestedExpirationMillis, suppliedClaims);
    long afterGeneration = System.currentTimeMillis();

    Claims payload = util.parseToken(token).getPayload();
    Date expiration = payload.getExpiration();
    long clockPrecisionToleranceMillis = 1_500L;

    assertEquals("demo", payload.getSubject());
    assertEquals("admin", payload.get("role"));
    assertEquals("7", payload.get("tenantId"));
    assertTrue(
        expiration.getTime()
            >= beforeGeneration + requestedExpirationMillis - clockPrecisionToleranceMillis,
        () -> "expiration was earlier than the explicit lifetime: " + expiration);
    assertTrue(
        expiration.getTime()
            <= afterGeneration + requestedExpirationMillis + clockPrecisionToleranceMillis,
        () -> "expiration was later than the explicit lifetime: " + expiration);
  }
}
