package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayRosterReferenceRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered exact selection owner. Short local transactions never contain an RPC. */
public final class CanonicalGameplayRosterReferenceOwner {
  private final CanonicalGameplayRosterReferenceRepository repository;
  private final TransactionOperations transactions;
  private final CanonicalPublishedPlayerRouteReadService sourceReader;
  private final CanonicalPlayerRouteReadService routeReader;
  private final CanonicalInitialAdmissionRepository admissionRepository;
  private final net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterRecoveryClient
      recoveryClient;

  public CanonicalGameplayRosterReferenceOwner(
      CanonicalGameplayRosterReferenceRepository repository,
      TransactionOperations transactions,
      CanonicalPublishedPlayerRouteReadService sourceReader,
      CanonicalPlayerRouteReadService routeReader,
      CanonicalInitialAdmissionRepository admissionRepository,
      net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterRecoveryClient
          recoveryClient) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.sourceReader = Objects.requireNonNull(sourceReader, "sourceReader");
    this.routeReader = Objects.requireNonNull(routeReader, "routeReader");
    this.admissionRepository = Objects.requireNonNull(admissionRepository, "admissionRepository");
    this.recoveryClient = Objects.requireNonNull(recoveryClient, "recoveryClient");
  }

  public net.firedevops.firemud.gamesession.v1
          .CanonicalGameplayRosterRetirementAcknowledgementResponse
      acknowledgeRetirement(
          net.firedevops.firemud.gamesession.v1
                  .CanonicalGameplayRosterRetirementAcknowledgementRequest
              request,
          String authenticatedEntityUri) {
    requireOutsideTransaction();
    return Objects.requireNonNull(
        transactions.execute(
            ignored -> repository.acknowledgeRetirement(request, authenticatedEntityUri)));
  }

  public net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementConfirmationResponse
      confirmRetirementAcknowledgement(
          net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementConfirmationRequest
              request,
          String authenticatedEntityUri) {
    requireOutsideTransaction();
    return Objects.requireNonNull(
        transactions.execute(
            ignored ->
                repository.confirmRetirementAcknowledgement(request, authenticatedEntityUri)));
  }

  public GetCanonicalGameplayRosterReferenceResponse begin(
      GetCanonicalGameplayRosterReferenceRequest request) {
    requireOutsideTransaction();
    var retained =
        Objects.requireNonNull(transactions.execute(ignored -> repository.find(request)));
    if (retained.isPresent()) {
      var original =
          Objects.requireNonNull(transactions.execute(ignored -> repository.replay(request)));
      if (original.getCanonicalRosterReceipt().isEmpty()) {
        if (!original.getTerminalOutcome().isEmpty()) {
          throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_TERMINAL_UNISSUED");
        }
        try {
          requireCurrentTarget(request);
        } catch (CanonicalPublishedPlayerRouteReadService.StaleAuthorityException stale) {
          transactions.executeWithoutResult(ignored -> repository.completeUnissuedDenied(request));
          throw stale;
        }
      }
      return original;
    }
    var source = requireCurrentTarget(request);
    var qualified = qualifiedSnapshot(source);
    return Objects.requireNonNull(
        transactions.execute(
            ignored -> {
              var original = repository.begin(request);
              admissionRepository.lockExactCurrentOpenSnapshot(qualified);
              return original;
            }));
  }

  public GetCanonicalGameplayRosterReferenceResponse read(
      GetCanonicalGameplayRosterReferenceRequest request) {
    requireOutsideTransaction();
    var original =
        Objects.requireNonNull(transactions.execute(ignored -> repository.read(request)));
    if (original.getState()
            == net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState
                .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RESERVED
        && original.getRecoveryUuid().isEmpty()
        && original.getTerminalOutcome().isEmpty()) {
      requireCurrentTarget(request);
    }
    return original;
  }

  public GetCanonicalGameplayRosterReferenceResponse record(
      GetCanonicalGameplayRosterReferenceRequest request, CanonicalGameplayRosterResponse receipt) {
    requireOutsideTransaction();
    return Objects.requireNonNull(
        transactions.execute(ignored -> repository.record(request, receipt)));
  }

  public void reserveSelectionInput(
      GetCanonicalGameplayRosterReferenceRequest request,
      net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput input) {
    requireOutsideTransaction();
    begin(request);
    transactions.executeWithoutResult(ignored -> repository.reserveSelectionInput(request, input));
  }

  public GetCanonicalGameplayRosterReferenceResponse selectOriginal(
      GetCanonicalGameplayRosterReferenceRequest request, java.util.UUID character) {
    requireOutsideTransaction();
    return Objects.requireNonNull(
        transactions.execute(ignored -> repository.selectOriginal(request, character)));
  }

  public void recordSelectedAssignment(
      GetCanonicalGameplayRosterReferenceRequest request,
      net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentResponse
          receipt) {
    requireOutsideTransaction();
    transactions.executeWithoutResult(
        ignored -> repository.recordSelectedAssignment(request, receipt));
  }

  public void markRosterReadStarted(GetCanonicalGameplayRosterReferenceRequest request) {
    requireOutsideTransaction();
    transactions.executeWithoutResult(ignored -> repository.markRosterReadStarted(request));
  }

  public void completeSelection(
      GetCanonicalGameplayRosterReferenceRequest request,
      net.firedevops.firemud.gamesession.repository.CanonicalGameplayRosterReferenceRepository
              .SelectionOutcome
          outcome) {
    requireOutsideTransaction();
    transactions.executeWithoutResult(ignored -> repository.completeSelection(request, outcome));
  }

  /**
   * Qualifies committed owner reads before revalidating their exact rows under the target fence.
   */
  public boolean retireTarget(GetCanonicalGameplayRosterReferenceRequest request) {
    requireOutsideTransaction();
    var target =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.target(
            request);
    // The canonical route and OPEN readers require committed reads outside any transaction.
    // A second independent snapshot must agree with every owner-proof field in that route.
    var current = routeReader.readCurrentTarget(java.util.UUID.fromString(target.getTenantUuid()));
    var qualified =
        admissionRepository.readCurrentOpenSnapshotForRealm(
            request.getTargetNamespace(),
            java.util.UUID.fromString(target.getTenantUuid()),
            current.realmId());
    requireExactCurrentSnapshot(current, qualified);
    var recoveries =
        Objects.requireNonNull(
            transactions.execute(
                ignored -> {
                  repository.lockActiveTarget(request);
                  admissionRepository.lockExactCurrentOpenSnapshot(qualified);
                  return repository.beginRecoveries(request, current);
                }));
    for (var recovery : recoveries) {
      try {
        var verified = recoveryClient.read(recovery);
        transactions.executeWithoutResult(ignored -> repository.finishRecovery(verified));
      } catch (RuntimeException unavailableOrConflicting) {
        // The durable recovery marker remains protected for an exact later retry.
      }
    }
    return Boolean.TRUE.equals(
        transactions.execute(
            ignored -> {
              repository.lockActiveTarget(request);
              // This narrow local primitive locks and revalidates the actual pointer, terminal
              // attempt,
              // and catalog against the independently obtained snapshot. No suspended fence or RPC.
              admissionRepository.lockExactCurrentOpenSnapshot(qualified);
              return repository.sealTargetRetirement(request, current);
            }));
  }

  private static void requireExactCurrentSnapshot(
      CanonicalPlayableTarget route,
      CanonicalInitialAdmissionRepository.CurrentOpenSnapshot snapshot) {
    var proof = snapshot.ownerProof();
    if (!route.targetNamespace().equals(proof.targetNamespace())
        || !route.canonicalTenantId().equals(proof.canonicalTenantId())
        || !route.worldSlug().equals(proof.worldSlug())
        || !route.realmId().equals(proof.realmId())
        || !route.playableStateNamespaceId().equals(proof.playableStateNamespaceId())
        || !route.playableStateScope().equals(proof.playableStateScope())
        || !route.canonicalGameInstanceId().equals(proof.canonicalGameInstanceId())
        || !route.canonicalVersionId().equals(proof.canonicalVersionId())
        || route.catalogRevision() != proof.expectedCatalogRevision()
        || route.pointerVersion() != proof.committedPointerVersion()
        || route.activeWorldEpoch() != proof.activeLifecycleEpoch()
        || !route.initialAdmissionRequestId().equals(proof.initialAdmissionRequestId())
        || !route.initialAdmissionRequestDigest().equals(proof.requestDigest())
        || route.initialAdmissionOriginKind() != proof.originKind()
        || !Objects.equals(route.expectedPriorPointerVersion(), proof.expectedPriorPointerVersion())
        || !route.holdId().equals(proof.holdId())
        || !route.holdFence().equals(proof.holdFence())
        || !route.holdBindingDigest().equals(proof.holdBindingDigest())
        || route.auditEventId() != proof.auditEventId()
        || !route.ownerProofDigest().equals(proof.proofDigest())
        || route.ownerProofOutcome() != proof.outcome()
        || route.positiveDurableAbort() != proof.positiveDurableAbort()
        || !route.ownerProofTerminalAt().equals(proof.terminalAt())
        || !route
            .admissionPointerSnapshotDigest()
            .equals(snapshot.admissionPointerSnapshotDigest())) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_CURRENT_SOURCE_CONFLICT");
    }
  }

  private net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence
      requireCurrentTarget(GetCanonicalGameplayRosterReferenceRequest request) {
    var target =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.target(
            request);
    var selectors =
        new net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request(
            java.util.UUID.fromString(request.getOperationUuid()),
            java.util.UUID.fromString(request.getCanonicalAccountUuid()),
            request.getTargetNamespace(),
            java.util.UUID.fromString(target.getTenantUuid()),
            target.getWorldSlug(),
            java.util.UUID.fromString(target.getRealmUuid()),
            target.getRealmSlug(),
            java.util.UUID.fromString(target.getPlayableStateNamespaceUuid()),
            "SHARED",
            java.util.UUID.fromString(target.getGameInstanceUuid()),
            java.util.UUID.fromString(target.getCanonicalVersionUuid()),
            target.getCatalogRevision(),
            target.getPointerVersion(),
            target.getActiveWorldEpoch());
    // Owner readers independently reread catalog, OPEN pointer, launch binding, and sealed policy.
    // This is deliberately outside every short owner SQL transaction.
    var evidence = sourceReader.readCurrent(selectors);
    if (evidence == null
        || !selectors.equals(evidence.request())
        || !target
            .getAdmissionPointerSnapshotDigest()
            .equals(evidence.admissionPointerSnapshotDigest())
        || !("sha256:" + target.getPublishedOwnerProofDigest())
            .equals(evidence.gameSessionOwnerProof().proofDigest())
        || !("sha256:" + target.getPublishedPolicyDigest())
            .equals(evidence.selectedPolicyEvidence().policyDigest())
        || !target
            .getPublishedReleaseBundleRef()
            .equals(evidence.publishedPolicySetEvidence().publishedReleaseBundleRef())) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_SOURCE_MISMATCH");
    }
    return evidence;
  }

  private static CanonicalInitialAdmissionRepository.CurrentOpenSnapshot qualifiedSnapshot(
      net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence source) {
    var proof = source.gameSessionOwnerProof();
    var hold = proof.holdIdentity();
    var request = hold.request();
    var local =
        new net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof(
            net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof.Outcome
                .COMMITTED,
            request.initialAdmissionRequestId(),
            request.initialAdmissionRequestDigest(),
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.realmId(),
            request.playableStateNamespaceId(),
            request.playableStateScope(),
            request.canonicalGameInstanceId(),
            request.canonicalVersionId(),
            request.activeLifecycleEpoch(),
            request.expectedCatalogRevision(),
            net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind
                .valueOf(request.initialAdmissionOrigin().name()),
            request.expectedPriorPointerVersion(),
            hold.holdId(),
            hold.holdFence(),
            hold.holdBindingDigest(),
            proof.committedPointerVersion(),
            proof.auditEventId(),
            proof.proofDigest(),
            proof.positiveDurableAbort(),
            proof.terminalAt());
    return new CanonicalInitialAdmissionRepository.CurrentOpenSnapshot(
        local, source.admissionPointerSnapshotDigest());
  }

  private static void requireOutsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("CANONICAL_ROSTER_OWNER_REQUIRES_NO_AMBIENT_TRANSACTION");
    }
  }
}
