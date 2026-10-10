package net.firedevops.firemud.gamedesign.service;

import java.util.List;
import java.util.Objects;

/** Immutable result of constructing and verifying a version-scoped asset manifest. */
public record ExportedAssetManifest(
    String manifestHash,
    int manifestSchemaVersion,
    List<String> requiredManifestAssetKeys,
    List<PublishedArtifactDigest> artifactDigests) {
  public ExportedAssetManifest {
    if (manifestHash == null || !manifestHash.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "manifestHash must be a sha256-prefixed lowercase SHA-256 digest");
    }
    if (manifestSchemaVersion <= 0) {
      throw new IllegalArgumentException("manifestSchemaVersion must be positive");
    }
    requiredManifestAssetKeys = List.copyOf(Objects.requireNonNull(requiredManifestAssetKeys));
    artifactDigests = List.copyOf(Objects.requireNonNull(artifactDigests));

    List<String> digestUsageKeys =
        artifactDigests.stream().map(PublishedArtifactDigest::usageKey).toList();
    if (!requiredManifestAssetKeys.equals(digestUsageKeys)) {
      throw new IllegalArgumentException(
          "required manifest keys must exactly match the ordered artifact digest usage keys");
    }
    for (int index = 1; index < requiredManifestAssetKeys.size(); index++) {
      if (PublishedArtifactDigest.compareUsageKeysUtf8(
              requiredManifestAssetKeys.get(index - 1), requiredManifestAssetKeys.get(index))
          >= 0) {
        throw new IllegalArgumentException(
            "manifest asset keys must be unique and in UTF-8 byte order");
      }
    }
  }
}
