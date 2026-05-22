package net.firedevops.firemud.tcpproxy.telnet;

import org.springframework.util.StringUtils;

/**
 * Holds hidden session bootstrap metadata for the Telnet connection so it can be forwarded to the
 * gateway and Game Session Service.
 */
final class TelnetSessionContext {
  private volatile String gameInstanceId;
  private volatile String tenantId;
  private volatile String worldSlug;
  private volatile String realmSlug;
  private volatile String pointerVersion;

  boolean isReady() {
    return StringUtils.hasText(gameInstanceId) && StringUtils.hasText(tenantId);
  }

  String gameInstanceId() {
    return gameInstanceId;
  }

  String tenantId() {
    return tenantId;
  }

  String worldSlug() {
    return worldSlug;
  }

  String realmSlug() {
    return realmSlug;
  }

  String pointerVersion() {
    return pointerVersion;
  }

  void bootstrap(
      String gameInstanceId,
      String tenantId,
      String worldSlug,
      String realmSlug,
      String pointerVersion) {
    if (!StringUtils.hasText(gameInstanceId) || !StringUtils.hasText(tenantId)) {
      return;
    }
    this.gameInstanceId = gameInstanceId;
    this.tenantId = tenantId;
    if (routingBundleIsComplete(worldSlug, realmSlug, pointerVersion)) {
      this.worldSlug = worldSlug;
      this.realmSlug = realmSlug;
      this.pointerVersion = pointerVersion;
      return;
    }
    this.worldSlug = null;
    this.realmSlug = null;
    this.pointerVersion = null;
  }

  private static boolean routingBundleIsComplete(
      String worldSlug, String realmSlug, String pointerVersion) {
    return StringUtils.hasText(worldSlug)
        && StringUtils.hasText(realmSlug)
        && StringUtils.hasText(pointerVersion);
  }
}
