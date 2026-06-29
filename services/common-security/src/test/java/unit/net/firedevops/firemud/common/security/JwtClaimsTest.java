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
}
