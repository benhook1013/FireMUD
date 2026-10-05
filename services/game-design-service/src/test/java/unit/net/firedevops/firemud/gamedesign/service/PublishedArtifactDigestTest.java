package net.firedevops.firemud.gamedesign.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublishedArtifactDigestTest {
  private static final String HASH = "0123456789abcdef".repeat(4);
  private static final String MANIFEST_HASH = "sha256:" + HASH;

  @Test
  void acceptsExactDigestAndContentAddressedObjectKey() {
    PublishedArtifactDigest digest =
        new PublishedArtifactDigest(
            "branding/logo",
            "BINARY",
            "artifacts/sha256/" + HASH,
            "sha256:" + HASH,
            "image/png",
            1);

    assertEquals("branding/logo", digest.usageKey());
    assertEquals("sha256:" + HASH, digest.contentDigest());
    assertEquals(1, digest.artifactSchemaVersion());
  }

  @Test
  void rejectsDigestWithoutSha256PrefixOrWithNonCanonicalHex() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PublishedArtifactDigest(
                "logo", "BINARY", "artifacts/sha256/" + HASH, HASH, "image/png", 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PublishedArtifactDigest(
                "logo",
                "BINARY",
                "artifacts/sha256/" + HASH.toUpperCase(),
                "sha256:" + HASH.toUpperCase(),
                "image/png",
                1));
  }

  @Test
  void rejectsObjectKeyThatDoesNotAddressTheDigest() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PublishedArtifactDigest(
                "logo",
                "BINARY",
                "artifacts/sha256/" + "f".repeat(64),
                "sha256:" + HASH,
                "image/png",
                1));
  }

  @Test
  void rejectsIncompleteArtifactMetadata() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PublishedArtifactDigest(
                "logo", "BINARY", "artifacts/sha256/" + HASH, "sha256:" + HASH, " ", 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PublishedArtifactDigest(
                "logo", "BINARY", "artifacts/sha256/" + HASH, "sha256:" + HASH, "image/png", 0));
  }

  @Test
  void rejectsControlCharactersAndMalformedUtf8Metadata() {
    assertThrows(IllegalArgumentException.class, () -> artifact("logo\nname", HASH));
    assertThrows(IllegalArgumentException.class, () -> artifact("logo\ud800", HASH));
  }

  @Test
  void manifestRequiresExactUniqueUtf8OrderedDigestKeysAndDefensiveLists() {
    PublishedArtifactDigest privateUseBmp = artifact("\ue000", HASH);
    PublishedArtifactDigest supplementary = artifact("\ud800\udc00", "f".repeat(64));
    ArrayList<String> keys = new ArrayList<>(List.of("\ue000", "\ud800\udc00"));
    ArrayList<PublishedArtifactDigest> digests =
        new ArrayList<>(List.of(privateUseBmp, supplementary));

    ExportedAssetManifest manifest = new ExportedAssetManifest(MANIFEST_HASH, 1, keys, digests);
    keys.clear();
    digests.clear();

    assertEquals(List.of("\ue000", "\ud800\udc00"), manifest.requiredManifestAssetKeys());
    assertThrows(UnsupportedOperationException.class, () -> manifest.artifactDigests().clear());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExportedAssetManifest(
                MANIFEST_HASH,
                1,
                List.of("\ud800\udc00", "\ue000"),
                List.of(supplementary, privateUseBmp)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExportedAssetManifest(
                MANIFEST_HASH,
                1,
                List.of("\ue000", "\ue000"),
                List.of(privateUseBmp, privateUseBmp)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExportedAssetManifest(
                MANIFEST_HASH, 1, List.of("\ue000"), List.of(privateUseBmp, supplementary)));
  }

  private static PublishedArtifactDigest artifact(String usageKey, String hash) {
    return new PublishedArtifactDigest(
        usageKey,
        "BINARY",
        "artifacts/sha256/" + hash,
        "sha256:" + hash,
        "application/octet-stream",
        1);
  }
}
