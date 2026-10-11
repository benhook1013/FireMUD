package net.firedevops.firemud.gamedesign.service;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Mandatory actual-byte proof for one immutable published asset object. */
public record PublishedArtifactDigest(
    String usageKey,
    String artifactKind,
    String immutableObjectKey,
    String contentDigest,
    String contentType,
    int artifactSchemaVersion) {
  private static final String SHA256_PREFIX = "sha256:";

  public PublishedArtifactDigest {
    requireCanonicalText(usageKey, "usageKey");
    requireCanonicalText(artifactKind, "artifactKind");
    requireCanonicalText(contentType, "contentType");
    if (artifactSchemaVersion <= 0) {
      throw new IllegalArgumentException("artifactSchemaVersion must be positive");
    }
    if (contentDigest == null || !contentDigest.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "contentDigest must be a sha256-prefixed lowercase SHA-256 digest");
    }
    String expectedObjectKey =
        "artifacts/sha256/" + contentDigest.substring(SHA256_PREFIX.length());
    if (!Objects.equals(expectedObjectKey, immutableObjectKey)) {
      throw new IllegalArgumentException("immutableObjectKey must address contentDigest");
    }
  }

  public static int compareUsageKeysUtf8(String left, String right) {
    byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
    byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
    int commonLength = Math.min(leftBytes.length, rightBytes.length);
    for (int index = 0; index < commonLength; index++) {
      int comparison = Byte.compareUnsigned(leftBytes[index], rightBytes[index]);
      if (comparison != 0) {
        return comparison;
      }
    }
    return Integer.compare(leftBytes.length, rightBytes.length);
  }

  private static void requireCanonicalText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must be nonblank");
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(fieldName + " must not contain control characters");
    }
    try {
      StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException(fieldName + " must be valid UTF-8 text", exception);
    }
  }
}
