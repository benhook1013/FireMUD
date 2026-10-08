package net.firedevops.firemud.gamesession.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof.Outcome;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;

/**
 * Read-only canonical public-production route evidence carried beside Game Session's private row
 * keys. Instances are assembled only after catalog, pointer-owner proof, and launch-association
 * readback agree.
 */
public record CanonicalPlayableTarget(
    String targetNamespace,
    String tenantSlug,
    UUID canonicalTenantId,
    String worldSlug,
    String worldDisplayName,
    UUID realmId,
    String realmSlug,
    String realmDisplayName,
    long gameSessionTenantId,
    long gameInstanceId,
    UUID playableStateNamespaceId,
    String playableStateScope,
    UUID canonicalGameInstanceId,
    UUID canonicalVersionId,
    long runtimeVersionId,
    long catalogRevision,
    long pointerVersion,
    String admissionPointerSnapshotDigest,
    long activeWorldEpoch,
    String initialAdmissionRequestId,
    String initialAdmissionRequestDigest,
    OriginKind initialAdmissionOriginKind,
    Long expectedPriorPointerVersion,
    UUID holdId,
    UUID holdFence,
    String holdBindingDigest,
    long auditEventId,
    String ownerProofDigest,
    Outcome ownerProofOutcome,
    boolean positiveDurableAbort,
    Instant ownerProofTerminalAt,
    String characterCreationPolicy)
    implements Serializable {
  private static final long serialVersionUID = 1L;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern REQUEST_DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern SNAPSHOT_DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern OWNER_PROOF_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

  public CanonicalPlayableTarget {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("targetNamespace is not a valid owner namespace");
    }
    requireText(tenantSlug, "tenantSlug");
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireText(worldSlug, "worldSlug");
    requireText(worldDisplayName, "worldDisplayName");
    requireNonNil(realmId, "realmId");
    requireText(realmSlug, "realmSlug");
    requireText(realmDisplayName, "realmDisplayName");
    requirePositive(gameSessionTenantId, "gameSessionTenantId");
    requirePositive(gameInstanceId, "gameInstanceId");
    requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
    if (!"SHARED".equals(playableStateScope) && !"ISOLATED".equals(playableStateScope)) {
      throw new IllegalArgumentException("playableStateScope is not a supported catalog value");
    }
    requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
    requireNonNil(canonicalVersionId, "canonicalVersionId");
    requirePositive(runtimeVersionId, "runtimeVersionId");
    requirePositive(catalogRevision, "catalogRevision");
    requirePositive(pointerVersion, "pointerVersion");
    if (admissionPointerSnapshotDigest == null
        || !SNAPSHOT_DIGEST.matcher(admissionPointerSnapshotDigest).matches()) {
      throw new IllegalArgumentException(
          "admissionPointerSnapshotDigest is not raw lowercase SHA-256 hex");
    }
    requirePositive(activeWorldEpoch, "activeWorldEpoch");
    requireText(initialAdmissionRequestId, "initialAdmissionRequestId");
    if (initialAdmissionRequestDigest == null
        || !REQUEST_DIGEST.matcher(initialAdmissionRequestDigest).matches()) {
      throw new IllegalArgumentException(
          "initialAdmissionRequestDigest is not a canonical request digest");
    }
    Objects.requireNonNull(initialAdmissionOriginKind, "initialAdmissionOriginKind");
    if (initialAdmissionOriginKind == OriginKind.NO_PRIOR_POINTER) {
      if (expectedPriorPointerVersion != null) {
        throw new IllegalArgumentException(
            "NO_PRIOR_POINTER must not carry expectedPriorPointerVersion");
      }
    } else if (expectedPriorPointerVersion == null || expectedPriorPointerVersion <= 0L) {
      throw new IllegalArgumentException("EXPECT_CLOSED requires an exact prior pointer version");
    }
    requireNonNil(holdId, "holdId");
    requireNonNil(holdFence, "holdFence");
    if (holdBindingDigest == null || !OWNER_PROOF_DIGEST.matcher(holdBindingDigest).matches()) {
      throw new IllegalArgumentException("holdBindingDigest is not a canonical proof digest");
    }
    requirePositive(auditEventId, "auditEventId");
    if (ownerProofDigest == null || !OWNER_PROOF_DIGEST.matcher(ownerProofDigest).matches()) {
      throw new IllegalArgumentException("ownerProofDigest is not a canonical proof digest");
    }
    if (ownerProofOutcome != Outcome.COMMITTED || positiveDurableAbort) {
      throw new IllegalArgumentException(
          "Canonical playable target requires exact COMMITTED proof");
    }
    Objects.requireNonNull(ownerProofTerminalAt, "ownerProofTerminalAt");
    requireText(characterCreationPolicy, "characterCreationPolicy");
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || !value.equals(value.trim())) {
      throw new IllegalArgumentException(name + " must be nonblank and trimmed");
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
