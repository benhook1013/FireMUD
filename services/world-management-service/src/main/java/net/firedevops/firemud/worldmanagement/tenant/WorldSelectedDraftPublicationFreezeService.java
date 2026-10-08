package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadClient;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadEvidence;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadClient;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadEvidence;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.Checkpoint;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.FrozenAttempt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Internal, unregistered composition for freezing one exact selected fresh World Draft graph.
 *
 * <p>It does not create a publication order, settle/release Account source participation, expose an
 * RPC, or claim a terminal publication result. The Account HELD read is only a prerequisite to the
 * World commit; applicable Account source writers remain protected by the retained order and this
 * service has no release path.
 */
final class WorldSelectedDraftPublicationFreezeService {
  private final String workloadNamespace;
  private final AuthoredDraftPublishSelectionReadClient selectionReadClient;
  private final AuthoredWorldVersionStateClient versionStateClient;
  private final AccountPublicationAuthorizationReadClient accountReadClient;
  private final WorldAuthoredSourceIntakeRepository intakeRepository;
  private final WorldDesignPublicationFenceRepository fence;
  private final WorldSelectedDraftPublicationCheckpointRepository checkpointRepository;
  private final WorldSelectedDraftPublicationAuthorizationRepository authorizationRepository;
  private final TransactionTemplate ownerTransaction;

  WorldSelectedDraftPublicationFreezeService(
      String workloadNamespace,
      AuthoredDraftPublishSelectionReadClient selectionReadClient,
      AuthoredWorldVersionStateClient versionStateClient,
      AccountPublicationAuthorizationReadClient accountReadClient,
      WorldAuthoredSourceIntakeRepository intakeRepository,
      WorldDesignPublicationFenceRepository fence,
      WorldSelectedDraftPublicationCheckpointRepository checkpointRepository,
      WorldSelectedDraftPublicationAuthorizationRepository authorizationRepository,
      PlatformTransactionManager transactionManager) {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.selectionReadClient = Objects.requireNonNull(selectionReadClient, "selectionReadClient");
    this.versionStateClient = Objects.requireNonNull(versionStateClient, "versionStateClient");
    this.accountReadClient = Objects.requireNonNull(accountReadClient, "accountReadClient");
    this.intakeRepository = Objects.requireNonNull(intakeRepository, "intakeRepository");
    this.fence = Objects.requireNonNull(fence, "fence");
    this.checkpointRepository =
        Objects.requireNonNull(checkpointRepository, "checkpointRepository");
    this.authorizationRepository =
        Objects.requireNonNull(authorizationRepository, "authorizationRepository");
    this.ownerTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerTransaction.setReadOnly(false);
  }

