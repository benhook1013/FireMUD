package net.firedevops.firemud.tcpproxy.telnet;

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
    return gameInstanceId != null
        && !gameInstanceId.isBlank()
        && tenantId != null
        && !tenantId.isBlank();
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
    if (gameInstanceId == null
        || gameInstanceId.isBlank()
        || tenantId == null
        || tenantId.isBlank()) {
      return;
    }
    this.gameInstanceId = gameInstanceId;
    this.tenantId = tenantId;
    TelnetRoutingBundle routingBundle =
        TelnetRoutingBundle.normalize(worldSlug, realmSlug, pointerVersion);
    if (routingBundle != null) {
      this.worldSlug = worldSlug;
      this.realmSlug = realmSlug;
      this.pointerVersion = pointerVersion;
      return;
    }
    this.worldSlug = null;
    this.realmSlug = null;
    this.pointerVersion = null;
  }
}
