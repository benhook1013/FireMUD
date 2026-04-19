package net.firedevops.firemud.common.security;

public class JwtAuthProperties {
  private String jwtSecret;
  private String jwtSecretPath;
  private long jwtExpirationMs = 3600000L;

  public String getJwtSecret() {
    return jwtSecret;
  }

  public void setJwtSecret(String jwtSecret) {
    this.jwtSecret = jwtSecret;
  }

  public String getJwtSecretPath() {
    return jwtSecretPath;
  }

  public void setJwtSecretPath(String jwtSecretPath) {
    this.jwtSecretPath = jwtSecretPath;
  }

  public long getJwtExpirationMs() {
    return jwtExpirationMs;
  }

  public void setJwtExpirationMs(long jwtExpirationMs) {
    this.jwtExpirationMs = jwtExpirationMs;
  }
}
