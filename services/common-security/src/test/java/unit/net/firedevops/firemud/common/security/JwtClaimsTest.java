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
                    "11",
                    Map.of(
                        "accountId",
                        "11",
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
    assertEquals(11L, routingClaims.accountId());
    assertEquals(7L, routingClaims.tenantId());
    assertEquals("demo", routingClaims.worldSlug());
    assertEquals("production", routingClaims.realmSlug());
    assertEquals(9L, routingClaims.gameInstanceId());
    assertEquals(17L, routingClaims.pointerVersion());

    Claims blankWorldClaims =
        jwtUtil
            .parseToken(
                jwtUtil.generateToken(
                    "11",
                    Map.of(
                        "accountId",
                        "11",
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
                    "11",
                    Map.of(
                        "accountId",
                        "11",
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
