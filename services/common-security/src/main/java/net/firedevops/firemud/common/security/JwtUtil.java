package net.firedevops.firemud.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/** Helper for generating and verifying JWT tokens. */
public class JwtUtil {
  private final byte[] key;
  private final long expirationMillis;

  public JwtUtil(String secretKey, long expirationMillis) {
    this.key = secretKey.getBytes(StandardCharsets.UTF_8);
    this.expirationMillis = expirationMillis;
  }

  public String generateToken(String subject, Map<String, Object> claims) {
    return generateToken(subject, expirationMillis, claims);
  }

  public String generateToken(
      String subject, long tokenExpirationMillis, Map<String, Object> claims) {
    long now = System.currentTimeMillis();
    return Jwts.builder()
        .subject(subject)
        .claims(claims)
        .issuedAt(new Date(now))
        .expiration(new Date(now + tokenExpirationMillis))
        .signWith(Keys.hmacShaKeyFor(key))
        .compact();
  }

  public Jws<Claims> parseToken(String token) {
    return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(key)).build().parseSignedClaims(token);
  }
}
