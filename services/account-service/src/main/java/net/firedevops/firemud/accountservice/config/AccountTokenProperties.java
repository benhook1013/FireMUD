package net.firedevops.firemud.accountservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "firemud.account.tokens")
public class AccountTokenProperties {
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
