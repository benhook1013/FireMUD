package net.firedevops.firemud.accountservice.config;

import net.firedevops.firemud.common.security.JwtAuthProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Primary;

@Primary
@ConfigurationProperties(prefix = "firemud.auth")
public class AccountAuthProperties extends JwtAuthProperties {
  private long sessionExpirationMs = 3600000L;
  private long playerBootstrapExpirationMs = 300000L;
  private long connectScopeExpirationMs = 120000L;
  private long connectTokenExpirationMs = 30000L;

  public long getSessionExpirationMs() {
    return sessionExpirationMs;
  }

  public void setSessionExpirationMs(long sessionExpirationMs) {
    this.sessionExpirationMs = sessionExpirationMs;
  }

  public long getPlayerBootstrapExpirationMs() {
    return playerBootstrapExpirationMs;
  }

  public void setPlayerBootstrapExpirationMs(long playerBootstrapExpirationMs) {
    this.playerBootstrapExpirationMs = playerBootstrapExpirationMs;
  }

  public long getConnectScopeExpirationMs() {
    return connectScopeExpirationMs;
  }

  public void setConnectScopeExpirationMs(long connectScopeExpirationMs) {
    this.connectScopeExpirationMs = connectScopeExpirationMs;
  }

  public long getConnectTokenExpirationMs() {
    return connectTokenExpirationMs;
  }

  public void setConnectTokenExpirationMs(long connectTokenExpirationMs) {
    this.connectTokenExpirationMs = connectTokenExpirationMs;
  }
}
