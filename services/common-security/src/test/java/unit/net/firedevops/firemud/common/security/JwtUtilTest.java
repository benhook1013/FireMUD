package net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
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
    Map<String, Object> suppliedClaims =
        Map.of("role", "admin", "tenantId", "7", "sub", "claims-subject");

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

  @Test
  void generateTokenWithAbsoluteExpirationSignsTheExactExpiryAndOwnsRegisteredClaims() {
    JwtUtil util = new JwtUtil("mysecretkey123456789012345678901", 3600000L);
    Instant expiresAt = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.SECONDS);
    Map<String, Object> suppliedClaims = new HashMap<>();
    suppliedClaims.put("role", "admin");
    suppliedClaims.put("sub", "claims-subject");
    suppliedClaims.put("iat", 1L);
    suppliedClaims.put("exp", 2L);

    long beforeGeneration = System.currentTimeMillis();
    String token = util.generateToken("demo", expiresAt, suppliedClaims);
    long afterGeneration = System.currentTimeMillis();

    Claims payload = util.parseToken(token).getPayload();
    assertEquals("demo", payload.getSubject());
    assertEquals("admin", payload.get("role"));
    assertEquals(expiresAt, payload.getExpiration().toInstant());
    assertTrue(
        !payload
            .getIssuedAt()
            .toInstant()
            .isBefore(Instant.ofEpochMilli(beforeGeneration).truncatedTo(ChronoUnit.SECONDS)));
    assertTrue(payload.getIssuedAt().getTime() <= afterGeneration);
  }

  @Test
  void absoluteExpirationRejectsExpiredAndSubsecondValues() {
    JwtUtil util = new JwtUtil("mysecretkey123456789012345678901", 3600000L);

    assertThrows(
        IllegalArgumentException.class,
        () -> util.generateToken("demo", Instant.EPOCH, Map.of("role", "admin")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            util.generateToken(
                "demo", Instant.parse("2030-01-01T00:00:30.000000001Z"), Map.of("role", "admin")));
  }

  @Test
  void reloadableJwtUtilDelegatesAbsoluteExpiryToTheCurrentSigner() {
    ReloadableJwtUtil util = new ReloadableJwtUtil("mysecretkey123456789012345678901", 3600000L);
    util.updateSecret("rotatedsecretkey12345678901234567890");
    Instant expiresAt = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.SECONDS);

    String token = util.generateToken("demo", expiresAt, Map.of("role", "admin"));

    Claims payload = util.parseToken(token).getPayload();
    assertEquals("demo", payload.getSubject());
    assertEquals("admin", payload.get("role"));
    assertEquals(expiresAt, payload.getExpiration().toInstant());
  }
}
