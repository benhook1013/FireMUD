package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.FrozenAttempt;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Read-only authenticated owner composition for the immutable selected-publication inventory. */
final class WorldSelectedPublicationArtifactInventoryReadService {
  private final String workloadNamespace;
  private final WorldDesignPublicationFenceRepository fence;
  private final WorldSelectedDraftPublicationAuthorizationRepository authorizationRepository;
  private final WorldSelectedPublicationArtifactInventoryRepository inventoryRepository;

  WorldSelectedPublicationArtifactInventoryReadService(
      String workloadNamespace,
      WorldDesignPublicationFenceRepository fence,
      WorldSelectedDraftPublicationAuthorizationRepository authorizationRepository,
      WorldSelectedPublicationArtifactInventoryRepository inventoryRepository) {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.fence = Objects.requireNonNull(fence, "fence");
    this.authorizationRepository =
        Objects.requireNonNull(authorizationRepository, "authorizationRepository");
    this.inventoryRepository = Objects.requireNonNull(inventoryRepository, "inventoryRepository");
  }

  /** Reads only a committed attempt and its original Account/inventory rows; never recaptures. */
  WorldSelectedPublicationArtifactInventoryEvidence read(
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
    requireAuthenticatedGameDesignCaller();
    return readRetained(freezeEvidence);
  }

  /** Storage-only retained read for a separately authenticated recipient-specific boundary. */
  WorldSelectedPublicationArtifactInventoryEvidence readRetained(
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(freezeEvidence, "freezeEvidence");

    var request = freezeEvidence.request();
    var acknowledgement = freezeEvidence.acknowledgement();
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new SecurityException("World inventory target namespace differs from this workload");
    }
    FrozenAttempt attempt =
        fence
            .readAttemptByFence(acknowledgement.publicationFence())
            .orElseThrow(
                () -> conflict("World inventory read has no retained freeze for its fence"));
    requireAttemptMatchesAcknowledgement(attempt, freezeEvidence);

    AccountPublicationAuthorizationBinding retained =
        authorizationRepository
            .readCommitted(attempt)
            .orElseThrow(
                () -> conflict("World inventory read has no original Account qualification"));
    if (!Arrays.equals(
        request.accountPublicationAuthorizationBinding(), retained.canonicalBytes())) {
      throw conflict("World inventory read changed the original Account publication order");
    }

    WorldSelectedPublicationArtifactInventory inventory =
        inventoryRepository.readCommitted(attempt, retained);
    return WorldSelectedPublicationArtifactInventoryEvidence.fromPublicEvidence(
        freezeEvidence, inventory.publicEvidence());
  }

  private static void requireAttemptMatchesAcknowledgement(
      FrozenAttempt attempt, WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
    var request = freezeEvidence.request();
    var acknowledgement = freezeEvidence.acknowledgement();
    var retained = attempt.request();
    var owner = retained.ownerBinding();
    var selected = request.accountBinding().input().selection();
    String workflowId =
        PublicationDigestRequestBinding.full(
                request.canonicalTenantId().toString(),
                Long.toString(selected.target().gameDesignVersionRowId()),
                request.publicationRequestId())
            .derivedWorkflowIdentity();

    if (!attempt.publicationFence().equals(acknowledgement.publicationFence())
        || !owner.targetNamespace().equals(request.targetNamespace())
        || !owner.canonicalTenantId().equals(request.canonicalTenantId())
        || !owner.canonicalVersionId().equals(request.canonicalVersionId())
        || !retained.publicationRequestId().equals(request.publicationRequestId())
        || !retained.requestDigest().equals(request.requestDigest())
        || retained.versionStateEpoch() != request.expectedVersionStateEpoch()
        || !retained.publishWorkflowId().equals(workflowId)
        || !retained.intakeRequestId().equals(acknowledgement.intakeRequestId())
        || acknowledgement.versionStateEpoch() != retained.versionStateEpoch()
        || !attempt.checkpoint().appliedCommitId().equals(acknowledgement.appliedCommitId())
        || !attempt.checkpoint().contentDigest().equals(acknowledgement.contentDigest())
        || attempt.checkpoint().digestSchemaVersion() != acknowledgement.digestSchemaVersion()
        || !selected
            .selectedCommit()
            .commitId()
            .toString()
            .equals(acknowledgement.appliedCommitId())) {
      throw conflict(
          "World inventory read differs from the complete retained freeze acknowledgement");
    }
  }

  private void requireAuthenticatedGameDesignCaller() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null
        || !peer.isService("game-design-service")
        || !peer.isInNamespace(workloadNamespace)
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new SecurityException(
          "World inventory read requires the authenticated same-namespace Game Design workload");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("World inventory read requires no ambient owner transaction");
    }
  }

  private static ConflictException conflict(String message) {
    return new ConflictException(message);
  }
}
