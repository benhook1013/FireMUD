package net.firedevops.firemud.gamesession.dto;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;

/** Durable fresh-tenant launch association plus a separate current Game Session row projection. */
public record CanonicalGameInstanceLaunchAssociation(
    String targetNamespace,
    long gameSessionTenantId,
    UUID tenantAssociationOperationId,
    UUID canonicalTenantId,
    UUID gameInstanceUuid,
    String worldSlug,
    UUID playableStateNamespaceId,
    RealmEntryPolicy.StateScope playableStateScope,
    boolean publicProduction,
    String controlPlaneRequestId,
    String launchDescriptorId,
    long capturedStartingRowVersion,
    CompleteLaunchBindingEvidence launchBindingEvidence,
    CurrentGameInstanceStatus currentGameInstanceStatus,
    long currentRowVersion,
    AuthoredWorldLaunchDescriptorEvidence fixtureDescriptor,
    String fixtureProvenanceDigest,
    UUID fixtureCanonicalVersionId) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public enum CurrentGameInstanceStatus {
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED
  }

  public CanonicalGameInstanceLaunchAssociation {
    AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
    requirePositive(gameSessionTenantId, "gameSessionTenantId");
    requireNonNil(tenantAssociationOperationId, "tenantAssociationOperationId");
    requireNonNil(gameInstanceUuid, "gameInstanceUuid");
    requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
    Objects.requireNonNull(playableStateScope, "playableStateScope");
    if (playableStateScope != RealmEntryPolicy.StateScope.SHARED) {
      throw new IllegalArgumentException("Canonical launch association requires SHARED scope");
    }
    if (!publicProduction) {
      throw new IllegalArgumentException(
          "Canonical launch association requires owner-confirmed public production");
    }
    requireText(controlPlaneRequestId, "controlPlaneRequestId");
    requireText(launchDescriptorId, "launchDescriptorId");
    if (capturedStartingRowVersion < 0) {
      throw new IllegalArgumentException("capturedStartingRowVersion must not be negative");
    }
    if ((launchBindingEvidence == null) == (fixtureDescriptor == null)
        || (fixtureDescriptor == null) != (fixtureProvenanceDigest == null)
        || (fixtureDescriptor == null) != (fixtureCanonicalVersionId == null)) {
      throw new IllegalArgumentException(
          "Launch association requires exactly one Human binding or run-owned fixture descriptor");
    }
    if (fixtureProvenanceDigest != null
        && !fixtureProvenanceDigest.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("fixtureProvenanceDigest must be a prefixed SHA-256");
    }
    Objects.requireNonNull(currentGameInstanceStatus, "currentGameInstanceStatus");
    if (currentRowVersion < capturedStartingRowVersion) {
      throw new IllegalArgumentException(
          "currentRowVersion must not precede the captured STARTING row version");
    }

    var descriptor =
        launchBindingEvidence != null ? launchBindingEvidence.descriptor() : fixtureDescriptor;
    descriptor.requireValid();
    if (launchBindingEvidence != null) {
      launchBindingEvidence.releaseAttestation().requireValid(descriptor);
    }
    if (!targetNamespace.equals(descriptor.targetNamespace())
        || !canonicalTenantId.equals(descriptor.canonicalTenantId())
        || !worldSlug.equals(descriptor.worldSlug())
        || !controlPlaneRequestId.equals(descriptor.controlPlaneRequestId())
        || !launchDescriptorId.equals(descriptor.launchDescriptorId())) {
      throw new IllegalArgumentException(
          "Launch association does not match its complete authored launch binding");
    }
  }

  /** Existing Human launch association constructor retained at its call sites. */
  public CanonicalGameInstanceLaunchAssociation(
      String targetNamespace,
      long gameSessionTenantId,
      UUID tenantAssociationOperationId,
      UUID canonicalTenantId,
      UUID gameInstanceUuid,
      String worldSlug,
      UUID playableStateNamespaceId,
      RealmEntryPolicy.StateScope playableStateScope,
      boolean publicProduction,
      String controlPlaneRequestId,
      String launchDescriptorId,
      long capturedStartingRowVersion,
      CompleteLaunchBindingEvidence launchBindingEvidence,
      CurrentGameInstanceStatus currentGameInstanceStatus,
      long currentRowVersion) {
    this(
        targetNamespace,
        gameSessionTenantId,
        tenantAssociationOperationId,
        canonicalTenantId,
        gameInstanceUuid,
        worldSlug,
        playableStateNamespaceId,
        playableStateScope,
        publicProduction,
        controlPlaneRequestId,
        launchDescriptorId,
        capturedStartingRowVersion,
        Objects.requireNonNull(launchBindingEvidence, "launchBindingEvidence"),
        currentGameInstanceStatus,
        currentRowVersion,
        null,
        null,
        null);
  }

  /** Canonical content version from retained publication or fixture policy evidence. */
  public UUID canonicalVersionId() {
    return launchBindingEvidence != null
        ? launchBindingEvidence.releaseAttestation().canonicalVersionId()
        : fixtureCanonicalVersionId;
  }

  public boolean isRunOwnedFixture() {
    return fixtureDescriptor != null;
  }

  /** The authored launch descriptor of either variant. */
  public AuthoredWorldLaunchDescriptorEvidence descriptor() {
    return launchBindingEvidence != null ? launchBindingEvidence.descriptor() : fixtureDescriptor;
  }

  /**
   * The Human release-attestation digest, or for the run-owned fixture its policy-seal evidence
   * digest, which takes the attestation's place in lifecycle and intent bindings.
   */
  public String attestationDigest() {
    return launchBindingEvidence != null
        ? launchBindingEvidence.releaseAttestation().evidenceDigest()
        : fixtureProvenanceDigest;
  }

  private static void requireNonNil(UUID value, String name) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
