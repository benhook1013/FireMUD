package net.firedevops.firemud.gamesession.dto;

import java.util.Objects;
import java.util.UUID;
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
    long currentRowVersion) {
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
    Objects.requireNonNull(launchBindingEvidence, "launchBindingEvidence");
    Objects.requireNonNull(currentGameInstanceStatus, "currentGameInstanceStatus");
    if (currentRowVersion < capturedStartingRowVersion) {
      throw new IllegalArgumentException(
          "currentRowVersion must not precede the captured STARTING row version");
    }

    var descriptor = launchBindingEvidence.descriptor();
    descriptor.requireValid();
    launchBindingEvidence.releaseAttestation().requireValid(descriptor);
    if (!targetNamespace.equals(descriptor.targetNamespace())
        || !canonicalTenantId.equals(descriptor.canonicalTenantId())
        || !worldSlug.equals(descriptor.worldSlug())
        || !controlPlaneRequestId.equals(descriptor.controlPlaneRequestId())
        || !launchDescriptorId.equals(descriptor.launchDescriptorId())) {
      throw new IllegalArgumentException(
          "Launch association does not match its complete authored launch binding");
    }
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
