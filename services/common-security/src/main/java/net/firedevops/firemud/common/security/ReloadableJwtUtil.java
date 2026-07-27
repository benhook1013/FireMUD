package net.firedevops.firemud.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wrapper around {@link JwtUtil} that allows the signing secret to be updated at runtime. All token
 * operations delegate to the current instance.
 */
public class ReloadableJwtUtil extends JwtUtil {
  private final long expirationMillis;
  private final AtomicReference<JwtUtil> delegate;

  public ReloadableJwtUtil(String secret, long expirationMillis) {
    super(secret, expirationMillis);
    this.expirationMillis = expirationMillis;
    this.delegate = new AtomicReference<>(new JwtUtil(secret, expirationMillis));
  }

  /**
   * Update the signing secret. Existing tokens signed with the previous secret will no longer parse
   * successfully.
   */
  public synchronized void updateSecret(String secret) {
    this.delegate.set(new JwtUtil(secret, expirationMillis));
  }

  @Override
  public String generateToken(String subject, Map<String, Object> claims) {
    return delegate.get().generateToken(subject, claims);
  }

  @Override
  public String generateToken(
      String subject, long tokenExpirationMillis, Map<String, Object> claims) {
    return delegate.get().generateToken(subject, tokenExpirationMillis, claims);
  }

  @Override
  public Jws<Claims> parseToken(String token) {
    return delegate.get().parseToken(token);
  }
}
