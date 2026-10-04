package net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionClaimsTest {
  private static final String ACCOUNT_ID = "11111111-1111-4111-8111-111111111111";

  @Test
  void privilegedRoleCheckStillRecognizesGlobalControlPlaneRoles() {
    SessionClaims claims =
        new SessionClaims(ACCOUNT_ID, List.of("platformAdmin"), Map.of(), false, null, null);

    assertTrue(claims.hasPrivilegedRole());
  }

  @Test
  void privilegedRoleCheckStillRecognizesTenantScopedControlPlaneRoles() {
    SessionClaims claims =
        new SessionClaims(
            ACCOUNT_ID,
            List.of(),
            Map.of("7", List.of("tenantAdmin", "moderator")),
            false,
            null,
            null);

    assertTrue(claims.hasPrivilegedRole());
  }

  @Test
  void fromJwtPreservesCanonicalAccountSubjectAndClaim() {
    JwtUtil jwtUtil = new JwtUtil("mysecretkey123456789012345678901", 30_000L);
    SessionClaims claims =
        SessionClaims.fromJwt(
            jwtUtil.parseToken(jwtUtil.generateToken(ACCOUNT_ID, Map.of("accountId", ACCOUNT_ID))));

    assertEquals(ACCOUNT_ID, claims.accountId());
  }

  @Test
  void fromJwtRejectsNonUuidOrMismatchedAccountIdentity() {
    JwtUtil jwtUtil = new JwtUtil("mysecretkey123456789012345678901", 30_000L);
    String otherAccountId = "22222222-2222-4222-8222-222222222222";

    assertThrows(
        IllegalArgumentException.class,
        () ->
            SessionClaims.fromJwt(
                jwtUtil.parseToken(jwtUtil.generateToken("42", Map.of("accountId", "42")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SessionClaims.fromJwt(
                jwtUtil.parseToken(
                    jwtUtil.generateToken(ACCOUNT_ID, Map.of("accountId", otherAccountId)))));
    assertThrows(
        IllegalArgumentException.class,
        () -> SessionClaims.fromJwt(jwtUtil.parseToken(jwtUtil.generateToken("user", Map.of()))));
  }

  @Test
  void rejectsNumericAccountIdentityWhenClaimsAreConstructedDirectly() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SessionClaims("42", List.of(), Map.of(), false, null, null));
  }

  @Test
  void fromJwtAllowsInternalWorkloadWithoutAccountIdentity() {
    JwtUtil jwtUtil = new JwtUtil("mysecretkey123456789012345678901", 30_000L);
    SessionClaims claims =
        SessionClaims.fromJwt(
            jwtUtil.parseToken(
                jwtUtil.generateToken(
                    "world-management-service", Map.of("internalService", true))));

    assertNull(claims.accountId());
    assertTrue(claims.internalService());
  }

  @Test
  void internalWorkloadFlagDoesNotDiscardMalformedOrMismatchedAccountIdentity() {
    JwtUtil jwtUtil = new JwtUtil("mysecretkey123456789012345678901", 30_000L);
    for (Object accountId : List.of("", " ", "null", "NULL", 42L, List.of(), ACCOUNT_ID)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              SessionClaims.fromJwt(
                  jwtUtil.parseToken(
                      jwtUtil.generateToken(
                          "world-management-service",
                          Map.of("internalService", true, "accountId", accountId)))));
    }
  }

  @Test
  void rejectsPresentBlankAccountIdentityWhenClaimsAreConstructedDirectly() {
    for (String accountId : List.of("", " ", "null")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new SessionClaims(accountId, List.of(), Map.of(), true, null, null));
    }
  }
}
