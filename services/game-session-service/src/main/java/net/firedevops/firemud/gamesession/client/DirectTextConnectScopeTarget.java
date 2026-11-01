package net.firedevops.firemud.gamesession.client;

import java.util.UUID;
import net.firedevops.firemud.common.security.RequestIdValidation;

/** Server-resolved routing evidence, not authentication or gameplay admission authority. */
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
    RequestIdValidation.requirePositiveLong(tenantId, "tenantId");
    requireText(worldSlug, "worldSlug", 128);
    requireText(realmSlug, "realmSlug", 128);
    requireUuid(realmId, "realmId");
    requireUuid(playableStateNamespaceId, "playableStateNamespaceId");
    requireScope(playableStateScope);
    RequestIdValidation.requirePositiveLong(gameInstanceId, "gameInstanceId");
    if (catalogRevision < 1 || pointerVersion < 1) {
      throw new IllegalArgumentException("routing revisions must be positive");
    }
  }

  static void requireText(String value, String name, int maxLength) {
    if (value == null || value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(name + " is required and bounded");
    }
  }

  static void requireUuid(String value, String name) {
    if (value == null || !UUID.fromString(value).toString().equals(value)) {
      throw new IllegalArgumentException(name + " must be a canonical UUID");
    }
  }

  static void requireScope(String value) {
    if (!"SHARED".equals(value) && !"ISOLATED".equals(value)) {
      throw new IllegalArgumentException("playableStateScope must be SHARED or ISOLATED");
    }
  }
}
