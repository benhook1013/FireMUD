package net.firedevops.firemud.gamesession.repository;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayActorOrigins;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRegionBridgeObligation;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRuntimeActorReference;
import net.firedevops.firemud.gamesession.client.CanonicalPlayerCreatedRuntimeReferenceClient;
import net.firedevops.firemud.gamesession.client.CanonicalPreseededActorRuntimeReferenceClient;

/** Manual owner for the initial-NEW region binding mutation and its durable local ACK. */
final class CanonicalGameplayRegionBindingBridgeOwner {
  private final CanonicalGameplayRegionLeaseOwner regionLeaseOwner;
  private final CanonicalPreseededActorRuntimeReferenceClient entityReferenceClient;
  private final CanonicalPlayerCreatedRuntimeReferenceClient createdReferenceClient;

  CanonicalGameplayRegionBindingBridgeOwner(
      CanonicalGameplayRegionLeaseOwner regionLeaseOwner,
      CanonicalPreseededActorRuntimeReferenceClient entityReferenceClient) {
    this(regionLeaseOwner, entityReferenceClient, null);
  }

  CanonicalGameplayRegionBindingBridgeOwner(
      CanonicalGameplayRegionLeaseOwner regionLeaseOwner,
      CanonicalPreseededActorRuntimeReferenceClient entityReferenceClient,
      CanonicalPlayerCreatedRuntimeReferenceClient createdReferenceClient) {
    this.regionLeaseOwner = Objects.requireNonNull(regionLeaseOwner, "regionLeaseOwner");
    this.entityReferenceClient =
        Objects.requireNonNull(entityReferenceClient, "entityReferenceClient");
    this.createdReferenceClient = createdReferenceClient;
  }

  /** Acquires the real region grant/fence, then performs the selected first-NEW bridge. */
  CanonicalGameplayBindingInventoryRepository.RegionBridgeAcknowledgement installAndBridge(
      UUID transitionId,
      net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest request) {
    return bridge(regionLeaseOwner.installForTransition(transitionId, request));
  }

  /**
   * Returns the closed same-grant capability so a lost bridge reply can retry without reacquire.
   */
  CanonicalGameplayRegionLeaseOwner.Installation installForTransition(
      UUID transitionId,
      net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest request) {
    return regionLeaseOwner.installForTransition(transitionId, request);
  }

  /**
   * Installs another per-binding bridge under a still-current region installation created by this
   * same manually composed owner. The supplied handle carries private grant authority; no token is
   * accepted or reconstructed by the caller.
   */
  CanonicalGameplayRegionLeaseOwner.Installation installForTransition(
      CanonicalGameplayRegionLeaseOwner.Installation existingRegionInstallation,
      UUID transitionId,
      net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest request) {
    return regionLeaseOwner.installForTransition(existingRegionInstallation, transitionId, request);
  }

  /** Executes only an exact first-NEW bridge from a privately created region installation. */
  CanonicalGameplayBindingInventoryRepository.RegionBridgeAcknowledgement bridge(
      CanonicalGameplayRegionLeaseOwner.Installation installation) {
    Objects.requireNonNull(installation, "installation");
    CanonicalGameplayRegionLeaseOwner.BridgeContext initial =
        regionLeaseOwner.currentBridgeContext(installation);
    if (initial.evidence().expectedPrior() != null
        || initial.evidence().transition().status()
            != net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot
                .Status.PROVISIONAL) {
      throw unavailable();
    }

    CanonicalGameplayRuntimeActorReference reference = readReference(installation.worldRequest);
    CanonicalGameplayRegionLeaseOwner.RegionBridgePreimage initialPreimage =
        regionLeaseOwner.probeRegionSessionBinding(installation, reference);
    regionLeaseOwner.prepareBridge(installation, initialPreimage, reference);

    // The stable operation and current SQL fence are durable before this first physical write.
    CanonicalGameplayRuntimeActorReference beforeWriteReference =
        readReference(installation.worldRequest);
    if (!sameReference(reference, beforeWriteReference)) throw unavailable();
    CanonicalGameplayRegionLeaseOwner.RegionBridgePreimage beforeWritePreimage =
        regionLeaseOwner.probeRegionSessionBinding(installation, beforeWriteReference);
    CanonicalGameplayBindingRegionBridgeObligation prepared =
        regionLeaseOwner.prepareBridge(installation, beforeWritePreimage, beforeWriteReference);
    regionLeaseOwner.applyRegionSessionBinding(installation, beforeWriteReference);

    CanonicalGameplayRuntimeActorReference currentReference =
        readReference(installation.worldRequest);
    if (!sameReference(reference, currentReference)) throw unavailable();
    CanonicalGameplayRegionLeaseOwner.RegionBridgePreimage beforeReadbackPreimage =
        regionLeaseOwner.probeRegionSessionBinding(installation, currentReference);
    prepared =
        regionLeaseOwner.prepareBridge(installation, beforeReadbackPreimage, currentReference);

    CanonicalGameplayRegionLeaseRedisOwner.BindingReadback readback =
        regionLeaseOwner.readBackRegionSessionBinding(
            installation, beforeReadbackPreimage.context().status(), currentReference);
    CanonicalGameplayRuntimeActorReference afterReadbackReference =
        readReference(installation.worldRequest);
    if (!sameReference(reference, afterReadbackReference)) throw unavailable();
    CanonicalGameplayRegionLeaseOwner.RegionBridgePreimage beforeAckPreimage =
        regionLeaseOwner.probeRegionSessionBinding(installation, afterReadbackReference);
    if (!beforeReadbackPreimage.context().status().equals(beforeAckPreimage.context().status())) {
      throw unavailable();
    }
    prepared =
        regionLeaseOwner.prepareBridge(installation, beforeAckPreimage, afterReadbackReference);

    return regionLeaseOwner.acknowledgeBridge(
        installation, prepared, beforeAckPreimage.context(), afterReadbackReference, readback);
  }

