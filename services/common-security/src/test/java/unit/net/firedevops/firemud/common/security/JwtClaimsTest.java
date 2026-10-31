package unit.net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jsonwebtoken.Claims;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.Test;

class JwtClaimsTest {
  @Test
  void requireCanonicalAccountUuidPreservesExactCanonicalText() {
    String accountId = "4cae05e8-7a6b-4b14-9d44-665e3eec450b";
    assertEquals(accountId, JwtClaims.requireCanonicalAccountUuid(accountId, "accountId"));
  }

  @Test
  void requireCanonicalAccountUuidRejectsNoncanonicalAndNonTextCarriers() {
    for (Object accountId :
        List.of(
            "11",
            "not-a-uuid",
            "4CAE05E8-7A6B-4B14-9D44-665E3EEC450B",
            "00000000-0000-0000-0000-000000000000",
            " 4cae05e8-7a6b-4b14-9d44-665e3eec450b",
            "4cae05e8-7a6b-4b14-9d44-665e3eec450b ",
            "",
            11L,
            java.util.UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b"))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> JwtClaims.requireCanonicalAccountUuid(accountId, "accountId"));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> JwtClaims.requireCanonicalAccountUuid(null, "accountId"));
  }

  @Test
  void uuidReadersPreserveTheirNoncanonicalFailureCauseContracts() {
    String canonical = "4cae05e8-7a6b-4b14-9d44-665e3eec450b";
    for (String value :
        List.of(
            canonical.toUpperCase(java.util.Locale.ROOT), "00000000-0000-0000-0000-000000000000")) {
      IllegalArgumentException publicFailure =
          assertThrows(
              IllegalArgumentException.class,
              () -> JwtClaims.requireCanonicalAccountUuid(value, "accountId"));
      assertEquals("Malformed claim: accountId", publicFailure.getMessage());
      assertEquals("Invalid claim: accountId", publicFailure.getCause().getMessage());
      assertNull(publicFailure.getCause().getCause());

      for (String field : List.of("sub", "accountId")) {
        Claims claims =
            io.jsonwebtoken.Jwts.claims()
                .add("sub", field.equals("sub") ? value : canonical)
                .add("accountId", field.equals("accountId") ? value : canonical)
                .build();
        IllegalArgumentException scopeFailure =
            assertThrows(
                IllegalArgumentException.class,
                () -> JwtClaims.requireSignedGameplayRoutingClaims(claims, "mismatch"));
        assertEquals("Malformed claim: " + field, scopeFailure.getMessage());
        assertEquals("Noncanonical Account UUID", scopeFailure.getCause().getMessage());
        assertNull(scopeFailure.getCause().getCause());
      }
    }
  }

  @Test
  void uuidReadersPreserveMalformedParsingCauseAndDistinctBlankPrechecks() {
    IllegalArgumentException publicFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> JwtClaims.requireCanonicalAccountUuid("not-a-uuid", "accountId"));
    assertEquals("Malformed claim: accountId", publicFailure.getMessage());
    assertEquals("Invalid UUID string: not-a-uuid", publicFailure.getCause().getMessage());
    assertNull(publicFailure.getCause().getCause());
    Claims malformed = io.jsonwebtoken.Jwts.claims().add("sub", "not-a-uuid").build();
    IllegalArgumentException scopeFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> JwtClaims.requireSignedGameplayRoutingClaims(malformed, "mismatch"));
    assertEquals("Malformed claim: sub", scopeFailure.getMessage());
    assertEquals("Invalid UUID string: not-a-uuid", scopeFailure.getCause().getMessage());
    assertNull(scopeFailure.getCause().getCause());

    IllegalArgumentException publicBlank =
        assertThrows(
            IllegalArgumentException.class,
            () -> JwtClaims.requireCanonicalAccountUuid(" ", "accountId"));
    assertEquals("Malformed claim: accountId", publicBlank.getMessage());
    assertNull(publicBlank.getCause());
    Claims blank = org.mockito.Mockito.mock(Claims.class);
    org.mockito.Mockito.when(blank.get("sub")).thenReturn(" ");
    IllegalArgumentException scopeBlank =
        assertThrows(
            IllegalArgumentException.class,
            () -> JwtClaims.requireSignedGameplayRoutingClaims(blank, "mismatch"));
    assertEquals("Malformed claim: sub", scopeBlank.getMessage());
    assertEquals("Invalid UUID string:  ", scopeBlank.getCause().getMessage());
    assertNull(scopeBlank.getCause().getCause());
  }

