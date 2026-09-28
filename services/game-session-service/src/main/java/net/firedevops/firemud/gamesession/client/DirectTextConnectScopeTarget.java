package net.firedevops.firemud.gamesession.client;

/** Server-resolved realm routing evidence sent to Account before a direct-text scope is issued. */
public record DirectTextConnectScopeTarget(
    String tenantId,
    String worldSlug,
    String realmSlug,
    String realmId,
    String playableStateNamespaceId,
    String playableStateScope,
    String gameInstanceId,
    long catalogRevision,
    long pointerVersion) {
  public DirectTextConnectScopeTarget {
    requireText(tenantId, "tenantId");
    requireText(worldSlug, "worldSlug");
    requireText(realmSlug, "realmSlug");
    requireText(realmId, "realmId");
    requireText(playableStateNamespaceId, "playableStateNamespaceId");
    requireText(playableStateScope, "playableStateScope");
    requireText(gameInstanceId, "gameInstanceId");
    if (catalogRevision < 1 || pointerVersion < 1) {
      throw new IllegalArgumentException("routing revisions must be positive");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