  /**
   * Freezes and returns only the exact committed World checkpoint and Account-order correlation.
   *
   * <p>The supplied plan and Account binding are exact lookup identities, not authority. On a
   * committed retry the immutable World attempt and Account correlation are checked before any
   * mutable Game Design state or remote Account HELD read is repeated.
   */
  FrozenAttempt freeze(
      WorldDesignPublicationFenceEvidence evidence,
      WorldDraftTopologyCommitPlan selectedPlan,
      AccountPublicationAuthorizationBinding accountBinding) {
    requireAuthenticatedGameDesignCaller();
    requireNoAmbientTransaction();
    if (!workloadNamespace.equals(evidence.targetNamespace())) {
      throw new SecurityException(
          "World selected-publication freeze target namespace differs from this workload");
    }
    validateRelation(evidence, selectedPlan, accountBinding);

    Optional<FrozenAttempt> prior = fence.readAttempt(evidence);
    if (prior.isPresent()) {
      FrozenAttempt stored = prior.orElseThrow();
      requireCheckpointMatchesSelection(stored, selectedPlan, accountBinding.input().selection());
      AccountPublicationAuthorizationBinding retained =
          authorizationRepository
              .readCommitted(stored)
              .orElseThrow(
                  () ->
                      conflict(
                          "World freeze attempt has no original Account publication qualification"));
      requireSameAccountBinding(accountBinding, retained);
      return stored;
    }

    WorldAuthoredSourceIntakeReceipt intake = readExactIntake(evidence);
    requireSelectedDraft(intake, evidence, accountBinding.input().selection());
    requireCurrentDraftState(intake, evidence, accountBinding.input().selection());
    requireAccountHeld(accountBinding);

    AtomicBoolean attemptCreatedInTransaction = new AtomicBoolean();
    FrozenAttempt transactionResult =
        Objects.requireNonNull(
            ownerTransaction.execute(
                status -> {
                  if (!TransactionSynchronizationManager.isActualTransactionActive()
                      || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                    throw new IllegalStateException(
                        "World selected-publication freeze requires a writable owner transaction");
                  }
                  FrozenAttempt attempt =
                      fence.claimFreeze(
                          evidence,
                          () -> {
                            Checkpoint checkpoint =
                                checkpointRepository.capture(evidence, selectedPlan);
                            attemptCreatedInTransaction.set(true);
                            return checkpoint;
                          });
                  AccountPublicationAuthorizationBinding retained =
                      authorizationRepository.retainOrRequireExact(
                          attempt, accountBinding, attemptCreatedInTransaction.get());
                  requireSameAccountBinding(accountBinding, retained);
                  requireCheckpointMatchesSelection(
                      attempt, selectedPlan, accountBinding.input().selection());
                  return attempt;
                }),
            "World selected-publication freeze transaction did not commit");

    // Acknowledgement follows the owner transaction's commit and exact immutable readback.
    FrozenAttempt committed =
        fence
            .readAttempt(evidence)
            .orElseThrow(
                () ->
                    conflict("Committed World selected-publication freeze has no exact readback"));
    if (!transactionResult.equals(committed)) {
      throw conflict("Committed World freeze differs from its owner-transaction result");
    }
    AccountPublicationAuthorizationBinding retained =
        authorizationRepository
            .readCommitted(committed)
            .orElseThrow(
                () ->
                    conflict(
                        "Committed World freeze has no exact Account publication qualification"));
    requireSameAccountBinding(accountBinding, retained);
    requireCheckpointMatchesSelection(committed, selectedPlan, accountBinding.input().selection());
    return committed;
  }

  private void requireSelectedDraft(
      WorldAuthoredSourceIntakeReceipt intake,
      WorldDesignPublicationFenceEvidence evidence,
      AuthoredDraftPublishSelectionBinding suppliedSelection) {
    AuthoredDraftPublishSelectionReadEvidence.Request request =
        AuthoredDraftPublishSelectionReadEvidence.Request.create(
            workloadNamespace, suppliedSelection);
    AuthoredDraftPublishSelectionReadEvidence read = selectionReadClient.read(request);
    if (read == null
        || !request.equals(read.request())
        || !Arrays.equals(request.originalSelection(), suppliedSelection.canonicalBytes())
        || !request.selectionDigest().equals(suppliedSelection.digest())
        || !read.request().binding().equals(suppliedSelection)) {
      throw conflict("Game Design selected Draft read differs from the exact Account order");
    }
    requireSelectionOwnerBinding(evidence, suppliedSelection);
    if (!intake.targetNamespace().equals(workloadNamespace)
        || !intake.canonicalTenantId().equals(evidence.canonicalTenantId())
        || !intake.worldSlug().equals(intake.source().worldSlug())
        || !intake.sourceOperationId().equals(evidence.sourceOperationId())
        || !intake.sourceEvidenceDigest().equals(evidence.sourceEvidenceDigest())
        || suppliedSelection.target().sourceGameRowId() != intake.source().sourceGameRowId()
        || !suppliedSelection
            .target()
            .sourceGameTenantKey()
            .equals(intake.source().sourceGameTenantKey())
        || !suppliedSelection
            .target()
            .gameDesignVersionTenantKey()
            .equals(intake.source().sourceGameTenantKey())
        || !suppliedSelection
            .target()
            .sourceProvenanceKind()
            .equals(intake.source().provenanceKind())) {
      throw conflict("World selected Draft differs from its exact retained source intake");
    }
  }

