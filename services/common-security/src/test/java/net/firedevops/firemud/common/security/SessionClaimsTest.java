package net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SessionClaimsTest {
  private static final String ACCOUNT_ID = "018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0a";

  @Test
  void fromJwtPreservesCanonicalSignedAccountIdentityAndRoles() {
    Claims payload = mock(Claims.class);
    when(payload.get("accountId")).thenReturn(ACCOUNT_ID);
    when(payload.get("sub")).thenReturn(ACCOUNT_ID);
    when(payload.get("globalRoles")).thenReturn(List.of("platformAdmin"));
    when(payload.get("scopedRoles")).thenReturn(Map.of("7", List.of("moderator")));

    SessionClaims claims = SessionClaims.fromJwt(jwt(payload));

    assertEquals(ACCOUNT_ID, claims.accountId());
    assertEquals(List.of("platformAdmin"), claims.globalRoles());
    assertEquals(Map.of("7", List.of("moderator")), claims.scopedRoles());
    assertFalse(claims.internalService());
  }

  @ParameterizedTest
  @MethodSource("malformedAccountIdentityClaims")
  void fromJwtRejectsMalformedRawAccountIdentity(String claimName, Object value) {
    Claims payload = mock(Claims.class);
    when(payload.get("accountId")).thenReturn(ACCOUNT_ID);
    when(payload.get("sub")).thenReturn(ACCOUNT_ID);
    when(payload.get(claimName)).thenReturn(value);

    assertThrows(IllegalArgumentException.class, () -> SessionClaims.fromJwt(jwt(payload)));
  }

  private static Stream<Arguments> malformedAccountIdentityClaims() {
    return Stream.<Object>of(
            null,
            "",
            "42",
            42L,
            UUID.fromString(ACCOUNT_ID),
            "00000000-0000-0000-0000-000000000000",
            ACCOUNT_ID.toUpperCase(java.util.Locale.ROOT),
            "1-1-1-1-1",
            " " + ACCOUNT_ID,
            ACCOUNT_ID + " ",
            List.of(ACCOUNT_ID),
            new String[] {ACCOUNT_ID},
            Map.of("value", ACCOUNT_ID))
        .flatMap(value -> Stream.of(Arguments.of("accountId", value), Arguments.of("sub", value)));
  }

  @Test
  void fromJwtRejectsDifferentCanonicalSignedSubject() {
    Claims payload = mock(Claims.class);
    when(payload.get("accountId")).thenReturn(ACCOUNT_ID);
    when(payload.get("sub")).thenReturn("018f8f0a-2b7c-7a24-9c15-6a9b8c7d6e5f");

    assertThrows(IllegalArgumentException.class, () -> SessionClaims.fromJwt(jwt(payload)));
  }

  @Test
  void fromJwtPreservesInternalServiceWithoutAccountIdentity() {
    Claims payload = mock(Claims.class);
    when(payload.get("internalService", Boolean.class)).thenReturn(true);
    when(payload.get("sub")).thenReturn("service:game-logic-service");
    when(payload.get("serviceName")).thenReturn("game-logic-service");
    when(payload.get("serviceInstanceId")).thenReturn("game-logic-service-1");

    SessionClaims claims = SessionClaims.fromJwt(jwt(payload));

    assertEquals("", claims.accountId());
    assertTrue(claims.internalService());
    assertEquals("game-logic-service", claims.serviceName());
    assertEquals("game-logic-service-1", claims.serviceInstanceId());
  }

  @Test
  void internalServiceFlagDoesNotBypassPresentAccountIdentityValidation() {
    Claims payload = mock(Claims.class);
    when(payload.get("internalService", Boolean.class)).thenReturn(true);
    when(payload.containsKey("accountId")).thenReturn(true);
    when(payload.get("accountId")).thenReturn(List.of(ACCOUNT_ID));
    when(payload.get("sub")).thenReturn("service:game-logic-service");

    assertThrows(IllegalArgumentException.class, () -> SessionClaims.fromJwt(jwt(payload)));
  }

  private static Jws<Claims> jwt(Claims payload) {
    @SuppressWarnings("unchecked")
    Jws<Claims> jwt = mock(Jws.class);
    when(jwt.getPayload()).thenReturn(payload);
    return jwt;
  }

  @Test
  void hasPrivilegedRoleRecognizesGlobalControlPlaneRole() {
    SessionClaims claims =
        new SessionClaims("11", List.of("platformAdmin"), Map.of(), false, null, null);

    assertTrue(claims.hasPrivilegedRole());
  }

  @Test
  void hasPrivilegedRoleRecognizesTenantScopedControlPlaneRoles() {
    SessionClaims claims =
        new SessionClaims(
            "11", List.of(), Map.of("7", List.of("tenantAdmin", "moderator")), false, null, null);

    assertTrue(claims.hasPrivilegedRole());
  }

  @Test
  void hasPrivilegedRoleRejectsNonControlPlaneRoles() {
    SessionClaims claims =
        new SessionClaims("11", List.of(), Map.of("7", List.of("player")), false, null, null);

    assertFalse(claims.hasPrivilegedRole());
  }
}
