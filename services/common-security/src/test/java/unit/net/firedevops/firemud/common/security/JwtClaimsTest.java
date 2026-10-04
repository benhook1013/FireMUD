package unit.net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jsonwebtoken.Claims;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.Test;

class JwtClaimsTest {
  private static final String ACCOUNT_ID = "1111111a-1111-4111-8111-111111111111";
  private static final String OTHER_ACCOUNT_ID = "22222222-2222-4222-8222-222222222222";

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
        jwtUtil
            .parseToken(jwtUtil.generateToken(ACCOUNT_ID, Map.of("accountId", ACCOUNT_ID)))
            .getPayload();
    assertEquals(
        ACCOUNT_ID,
        JwtClaims.requireSignedActorAccountId(validClaims, "signed token account mismatch"));

    Claims malformedSubjectClaims =
        jwtUtil
            .parseToken(jwtUtil.generateToken("42", Map.of("accountId", ACCOUNT_ID)))
            .getPayload();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JwtClaims.requireSignedActorAccountId(
                malformedSubjectClaims, "signed token account mismatch"));

    Claims malformedAccountClaims =
        jwtUtil
            .parseToken(jwtUtil.generateToken(ACCOUNT_ID, Map.of("accountId", "42")))
            .getPayload();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JwtClaims.requireSignedActorAccountId(
                malformedAccountClaims, "signed token account mismatch"));

    Claims numericAccountClaim =
        jwtUtil
            .parseToken(jwtUtil.generateToken(ACCOUNT_ID, Map.of("accountId", 42L)))
            .getPayload();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JwtClaims.requireSignedActorAccountId(
                numericAccountClaim, "signed token account mismatch"));

    Claims accountIdArrayClaim =
        jwtUtil
            .parseToken(jwtUtil.generateToken(ACCOUNT_ID, Map.of("accountId", List.of(ACCOUNT_ID))))
            .getPayload();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JwtClaims.requireSignedActorAccountId(
                accountIdArrayClaim, "signed token account mismatch"));

    for (String whitespaceAccountId : List.of(" " + ACCOUNT_ID, ACCOUNT_ID + " ")) {
      Claims whitespaceAccountClaim =
          jwtUtil
              .parseToken(
                  jwtUtil.generateToken(ACCOUNT_ID, Map.of("accountId", whitespaceAccountId)))
              .getPayload();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              JwtClaims.requireSignedActorAccountId(
                  whitespaceAccountClaim, "signed token account mismatch"));
    }

    for (String whitespaceSubject : List.of(" " + ACCOUNT_ID, ACCOUNT_ID + " ")) {
      Claims whitespaceSubjectClaim =
          jwtUtil
              .parseToken(jwtUtil.generateToken(whitespaceSubject, Map.of("accountId", ACCOUNT_ID)))
              .getPayload();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              JwtClaims.requireSignedActorAccountId(
                  whitespaceSubjectClaim, "signed token account mismatch"));
    }

    assertThrows(
        IllegalArgumentException.class,
        () -> JwtClaims.requireAccountId(UUID.fromString(ACCOUNT_ID), "accountId"));
    assertThrows(
        IllegalArgumentException.class,
        () -> JwtClaims.requireAccountId(new String[] {ACCOUNT_ID}, "accountId"));

    String nilAccountId = "00000000-0000-0000-0000-000000000000";
    Claims nilAccountClaims =
        jwtUtil
            .parseToken(jwtUtil.generateToken(nilAccountId, Map.of("accountId", nilAccountId)))
            .getPayload();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JwtClaims.requireSignedActorAccountId(
                nilAccountClaims, "signed token account mismatch"));

    String uppercaseAccountId = ACCOUNT_ID.toUpperCase(java.util.Locale.ROOT);
    Claims noncanonicalAccountClaims =
        jwtUtil
            .parseToken(
                jwtUtil.generateToken(uppercaseAccountId, Map.of("accountId", uppercaseAccountId)))
            .getPayload();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JwtClaims.requireSignedActorAccountId(
                noncanonicalAccountClaims, "signed token account mismatch"));

    Claims mismatchedClaims =
        jwtUtil
            .parseToken(jwtUtil.generateToken(ACCOUNT_ID, Map.of("accountId", OTHER_ACCOUNT_ID)))
            .getPayload();
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
                    ACCOUNT_ID,
                    Map.of(
                        "accountId",
                        ACCOUNT_ID,
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
    assertEquals(ACCOUNT_ID, routingClaims.accountId());
    assertEquals(7L, routingClaims.tenantId());
    assertEquals("demo", routingClaims.worldSlug());
    assertEquals("production", routingClaims.realmSlug());
    assertEquals(9L, routingClaims.gameInstanceId());
    assertEquals(17L, routingClaims.pointerVersion());

    Claims blankWorldClaims =
        jwtUtil
            .parseToken(
                jwtUtil.generateToken(
                    ACCOUNT_ID,
                    Map.of(
                        "accountId",
                        ACCOUNT_ID,
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
                    ACCOUNT_ID,
                    Map.of(
                        "accountId",
                        ACCOUNT_ID,
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
}