  private void requireCurrentDraftState(
      WorldAuthoredSourceIntakeReceipt intake,
      WorldDesignPublicationFenceEvidence evidence,
      AuthoredDraftPublishSelectionBinding selection) {
    UUID readId;
    do {
      readId = UUID.randomUUID();
    } while (readId.equals(intake.intakeRequestId())
        || readId.equals(intake.operationId())
        || readId.equals(intake.sourceOperationId())
        || readId.equals(intake.source().registrationRequestId())
        || readId.equals(evidence.canonicalVersionId()));
    AuthoredWorldVersionStateEvidence.Request request =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            workloadNamespace,
            readId,
            intake.canonicalTenantId(),
            intake.worldSlug(),
            intake.sourceOperationId(),
            intake.sourceEvidenceDigest(),
            selection.target().gameDesignVersionRowId());
    AuthoredWorldVersionStateEvidence current = versionStateClient.read(request);
    try {
      current.requireValid();
    } catch (RuntimeException invalid) {
      throw new WorldDesignPublicationFenceRepository.ConflictException(
          "Game Design returned invalid current World version-state evidence");
    }
    if (!request.equals(current.request())
        || !intake.source().equals(current.sourceEvidence())
        || !evidence.canonicalVersionId().equals(current.canonicalVersionId())
        || current.versionState() != VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT
        || current.versionStateEpoch() != evidence.versionStateEpoch()
        || !selection
            .intent()
            .expectedVersionStateEpoch()
            .equals(Long.toString(current.versionStateEpoch()))) {
      throw conflict("Current Game Design Version is not the exact selected DRAFT epoch");
    }
  }

  private void requireAccountHeld(AccountPublicationAuthorizationBinding binding) {
    AccountPublicationAuthorizationReadEvidence.Request request =
        AccountPublicationAuthorizationReadEvidence.Request.create(workloadNamespace, binding);
    AccountPublicationAuthorizationReadEvidence read = accountReadClient.read(request);
    if (read == null
        || !request.equals(read.request())
        || !Arrays.equals(
            request.originalPublicationAuthorizationBinding(), binding.canonicalBytes())
        || !request
            .publicationAuthorizationDigest()
            .equals(DraftAuthorizationFenceBinding.digest(binding.canonicalBytes()))
        || !read.request().binding().operationId().equals(binding.operationId())
        || !read.request().binding().fenceId().equals(binding.fenceId())
        || !Arrays.equals(read.request().binding().canonicalBytes(), binding.canonicalBytes())) {
      throw conflict("Account HELD read differs from the exact original publication order");
    }
  }

  private WorldAuthoredSourceIntakeReceipt readExactIntake(
      WorldDesignPublicationFenceEvidence evidence) {
    WorldAuthoredSourceIntakeReceipt intake =
        intakeRepository
            .read(evidence.targetNamespace(), evidence.intakeRequestId())
            .orElseThrow(() -> conflict("World freeze requires an exact committed source intake"));
    if (!evidence
            .ownerBinding()
            .equals(
                new WorldDesignPublicationFenceEvidence.OwnerBinding(
                    intake.targetNamespace(),
                    intake.canonicalTenantId(),
                    evidence.canonicalVersionId(),
                    evidence.versionIdentityOperationId(),
                    evidence.gameDesignVersionId(),
                    intake.intakeRequestId(),
                    intake.operationId(),
                    intake.requestDigest(),
                    intake.sourceOperationId(),
                    intake.sourceEvidenceDigest(),
                    intake.receiptDigest()))
        || !"NEW_GAME_ROW".equals(intake.source().provenanceKind())) {
      throw conflict("World freeze owner differs from its exact retained fresh source intake");
    }
    return intake;
  }

  private static void validateRelation(
      WorldDesignPublicationFenceEvidence evidence,
      WorldDraftTopologyCommitPlan plan,
      AccountPublicationAuthorizationBinding account) {
    Objects.requireNonNull(evidence, "evidence");
    Objects.requireNonNull(plan, "selectedPlan");
    Objects.requireNonNull(account, "accountBinding");
    AuthoredDraftPublishSelectionBinding selection = account.input().selection();
    if (!evidence.targetNamespace().equals(plan.ownerBinding().targetNamespace())
        || !evidence.ownerBinding().equals(plan.ownerBinding())
        || !selection.intent().canonicalTenantId().equals(evidence.canonicalTenantId())
        || !account.tenantId().equals(evidence.canonicalTenantId())
        || !selection.intent().canonicalVersionId().equals(evidence.canonicalVersionId())
        || selection.target().gameDesignVersionRowId() != evidence.gameDesignVersionId()
        || !selection.intent().publishRequestId().equals(evidence.publicationRequestId())
        || !selection
            .intent()
            .expectedVersionStateEpoch()
            .equals(Long.toString(evidence.versionStateEpoch()))
        || !selection.digest().equals("sha256:" + evidence.requestDigest())
        || !PublicationDigestRequestBinding.full(
                evidence.canonicalTenantId().toString(),
                Long.toString(evidence.gameDesignVersionId()),
                evidence.publicationRequestId())
            .derivedWorkflowIdentity()
            .equals(evidence.publishWorkflowId())
        || !selection.selectedCommit().equals(plan.binding())
        || !Arrays.equals(
            selection.selectedCommit().canonicalBytes(), plan.binding().canonicalBytes())) {
      throw conflict("World freeze, selected Draft, and Account publication order are not exact");
    }
  }

  private static void requireSelectionOwnerBinding(
      WorldDesignPublicationFenceEvidence evidence,
      AuthoredDraftPublishSelectionBinding selection) {
    if (!selection.intent().canonicalTenantId().equals(evidence.canonicalTenantId())
        || !selection.intent().canonicalVersionId().equals(evidence.canonicalVersionId())
        || selection.target().gameDesignVersionRowId() != evidence.gameDesignVersionId()
        || !selection.intent().publishRequestId().equals(evidence.publicationRequestId())
        || !selection
            .intent()
            .expectedVersionStateEpoch()
            .equals(Long.toString(evidence.versionStateEpoch()))
        || !selection.digest().equals("sha256:" + evidence.requestDigest())) {
      throw conflict("Game Design selection differs from the exact World freeze request");
    }
  }

  private static void requireCheckpointMatchesSelection(
      FrozenAttempt attempt,
      WorldDraftTopologyCommitPlan plan,
      AuthoredDraftPublishSelectionBinding selection) {
    if (!attempt.request().ownerBinding().equals(plan.ownerBinding())
        || !selection.selectedCommit().equals(plan.binding())
        || !attempt.checkpoint().appliedCommitId().equals(plan.binding().commitId().toString())
        || !attempt
            .checkpoint()
            .appliedCommitId()
            .equals(selection.selectedCommit().commitId().toString())) {
      throw conflict("World frozen APPLIED checkpoint differs from the exact selected commit");
    }
  }

  private static void requireSameAccountBinding(
      AccountPublicationAuthorizationBinding supplied,
      AccountPublicationAuthorizationBinding retained) {
    if (!supplied.operationId().equals(retained.operationId())
        || !supplied.fenceId().equals(retained.fenceId())
        || !Arrays.equals(supplied.canonicalBytes(), retained.canonicalBytes())) {
      throw conflict("World freeze retry changed its exact original Account publication order");
    }
  }

  private void requireAuthenticatedGameDesignCaller() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null
        || !peer.isService("game-design-service")
        || !peer.isInNamespace(workloadNamespace)
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new SecurityException(
          "World selected-publication freeze requires the authenticated same-namespace Game Design workload");
    }
  }

  private void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "World selected-publication freeze cannot span owner reads from an ambient transaction");
    }
  }

  private static WorldDesignPublicationFenceRepository.ConflictException conflict(String message) {
    return new WorldDesignPublicationFenceRepository.ConflictException(message);
  }
}
