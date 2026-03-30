package net.firedevops.firemud.tcpproxy.telnet;

import org.springframework.util.StringUtils;

/**
 * Holds hidden session bootstrap metadata for the Telnet connection so it can be forwarded to the
 * gateway and Game Session Service.
 */
final class TelnetSessionContext {
  private volatile String gameInstanceId;
  private volatile String tenantId;

  boolean isReady() {
    return StringUtils.hasText(gameInstanceId) && StringUtils.hasText(tenantId);
  }

  String gameInstanceId() {
    return gameInstanceId;
  }

  String tenantId() {
    return tenantId;
  }

  void bootstrap(String gameInstanceId, String tenantId) {
    if (!StringUtils.hasText(gameInstanceId) || !StringUtils.hasText(tenantId)) {
      return;
    }
    this.gameInstanceId = gameInstanceId;
    this.tenantId = tenantId;
  }
}
