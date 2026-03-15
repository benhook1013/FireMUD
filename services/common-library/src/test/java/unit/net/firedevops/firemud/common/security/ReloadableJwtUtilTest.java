package net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jsonwebtoken.JwtException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReloadableJwtUtilTest {
  @Test
  void updateSecretReplacesSigningKey() {
    ReloadableJwtUtil util = new ReloadableJwtUtil("secret1secret1secret1secret1abcd", 3600000L);
    String token1 = util.generateToken("demo", Map.of());

    util.updateSecret("secret2secret2secret2secret2abcd");

    assertThrows(JwtException.class, () -> util.parseToken(token1));

    String token2 = util.generateToken("demo", Map.of());
    assertEquals("demo", util.parseToken(token2).getPayload().getSubject());
  }
}
