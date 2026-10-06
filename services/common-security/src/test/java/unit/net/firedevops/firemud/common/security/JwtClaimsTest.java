package unit.net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jsonwebtoken.Claims;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.Test;

class JwtClaimsTest {
  private static final String ACCOUNT_UUID = "4cae05e8-7a6b-4b14-9d44-665e3eec450b";

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
                    ACCOUNT_UUID,
                    Map.of(
                        "accountId",
                        ACCOUNT_UUID,
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
    assertEquals(ACCOUNT_UUID, routingClaims.accountId());
    assertEquals(7L, routingClaims.tenantId());
    assertEquals("demo", routingClaims.worldSlug());
    assertEquals("production", routingClaims.realmSlug());
    assertEquals(9L, routingClaims.gameInstanceId());
    assertEquals(17L, routingClaims.pointerVersion());

    Claims blankWorldClaims =
        jwtUtil
            .parseToken(
                jwtUtil.generateToken(
                    ACCOUNT_UUID,
                    Map.of(
                        "accountId",
                        ACCOUNT_UUID,
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
                    ACCOUNT_UUID,
                    Map.of(
                        "accountId",
                        ACCOUNT_UUID,
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

  @Test
  void requireSignedGameplayAccountUuidRejectsNoncanonicalOrIncompatibleCarriers() {
    JwtUtil jwtUtil = new JwtUtil("mysecretkey123456789012345678901", 30_000L);

    for (String accountId :
        List.of(
            "11",
            "not-a-uuid",
            "4CAE05E8-7A6B-4B14-9D44-665E3EEC450B",
            "00000000-0000-0000-0000-000000000000",
            " " + ACCOUNT_UUID)) {
      Claims claims =
          jwtUtil
              .parseToken(jwtUtil.generateToken(accountId, Map.of("accountId", accountId)))
              .getPayload();
      assertThrows(
          IllegalArgumentException.class,
          () -> JwtClaims.requireSignedGameplayAccountUuid(claims, "account subject mismatch"));
    }

    Claims mismatchedClaims =
        jwtUtil
            .parseToken(
                jwtUtil.generateToken(
                    ACCOUNT_UUID, Map.of("accountId", "4d3e9d15-a02e-41db-8648-0d2c68d0f0a3")))
            .getPayload();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JwtClaims.requireSignedGameplayAccountUuid(
                mismatchedClaims, "account subject mismatch"));

    Claims numericClaim =
        jwtUtil
            .parseToken(jwtUtil.generateToken(ACCOUNT_UUID, Map.of("accountId", 42L)))
            .getPayload();
    assertThrows(
        IllegalArgumentException.class,
        () -> JwtClaims.requireSignedGameplayAccountUuid(numericClaim, "account subject mismatch"));
  }
}
