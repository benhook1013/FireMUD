package net.firedevops.firemud.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.Objects;

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

  /**
   * Signs with an owner-selected absolute expiration at JWT NumericDate precision. The issuance
   * timestamp is sampled once; expiration must be whole-second precision and later than issuance.
   */
  public String generateToken(String subject, Instant expiresAt, Map<String, Object> claims) {
    Objects.requireNonNull(subject, "Token subject required");
    Objects.requireNonNull(expiresAt, "Absolute token expiration required");
    Objects.requireNonNull(claims, "Token claims required");
    if (!expiresAt.equals(expiresAt.truncatedTo(ChronoUnit.SECONDS))) {
      throw new IllegalArgumentException("Absolute JWT expiration must use whole-second precision");
    }

    Date issuedAt = new Date(System.currentTimeMillis());
    Date expiration = Date.from(expiresAt);
    if (!expiration.after(issuedAt)) {
      throw new IllegalArgumentException("Absolute JWT expiration must be after issuance");
    }
    return Jwts.builder()
        .subject(subject)
        .claims(claims)
        .issuedAt(issuedAt)
        .expiration(expiration)
        .signWith(Keys.hmacShaKeyFor(key))
        .compact();
  }

  public Jws<Claims> parseToken(String token) {
    return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(key)).build().parseSignedClaims(token);
  }
}