  /**
   * Revalidates an original NEW binding through this private region owner and a fresh Entity read.
   */
  CanonicalGameplayRegionLeaseOwner.OriginalNewFinalBindingCurrentProof
      revalidateOriginalNewFinalBinding(
          CanonicalGameplayRegionLeaseOwner.Installation installation,
          CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource
              finalAdmissionSource) {
    Objects.requireNonNull(installation, "installation");
    Objects.requireNonNull(finalAdmissionSource, "finalAdmissionSource");
    CanonicalGameplayRuntimeActorReference before = readReference(installation.worldRequest);
    CanonicalGameplayRegionLeaseOwner.OriginalNewFinalBindingCurrentProof proof =
        regionLeaseOwner.revalidateOriginalNewFinalBinding(
            installation, finalAdmissionSource, before);
    CanonicalGameplayRuntimeActorReference after = readReference(installation.worldRequest);
    if (!sameReference(before, after) || !proof.matchesCurrentEntityReference(after)) {
      throw unavailable();
    }
    return proof;
  }

  private CanonicalGameplayRuntimeActorReference readReference(
      net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest request) {
    var origin = CanonicalGameplayActorOrigins.fromWorldRequest(request);
    if (origin instanceof CanonicalGameplayRuntimeActorReference.CreationOrigin) {
      if (createdReferenceClient == null) throw unavailable();
      var target = request.getExpectedTarget();
      return createdReferenceClient.read(
          PlayerCreatedActorRuntimeReferenceRequest.newBuilder()
              .setSchemaVersion(1)
              .setOrigin(request.getPlayerCreatedOrigin())
              .setTenantUuid(target.getCanonicalTenantUuid())
              .setAccountUuid(request.getCanonicalAccountUuid())
              .setPlayableStateNamespaceUuid(target.getPlayableStateNamespaceUuid())
              .setGameInstanceUuid(target.getGameInstanceId())
              .setCharacterUuid(request.getCharacterUuid())
              .setCanonicalVersionUuid(target.getCanonicalVersionUuid())
              .setRealmUuid(target.getRealmUuid())
              .setWorldSlug(target.getWorldSlug())
              .setRealmSlug(target.getRealmSlug())
              .build());
    }
    return entityReferenceClient.read(entityRequest(request));
  }

  private static ReadPreseededActorRuntimeReferenceRequest entityRequest(
      net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest request) {
    return ReadPreseededActorRuntimeReferenceRequest.newBuilder()
        .setSchemaVersion(1)
        .setAssignmentUuid(request.getAssignmentUuid())
        .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
        .setExpectedTarget(request.getExpectedTarget())
        .setCharacterUuid(request.getCharacterUuid())
        .setIntentDigest(request.getAssignmentIntentDigest())
        .build();
  }

  private static boolean sameReference(
      CanonicalGameplayRuntimeActorReference expected,
      CanonicalGameplayRuntimeActorReference actual) {
    return CanonicalGameplayRegionBindingBridgeProofs.entityReferenceDigest(expected)
        .equals(CanonicalGameplayRegionBindingBridgeProofs.entityReferenceDigest(actual));
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "Canonical first-NEW region bridge requires the exact current fenced source and readback");
  }
}
