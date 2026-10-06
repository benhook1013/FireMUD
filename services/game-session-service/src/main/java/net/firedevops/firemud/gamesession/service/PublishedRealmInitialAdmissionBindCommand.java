package net.firedevops.firemud.gamesession.service;

import java.util.UUID;

/** Explicit internal request to launch and bind one exact published realm for first admission. */
public record PublishedRealmInitialAdmissionBindCommand(
    UUID canonicalTenantId,
    long publishedVersionId,
    long gameTemplateId,
    long ownerAccountId,
    String worldSlug,
    String realmSlug,
    String initialAdmissionRequestId) {
  public PublishedRealmInitialAdmissionBindCommand {
    if (canonicalTenantId == null
        || new UUID(0L, 0L).equals(canonicalTenantId)
        || publishedVersionId <= 0
        || gameTemplateId <= 0
        || ownerAccountId <= 0
        || !canonicalUuid(initialAdmissionRequestId)) {
      throw new IllegalArgumentException("Published initial admission identity is incomplete");
    }
    requireSlug(worldSlug, "worldSlug");
    requireSlug(realmSlug, "realmSlug");
  }

  private static boolean canonicalUuid(String value) {
    if (value == null) {
      return false;
    }
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static void requireSlug(String value, String name) {
    if (value == null || !value.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || value.length() > 64) {
      throw new IllegalArgumentException(name + " must be a canonical realm-policy slug");
    }
  }
}
