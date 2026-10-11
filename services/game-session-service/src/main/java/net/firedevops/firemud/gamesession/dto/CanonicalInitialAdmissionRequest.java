package net.firedevops.firemud.gamesession.dto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact first-OPEN intent plus the immutable World-issued hold identity. */
public record CanonicalInitialAdmissionRequest(
    String targetNamespace,
    UUID canonicalTenantId,
    String worldSlug,
    UUID realmId,
    UUID playableStateNamespaceId,
    String playableStateScope,
    UUID canonicalGameInstanceId,
    UUID canonicalVersionId,
    long activeLifecycleEpoch,
    long expectedCatalogRevision,
    OriginKind originKind,
    Long expectedPriorPointerVersion,
    String initialAdmissionRequestId,
    String requestDigest,
    UUID holdId,
    UUID holdFence,
    String holdBindingDigest) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
  private static final Pattern REQUEST_DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern WORLD_BINDING_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final String DIGEST_DOMAIN = "game-session-canonical-initial-admission/v1";

  /** The only first-OPEN origin preconditions accepted by the canonical contract. */
  public enum OriginKind {
    NO_PRIOR_POINTER,
    EXPECT_CLOSED
  }

  public CanonicalInitialAdmissionRequest {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("targetNamespace must be a canonical workload namespace");
    }
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireSlug(worldSlug, "worldSlug");
    requireNonNil(realmId, "realmId");
    requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
    if (!"SHARED".equals(playableStateScope)) {
      throw new IllegalArgumentException(
          "Current canonical launch association supports only SHARED playable state");
    }
    requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
    requireNonNil(canonicalVersionId, "canonicalVersionId");
    requirePositive(activeLifecycleEpoch, "activeLifecycleEpoch");
    requirePositive(expectedCatalogRevision, "expectedCatalogRevision");
    Objects.requireNonNull(originKind, "originKind");
    if (originKind == OriginKind.NO_PRIOR_POINTER) {
      if (expectedPriorPointerVersion != null) {
        throw new IllegalArgumentException(
            "NO_PRIOR_POINTER must not carry expectedPriorPointerVersion");
      }
    } else if (expectedPriorPointerVersion == null || expectedPriorPointerVersion <= 0L) {
      throw new IllegalArgumentException(
          "EXPECT_CLOSED requires a positive exact expectedPriorPointerVersion");
    }
    // Match the 128-character owner attempt and immutable intent ledgers.
    requireText(initialAdmissionRequestId, "initialAdmissionRequestId", 128);
    if (!REQUEST_DIGEST.matcher(Objects.requireNonNull(requestDigest, "requestDigest")).matches()) {
      throw new IllegalArgumentException(
          "requestDigest must be bare lowercase 64-character SHA-256 hex");
    }
    requireNonNil(holdId, "holdId");
    requireNonNil(holdFence, "holdFence");
    if (!WORLD_BINDING_DIGEST
        .matcher(Objects.requireNonNull(holdBindingDigest, "holdBindingDigest"))
        .matches()) {
      throw new IllegalArgumentException(
          "holdBindingDigest must be World-prefixed SHA-256 over its canonical request bytes");
    }
    if (!computeRequestDigest(
            targetNamespace,
            canonicalTenantId,
            worldSlug,
            realmId,
            playableStateNamespaceId,
            playableStateScope,
            canonicalGameInstanceId,
            canonicalVersionId,
            activeLifecycleEpoch,
            expectedCatalogRevision,
            originKind,
            expectedPriorPointerVersion,
            initialAdmissionRequestId)
        .equals(requestDigest)) {
      throw new IllegalArgumentException(
          "requestDigest does not bind the exact initial admission input");
    }
  }

  /**
   * Game Session request digest, deliberately bare and distinct from World's sha256:-prefixed RFC
   * 8785 hold-binding digest.
   */
  public static String computeRequestDigest(
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID realmId,
      UUID playableStateNamespaceId,
      String playableStateScope,
      UUID canonicalGameInstanceId,
      UUID canonicalVersionId,
      long activeLifecycleEpoch,
      long expectedCatalogRevision,
      OriginKind originKind,
      Long expectedPriorPointerVersion,
      String initialAdmissionRequestId) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      add(digest, DIGEST_DOMAIN);
      add(digest, targetNamespace);
      add(digest, canonicalTenantId.toString());
      add(digest, worldSlug);
      add(digest, realmId.toString());
      add(digest, playableStateNamespaceId.toString());
      add(digest, playableStateScope);
      add(digest, canonicalGameInstanceId.toString());
      add(digest, canonicalVersionId.toString());
      add(digest, Long.toString(activeLifecycleEpoch));
      add(digest, Long.toString(expectedCatalogRevision));
      add(digest, originKind.name());
      add(
          digest,
          expectedPriorPointerVersion == null ? "" : expectedPriorPointerVersion.toString());
      add(digest, initialAdmissionRequestId);
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void add(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private static void requireSlug(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.length() > 120 || !SLUG.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " must be a canonical slug");
    }
  }

  private static void requireText(String value, String name, int maxLength) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || value.codePointCount(0, value.length()) > maxLength) {
      throw new IllegalArgumentException(name + " must contain 1.." + maxLength + " characters");
    }
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0L) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }
}
