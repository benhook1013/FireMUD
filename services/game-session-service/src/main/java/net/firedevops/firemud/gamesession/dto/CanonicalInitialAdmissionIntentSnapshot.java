package net.firedevops.firemud.gamesession.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;

/**
 * Immutable readback of Game Session's pre-hold initial-admission intent.
 *
 * <p>The state is the durable intent row state only. Neither the historical World hold identity nor
 * this snapshot proves a live World hold, lifecycle currentness, or gameplay admission.
 */
public record CanonicalInitialAdmissionIntentSnapshot(
    Request holdRequest,
    WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest,
    IntentState state,
    HoldIdentity holdIdentity,
    SourceBinding sourceBinding,
    Instant createdAt,
    Instant updatedAt,
    Instant terminalAt) {
  private static final Pattern PREFIXED_SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

  public enum IntentState {
    PENDING_HOLD,
    HOLD_ATTACHED,
    TERMINAL
  }

  public CanonicalInitialAdmissionIntentSnapshot {
    Objects.requireNonNull(holdRequest, "holdRequest");
    Objects.requireNonNull(lifecycleRequest, "lifecycleRequest");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(sourceBinding, "sourceBinding");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    requireExactBinding(holdRequest, lifecycleRequest, sourceBinding);
    if (holdIdentity != null
        && !java.util.Arrays.equals(
            holdRequest.canonicalRequestBytes(), holdIdentity.canonicalRequestBytes())) {
      throw new IllegalArgumentException(
          "Attached World hold identity does not echo the retained canonical request");
    }
    switch (state) {
      case PENDING_HOLD -> {
        if (holdIdentity != null || terminalAt != null) {
          throw new IllegalArgumentException(
              "Pending hold intent cannot contain attached identity");
        }
      }
      case HOLD_ATTACHED -> {
        if (holdIdentity == null || terminalAt != null) {
          throw new IllegalArgumentException("Attached hold intent requires only its identity");
        }
      }
      case TERMINAL -> {
        if (holdIdentity == null || terminalAt == null) {
          throw new IllegalArgumentException("Terminal intent requires identity and terminal time");
        }
      }
    }
  }

  /** Exact owner fields retained beside the canonical World request bytes. */
  public record SourceBinding(
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID realmId,
      UUID playableStateNamespaceId,
      String playableStateScope,
      boolean catalogVisible,
      boolean catalogPublicProduction,
      long catalogRevision,
      UUID catalogCreationRequestId,
      String catalogRequestDigest,
      String catalogReceiptDigest,
      UUID tenantAssociationOperationId,
      long gameSessionTenantId,
      UUID canonicalGameInstanceId,
      long gameInstanceId,
      UUID canonicalVersionId,
      long runtimeVersionId,
      String controlPlaneRequestId,
      String launchDescriptorId,
      long capturedStartingRowVersion,
      long currentRowVersion,
      String descriptorRequestDigest,
      String descriptorResultDigest,
      String releaseAttestationDigest) {
    public SourceBinding {
      Objects.requireNonNull(targetNamespace, "targetNamespace");
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      Objects.requireNonNull(worldSlug, "worldSlug");
      requireNonNil(realmId, "realmId");
      requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
      if (!"SHARED".equals(playableStateScope)) {
        throw new IllegalArgumentException("Initial admission intent requires SHARED scope");
      }
      if (!catalogVisible || !catalogPublicProduction || catalogRevision <= 0) {
        throw new IllegalArgumentException(
            "Initial admission intent requires the exact visible public catalog source");
      }
      requireNonNil(catalogCreationRequestId, "catalogCreationRequestId");
      requireDigest(catalogRequestDigest, PREFIXED_SHA256, "catalogRequestDigest");
      requireDigest(catalogReceiptDigest, PREFIXED_SHA256, "catalogReceiptDigest");
      requireNonNil(tenantAssociationOperationId, "tenantAssociationOperationId");
      requirePositive(gameSessionTenantId, "gameSessionTenantId");
      requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
      requirePositive(gameInstanceId, "gameInstanceId");
      requireNonNil(canonicalVersionId, "canonicalVersionId");
      requirePositive(runtimeVersionId, "runtimeVersionId");
      requireText(controlPlaneRequestId, "controlPlaneRequestId", 128);
      requireText(launchDescriptorId, "launchDescriptorId", 255);
      if (capturedStartingRowVersion < 0 || currentRowVersion < capturedStartingRowVersion) {
        throw new IllegalArgumentException("Launch row versions are invalid");
      }
      requireDigest(descriptorRequestDigest, PREFIXED_SHA256, "descriptorRequestDigest");
      requireDigest(descriptorResultDigest, PREFIXED_SHA256, "descriptorResultDigest");
      requireDigest(releaseAttestationDigest, PREFIXED_SHA256, "releaseAttestationDigest");
    }
  }

  /** Checks every request selector and digest against the immutable locked owner snapshots. */
  public static void requireExactBinding(
      Request holdRequest,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest,
      SourceBinding source) {
    Objects.requireNonNull(holdRequest, "holdRequest");
    Objects.requireNonNull(lifecycleRequest, "lifecycleRequest");
    Objects.requireNonNull(source, "source");
    requireIntentRequestId(holdRequest.initialAdmissionRequestId());
    OriginKind origin = OriginKind.valueOf(holdRequest.initialAdmissionOrigin().name());
    String computedDigest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            holdRequest.targetNamespace(),
            holdRequest.canonicalTenantId(),
            holdRequest.worldSlug(),
            holdRequest.realmId(),
            holdRequest.playableStateNamespaceId(),
            holdRequest.playableStateScope(),
            holdRequest.canonicalGameInstanceId(),
            holdRequest.canonicalVersionId(),
            holdRequest.activeLifecycleEpoch(),
            holdRequest.expectedCatalogRevision(),
            origin,
            holdRequest.expectedPriorPointerVersion(),
            holdRequest.initialAdmissionRequestId());
    if (!computedDigest.equals(holdRequest.initialAdmissionRequestDigest())) {
      throw new IllegalArgumentException(
          "Game Session request digest does not bind the complete initial-admission input");
    }
    if (!source.targetNamespace().equals(holdRequest.targetNamespace())
        || !source.canonicalTenantId().equals(holdRequest.canonicalTenantId())
        || !source.worldSlug().equals(holdRequest.worldSlug())
        || !source.realmId().equals(holdRequest.realmId())
        || !source.playableStateNamespaceId().equals(holdRequest.playableStateNamespaceId())
        || !source.playableStateScope().equals(holdRequest.playableStateScope())
        || source.catalogRevision() != holdRequest.expectedCatalogRevision()
        || !source.canonicalGameInstanceId().equals(holdRequest.canonicalGameInstanceId())
        || !source.canonicalVersionId().equals(holdRequest.canonicalVersionId())) {
      throw new IllegalArgumentException(
          "World hold request differs from the locked catalog and launch owner sources");
    }
    if (!source.targetNamespace().equals(lifecycleRequest.targetNamespace())
        || !source.canonicalTenantId().equals(lifecycleRequest.canonicalTenantId())
        || !source.worldSlug().equals(lifecycleRequest.worldSlug())
        || !source.canonicalGameInstanceId().equals(lifecycleRequest.canonicalGameInstanceId())
        || !source.playableStateNamespaceId().equals(lifecycleRequest.playableStateNamespaceId())
        || !source.playableStateScope().equals(lifecycleRequest.playableStateScope())
        || !source.controlPlaneRequestId().equals(lifecycleRequest.controlPlaneRequestId())
        || !source.canonicalVersionId().equals(lifecycleRequest.canonicalVersionId())
        || !lifecycleRequest.publicProduction()
        || !source
            .descriptorRequestDigest()
            .equals(lifecycleRequest.expectedDescriptorRequestDigest())
        || !source
            .descriptorResultDigest()
            .equals(lifecycleRequest.expectedDescriptorResultDigest())
        || !source
            .releaseAttestationDigest()
            .equals(lifecycleRequest.expectedReleaseAttestationDigest())) {
      throw new IllegalArgumentException(
          "World lifecycle selector differs from the locked launch owner source");
    }
  }

  private static void requireIntentRequestId(String value) {
    if (value == null
        || value.isBlank()
        || value.codePointCount(0, value.length()) > 120
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          "Initial-admission request ID must fit the immutable V28 120-character intent column");
    }
  }

  private static void requireText(String value, String name, int maximum) {
    if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maximum) {
      throw new IllegalArgumentException(name + " is required and must fit its storage limit");
    }
  }

  private static void requireDigest(String value, Pattern pattern, String name) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " has an invalid SHA-256 digest");
    }
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireNonNil(UUID value, String name) {
    if (value == null || new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }
}