  @Test
  void uuidReadersRejectNonTextBeforeParsingWithoutNestedCause() {
    IllegalArgumentException publicFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> JwtClaims.requireCanonicalAccountUuid(11L, "accountId"));
    assertEquals("Malformed claim: accountId", publicFailure.getMessage());
    assertNull(publicFailure.getCause());
    Claims numeric = org.mockito.Mockito.mock(Claims.class);
    org.mockito.Mockito.when(numeric.get("sub")).thenReturn(11L);
    IllegalArgumentException scopeFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> JwtClaims.requireSignedGameplayRoutingClaims(numeric, "mismatch"));
    assertEquals("Malformed claim: sub", scopeFailure.getMessage());
    assertNull(scopeFailure.getCause());
  }

  @Test
  void requireLongParsesNumericTextAndRejectsInvalidValues() {
    assertEquals(7L, JwtClaims.requireLong("7", "accountId", false));
    assertEquals(7L, JwtClaims.requireLong(7L, "accountId", false));

    assertThrows(
        IllegalArgumentException.class, () -> JwtClaims.requireLong("abc", "tenantId", false));
    assertThrows(
        IllegalArgumentException.class, () -> JwtClaims.requireLong("0", "tenantId", false));
    assertThrows(
        IllegalArgumentException.class, () -> JwtClaims.requireLong(null, "tenantId", false));
  }

  @Test
  void requireClaimRejectsMissingOrBlankClaimsAndReadsFirstIterableValue() {
    JwtUtil jwtUtil = new JwtUtil("mysecretkey123456789012345678901", 30_000L);
    Claims claims =
        jwtUtil
            .parseToken(jwtUtil.generateToken("11", Map.of("aud", List.of("", "gameplay-connect"))))
            .getPayload();

    assertEquals("gameplay-connect", JwtClaims.requireClaim(claims, "aud"));
    assertThrows(IllegalArgumentException.class, () -> JwtClaims.requireClaim(claims, "missing"));

    Claims claimsBlank =
        jwtUtil.parseToken(jwtUtil.generateToken("11", Map.of("blank", " "))).getPayload();
    assertThrows(
        IllegalArgumentException.class, () -> JwtClaims.requireClaim(claimsBlank, "blank"));
  }

  @Test
  void requireSignedActorAccountIdRejectsMalformedOrMismatchedAccountClaims() {
    JwtUtil jwtUtil = new JwtUtil("mysecretkey123456789012345678901", 30_000L);

    Claims validClaims =
        jwtUtil.parseToken(jwtUtil.generateToken("11", Map.of("accountId", "11"))).getPayload();
    assertEquals(
        11L, JwtClaims.requireSignedActorAccountId(validClaims, "signed token account mismatch"));

    Claims malformedSubjectClaims =
        jwtUtil
            .parseToken(jwtUtil.generateToken("not-a-number", Map.of("accountId", "11")))
            .getPayload();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JwtClaims.requireSignedActorAccountId(
                malformedSubjectClaims, "signed token account mismatch"));

    Claims malformedAccountClaims =
        jwtUtil
            .parseToken(jwtUtil.generateToken("11", Map.of("accountId", "not-a-number")))
            .getPayload();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JwtClaims.requireSignedActorAccountId(
                malformedAccountClaims, "signed token account mismatch"));

    Claims mismatchedClaims =
        jwtUtil.parseToken(jwtUtil.generateToken("11", Map.of("accountId", "12"))).getPayload();
    IllegalArgumentException mismatch =
        assertThrows(
            IllegalArgumentException.class,
            () -> JwtClaims.requireSignedActorAccountId(mismatchedClaims, "custom mismatch"));
    assertEquals("custom mismatch", mismatch.getMessage());
  }

  @Test
  void requireSignedGameplayRoutingClaimsRejectsMalformedOrIncompleteRoutingBundleClaims() {
    JwtUtil jwtUtil = new JwtUtil("mysecretkey123456789012345678901", 30_000L);

    Claims validClaims =
        jwtUtil
            .parseToken(
                jwtUtil.generateToken(
                    "10000000-0000-4000-8000-00000000000a",
                    Map.of(
                        "accountId",
                        "10000000-0000-4000-8000-00000000000a",
                        "tenantId",
                        "7",
                        "worldSlug",
                        "demo",
                        "realmSlug",
                        "production",
                        "gameInstanceId",
                        "9",
                        "pointerVersion",
                        "17")))
            .getPayload();
    JwtClaims.SignedGameplayRoutingClaims routingClaims =
        JwtClaims.requireSignedGameplayRoutingClaims(validClaims, "signed gameplay mismatch");
    assertEquals("10000000-0000-4000-8000-00000000000a", routingClaims.accountId());
    assertEquals(7L, routingClaims.tenantId());
    assertEquals("demo", routingClaims.worldSlug());
    assertEquals("production", routingClaims.realmSlug());
    assertEquals(9L, routingClaims.gameInstanceId());
    assertEquals(17L, routingClaims.pointerVersion());

    Claims blankWorldClaims =
        jwtUtil
            .parseToken(
                jwtUtil.generateToken(
                    "10000000-0000-4000-8000-00000000000a",
                    Map.of(
                        "accountId",
                        "10000000-0000-4000-8000-00000000000a",
                        "tenantId",
                        "7",
                        "worldSlug",
                        " ",
                        "realmSlug",
                        "production",
                        "gameInstanceId",
                        "9",
                        "pointerVersion",
                        "17")))
            .getPayload();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JwtClaims.requireSignedGameplayRoutingClaims(
                blankWorldClaims, "signed gameplay mismatch"));

    Claims zeroPointerClaims =
        jwtUtil
            .parseToken(
                jwtUtil.generateToken(
                    "10000000-0000-4000-8000-00000000000a",
                    Map.of(
                        "accountId",
                        "10000000-0000-4000-8000-00000000000a",
                        "tenantId",
                        "7",
                        "worldSlug",
                        "demo",
                        "realmSlug",
                        "production",
                        "gameInstanceId",
                        "9",
                        "pointerVersion",
                        "0")))
            .getPayload();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JwtClaims.requireSignedGameplayRoutingClaims(
                zeroPointerClaims, "signed gameplay mismatch"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "11",
        "",
        "not-a-uuid",
        "00000000-0000-0000-0000-000000000000",
        "10000000-0000-4000-8000-00000000000A",
        " 10000000-0000-4000-8000-00000000000a "
      })
  void routingScopeRejectsNoncanonicalAccountIdentity(String value) {
    var claims = io.jsonwebtoken.Jwts.claims().add("sub", value).add("accountId", value).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> JwtClaims.requireSignedGameplayRoutingClaims(claims, "mismatch"));
  }

  @Test
  void routingScopeRejectsNumericObjectsAndDifferentUuidSubjects() {
    Claims numeric = org.mockito.Mockito.mock(Claims.class);
    org.mockito.Mockito.when(numeric.get("sub")).thenReturn(11L);
    org.mockito.Mockito.when(numeric.get("accountId")).thenReturn(11L);
    for (var claims :
        java.util.List.of(
            numeric,
            io.jsonwebtoken.Jwts.claims()
                .add("sub", "10000000-0000-4000-8000-000000000001")
                .add("accountId", "10000000-0000-4000-8000-000000000002")
                .build())) {
      assertThrows(
          IllegalArgumentException.class,
          () -> JwtClaims.requireSignedGameplayRoutingClaims(claims, "mismatch"));
    }
  }
}
