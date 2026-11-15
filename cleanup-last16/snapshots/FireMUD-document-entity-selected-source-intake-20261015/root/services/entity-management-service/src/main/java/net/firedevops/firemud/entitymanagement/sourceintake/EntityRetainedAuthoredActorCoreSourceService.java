package net.firedevops.firemud.entitymanagement.sourceintake;

import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadClient;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Association;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered Entity consumer for Game Design's independently authenticated authored-core read.
 */
public final class EntityRetainedAuthoredActorCoreSourceService {
  private static final String ENTITY_RELEASE_SCHEMA =
      "v" + AuthoredWorldReleaseAttestationEvidence.SELECTED_FULL_SCHEMA_VERSION;

  private final String configuredNamespace;
  private final AuthoredInitialActorCoreReadClient gameDesignClient;
  private final EntityEmptySelectedSourceIntakeRepository repository;

  public EntityRetainedAuthoredActorCoreSourceService(
      String configuredNamespace,
      AuthoredInitialActorCoreReadClient gameDesignClient,
      EntityEmptySelectedSourceIntakeRepository repository) {
    if (!GrpcPeerIdentity.isValidNamespace(configuredNamespace)) {
      throw new IllegalArgumentException("Configured Entity namespace is required");
    }
    this.configuredNamespace = configuredNamespace;
    this.gameDesignClient = Objects.requireNonNull(gameDesignClient, "gameDesignClient");
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  /**
   * Obtains the exact already-pinned read directly from Game Design, then corroborates it against
   * Entity's retained selected source and the immutable Entity participant in that selected
   * release. The returned object is the unchanged public result from the authenticated client.
   */
  public AuthoredInitialActorCoreReadEvidence.ReadResult read(
      AuthoredInitialActorCoreReadEvidence.ReadRequest request) {
    Objects.requireNonNull(request, "authored-core read request is required");
    requireNoAmbientOwnerSql();

    Request associationRequest = request.associationRequest();
    if (!configuredNamespace.equals(associationRequest.targetNamespace())
        || !(associationRequest.selection() instanceof ExactReplay)) {
      throw denied("Exact same-namespace pinned Game Design read is required");
    }

    // This is a direct workload-authenticated read. No Entity owner transaction may surround it.
    AuthoredInitialActorCoreReadEvidence.ReadResult publicResult = gameDesignClient.read(request);
    if (publicResult == null || !associationRequest.equals(publicResult.request())) {
      throw denied("Game Design authored-core read differs from the exact original request");
    }
    Result associationRead = publicResult.associationRead();
    if (associationRead == null || !associationRequest.equals(associationRead.request())) {
      throw denied("Game Design association read differs from the exact original request");
    }

    Association association = associationRead.association();
    PublishedReleaseBundle release = associationRead.releaseBundle();
    if (association == null || release == null) {
      throw denied("Game Design selected association or release is unavailable");
    }
    if (release.getVersionId() <= 0L) {
      throw denied("Selected release has no canonical Version row");
    }

    // COMMITTED_EMPTY is only Entity's pre-publication source receipt. The public Game Design read
    // remains the independent authored-core evidence; this receipt only correlates its selection.
    // The repository resolves every association in a read-only repeatable-read snapshot.
    EntityEmptySelectedSourceIntakeReceipt receipt =
        repository.readPublicationScope(
            configuredNamespace, association.canonicalTenantId(), release.getVersionId());
    requireExactReleaseAndSource(associationRequest, association, release, receipt);
    requireExactProjections(publicResult.projections(), association, receipt);
    return publicResult;
  }

  private void requireExactReleaseAndSource(
      Request request,
      Association association,
      PublishedReleaseBundle release,
      EntityEmptySelectedSourceIntakeReceipt receipt) {
    if (receipt == null) {
      throw denied("Entity retained selected-source receipt is unavailable");
    }
    ExactReplay replay = (ExactReplay) request.selection();
    var authorization = receipt.authorizationBinding();
    var selected = authorization.selected();
    var target = selected.target();
    var worldRead = receipt.inputs().worldInventoryReadEvidence();
    var worldInventory = worldRead.inventory();
    var worldOwner = worldInventory.publicEvidence().ownerScope();
    var worldReceipt = association.worldReceipt();
    var worldSelection =
        worldRead.request().freezeEvidence().request().accountBinding().input().selection();

    if (!configuredNamespace.equals(receipt.targetNamespace())
        || !configuredNamespace.equals(authorization.targetNamespace())
        || authorization.owner() != Owner.ENTITY_MANAGEMENT
        || !association.canonicalTenantId().equals(receipt.canonicalTenantId())
        || !association.canonicalVersionId().equals(receipt.canonicalVersionId())
        || !association.selectedCommitId().equals(receipt.selectedCommitId())
        || !replay.canonicalVersionId().equals(receipt.canonicalVersionId())
        || !replay.selectedCommitId().equals(receipt.selectedCommitId())
        || !replay.publishWorkflowId().equals(association.publishWorkflowId())
        || !replay.associationDigest().equals(association.associationDigest())
        || !receipt.canonicalTenantId().equals(target.canonicalTenantId())
        || !receipt.canonicalVersionId().equals(target.canonicalVersionId())
        || target.gameDesignVersionRowId() != release.getVersionId()
        || !selected.commitId().equals(receipt.selectedCommitId())
        || !configuredNamespace.equals(association.targetNamespace())
        || !association.intakeRequestId().equals(worldOwner.intakeRequestId())
        || !association.worldOperationId().equals(worldOwner.intakeOperationId())
        || !association.publicationSelectionDigest().equals(worldSelection.digest())
        || !worldReceipt.targetNamespace().equals(worldOwner.targetNamespace())
        || !worldReceipt.canonicalTenantId().equals(worldOwner.canonicalTenantId())
        || !worldReceipt.intakeRequestId().equals(worldOwner.intakeRequestId())
        || !worldReceipt.operationId().equals(worldOwner.intakeOperationId())
        || !worldReceipt.sourceOperationId().equals(worldOwner.sourceOperationId())
        || !worldReceipt.sourceEvidenceDigest().equals(worldOwner.sourceEvidenceDigest())
        || !worldReceipt.receiptDigest().equals(worldOwner.intakeReceiptDigest())
        || !release.getCanonicalTenantId().equals(receipt.canonicalTenantId().toString())
        || !release.getCanonicalVersionId().equals(receipt.canonicalVersionId().toString())
        || release.getVersionId() != target.gameDesignVersionRowId()
        || !release.getPublishWorkflowId().equals(association.publishWorkflowId())
        || !ENTITY_RELEASE_SCHEMA.equals(release.getAttestationSchemaVersion())
        || !association.sourceOperationId().equals(worldOwner.sourceOperationId())
        || !association.sourceEvidenceDigest().equals(worldOwner.sourceEvidenceDigest())
        || !receipt.canonicalTenantId().equals(worldOwner.canonicalTenantId())
        || !receipt.canonicalVersionId().equals(worldOwner.canonicalVersionId())
        || !receipt
            .selectedCommitId()
            .toString()
            .equals(
                receipt
                    .inputs()
                    .worldInventoryReadEvidence()
                    .request()
                    .freezeEvidence()
                    .acknowledgement()
                    .appliedCommitId())
        || !receipt.operationId().equals(authorization.operationId())
        || !receipt.fenceId().equals(authorization.fenceId())
        || !receipt.intakeRequestId().equals(authorization.intakeRequestId())) {
      throw denied("Entity receipt differs from the exact selected source operation or release");
    }

    String expectedEntityDigest =
        EntitySelectedEmptyPublicationDigestService.contentDigest(
            receipt.canonicalTenantId(),
            target.gameDesignVersionRowId(),
            receipt.canonicalVersionId(),
            receipt.inputs().ownerSourceInventoryDeclaration().entityInventory().canonicalJson());
    List<ParticipantDigest> matchingEntityParticipants =
        release.getParticipantDigestsList().stream()
            .filter(value -> "ENTITY_MANAGEMENT".equals(value.getParticipantKey()))
            .toList();
    if (matchingEntityParticipants.size() != 1) {
      throw denied("Selected release has no unique immutable Entity participant digest");
    }
    ParticipantDigest entityParticipant = matchingEntityParticipants.getFirst();
    if (!Long.toString(target.gameDesignVersionRowId()).equals(entityParticipant.getScopeValue())
        || !receipt.selectedCommitId().toString().equals(entityParticipant.getAppliedCommitId())
        || entityParticipant.getDigestSchemaVersion()
            != EntitySelectedEmptyPublicationDigestService.DIGEST_SCHEMA_VERSION
        || !expectedEntityDigest.equals(entityParticipant.getContentDigest())
        || entityParticipant.hasAbilitySchemaDigest()) {
      throw denied("Selected release Entity digest differs from the retained Entity receipt");
    }
  }

  private static void requireExactProjections(
      List<AuthoredInitialActorCoreProjection> projections,
      Association association,
      EntityEmptySelectedSourceIntakeReceipt receipt) {
    if (projections == null) {
      throw denied("Game Design authored-core projection list is unavailable");
    }
    String selectedCommitDigest = receipt.authorizationBinding().selected().digest();
    for (AuthoredInitialActorCoreProjection projection : projections) {
      if (projection == null
          || !projection.canonicalTenantId().equals(receipt.canonicalTenantId())
          || !projection.canonicalVersionId().equals(receipt.canonicalVersionId())
          || !projection.selectedCommitId().equals(receipt.selectedCommitId())
          || !projection.selectedCommitDigest().equals(selectedCommitDigest)
          || !projection.worldSourceOperationId().equals(association.sourceOperationId())
          || !projection.worldSourceEvidenceDigest().equals(association.sourceEvidenceDigest())
          || !projection.worldSlug().equals(association.worldSlug())
          || !projection.publicationWorkflowId().equals(association.publishWorkflowId())) {
        throw denied("Authored-core projection differs from Entity's exact retained source");
      }
    }
  }

  private static void requireNoAmbientOwnerSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw denied("Entity authored-core consumption requires no ambient owner SQL");
    }
  }

  private static IllegalStateException denied(String message) {
    return new IllegalStateException(message);
  }
}
