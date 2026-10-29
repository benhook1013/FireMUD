package net.firedevops.firemud.gamesession.service;

import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadEvidence;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadInput;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.repository.AccountInitialAdmissionProtectionBindingRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered Account-purpose historical read; it performs no acquisition, renewal or mutation.
 */
public final class AccountInitialAdmissionSettlementReadService {
  private final AccountInitialAdmissionProtectionBindingRepository bindings;
  private final GameSessionStartSessionOperatorAttemptRepository originals;
  private final CanonicalInitialAdmissionRepository admissions;
  private final String namespace;

  public AccountInitialAdmissionSettlementReadService(
      AccountInitialAdmissionProtectionBindingRepository bindings,
      GameSessionStartSessionOperatorAttemptRepository originals,
      CanonicalInitialAdmissionRepository admissions,
      String namespace) {
    this.bindings = Objects.requireNonNull(bindings, "bindings");
    this.originals = Objects.requireNonNull(originals, "originals");
    this.admissions = Objects.requireNonNull(admissions, "admissions");
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Configured namespace is required");
    }
    this.namespace = namespace;
  }

  public AccountInitialAdmissionSettlementReadEvidence read(
      AccountInitialAdmissionSettlementReadInput input) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Settlement read requires independently committed owner facts");
    }
    Objects.requireNonNull(input, "input").requireTargetNamespace(namespace);
    var expected = input.protection();
    var request = expected.request();
    var hold = request.worldAdmissionHoldIdentity();
    var retained =
        bindings
            .read(namespace, hold.request().initialAdmissionRequestId())
            .orElseThrow(
                () ->
                    new SettlementUnavailableException("Exact protected admission is unavailable"));
    var protection = retained.protection();
    if (!Arrays.equals(protection.canonicalBytes(), expected.canonicalBytes())) {
      throw new SettlementUnavailableException(
          "Exact retained protection differs from the requested original");
    }
    // This existing owner read independently validates the actual original tuple and projection.
    var original =
        originals
            .readHistoricalOwnerAttempt(
                protection.request().originalTuple(),
                request.gameSessionOwnerAttemptId(),
                request.gameSessionOwnerFence())
            .orElseThrow(
                () ->
                    new SettlementUnavailableException(
                        "Original StartSession owner is unavailable"));
    if (!original.ownerMutationId().equals(request.gameSessionOwnerMutationId())
        || !original.leaseExpiresAt().equals(request.originalLeaseExpiresAt())
        || !Arrays.equals(
            original.postAuthorizationExecutionTuple(),
            request.originalPostAuthorizationTupleBytes())
        || !Arrays.equals(
            original.accountRedemptionProjection(), request.accountRedemptionProjectionBytes())) {
      throw new SettlementUnavailableException("Original StartSession owner association changed");
    }
    // Existing owner verifies the complete actual pointer/audit or positive fenced abort.
    var terminal =
        admissions
            .read(namespace, hold.request().initialAdmissionRequestId())
            .orElseThrow(
                () -> new SettlementUnavailableException("Admission terminal is unavailable"));
    if (terminal.outcome()
            == net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof.Outcome
                .PENDING
        || !retained.terminalProofDigest().equals(terminal.proofDigest())) {
      throw new SettlementUnavailableException(
          "Admission has no exact immutable protected terminal");
    }
    // The binding repository compared every stored operation field with this retained hold; compare
    // its actual readback again so a changed/substituted owner result cannot enter the response.
    var w = hold.request();
    if (!terminal.targetNamespace().equals(w.targetNamespace())
        || !terminal.initialAdmissionRequestId().equals(w.initialAdmissionRequestId())
        || !terminal.requestDigest().equals(w.initialAdmissionRequestDigest())
        || !terminal.canonicalTenantId().equals(w.canonicalTenantId())
        || !terminal.worldSlug().equals(w.worldSlug())
        || !terminal.realmId().equals(w.realmId())
        || !terminal.playableStateNamespaceId().equals(w.playableStateNamespaceId())
        || !terminal.playableStateScope().equals(w.playableStateScope())
        || !terminal.canonicalGameInstanceId().equals(w.canonicalGameInstanceId())
        || !terminal.canonicalVersionId().equals(w.canonicalVersionId())
        || terminal.activeLifecycleEpoch() != w.activeLifecycleEpoch()
        || terminal.expectedCatalogRevision() != w.expectedCatalogRevision()
        || !terminal.originKind().name().equals(w.initialAdmissionOrigin().name())
        || !Objects.equals(terminal.expectedPriorPointerVersion(), w.expectedPriorPointerVersion())
        || !terminal.holdId().equals(hold.holdId())
        || !terminal.holdFence().equals(hold.holdFence())
        || !terminal.holdBindingDigest().equals(hold.holdBindingDigest())) {
      throw new SettlementUnavailableException(
          "Admission owner returned another protected operation");
    }
    var proof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            hold,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.valueOf(
                terminal.outcome().name()),
            terminal.committedPointerVersion(),
            terminal.auditEventId(),
            terminal.proofDigest(),
            terminal.positiveDurableAbort(),
            terminal.terminalAt());
    return new AccountInitialAdmissionSettlementReadEvidence(
        input.readRequestId(), protection, proof);
  }

  public static final class SettlementUnavailableException extends IllegalStateException {
    public SettlementUnavailableException(String message) {
      super(message);
    }
  }
}
