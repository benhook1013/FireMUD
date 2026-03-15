package net.firedevops.firemud.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
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
}
