package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Objects;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayBindingInventoryRepository;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayAdmissionDecisionReadWorkloadGuard;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionDecisionReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionResponse;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered, non-admitting Account-only read of one exact retained PROVISIONAL decision. */
public final class CanonicalGameplayAdmissionDecisionReadGrpcService
    extends CanonicalGameplayAdmissionDecisionReadServiceGrpc
        .CanonicalGameplayAdmissionDecisionReadServiceImplBase {
  private final CanonicalGameplayBindingInventoryRepository repository;
  private final CanonicalGameplayAdmissionDecisionReadWorkloadGuard workloadGuard;
  private final String workloadNamespace;

  public CanonicalGameplayAdmissionDecisionReadGrpcService(
      CanonicalGameplayBindingInventoryRepository repository,
      CanonicalGameplayAdmissionDecisionReadWorkloadGuard workloadGuard,
      String workloadNamespace) {
    this.repository = Objects.requireNonNull(repository, "Game Session decision owner required");
    this.workloadGuard =
        Objects.requireNonNull(workloadGuard, "Account-only decision-read guard required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void getCanonicalGameplayAdmissionDecision(
      GetCanonicalGameplayAdmissionDecisionRequest request,
      StreamObserver<GetCanonicalGameplayAdmissionDecisionResponse> observer) {
    Objects.requireNonNull(observer, "response observer required");
    try {
      // Authenticate before parsing or inspecting any request value.
      workloadGuard.requireAccountCaller();
    } catch (StatusRuntimeException denied) {
      fail(observer, denied.getStatus());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    }
    if (request == null
        || request.getSerializedSize()
            > CanonicalGameplayAdmissionDecisionReadGrpcCodec.MAX_REQUEST_WIRE_BYTES) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    }

    final CanonicalGameplayAdmissionDecisionReadGrpcCodec.RequestEvidence selector;
    try {
      selector = CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(request);
      var lease = selector.expectedLeaseEvidence();
      Object targetNamespace = lease.carrier().get("targetNamespace");
      Object callerWorkload = lease.carrier().get("callerWorkload");
      String expectedCaller =
          "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
      if (lease.leaseKind()
              != net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence
                  .LeaseKind.NEW_BINDING
          || !workloadNamespace.equals(targetNamespace)
          || !expectedCaller.equals(callerWorkload)) {
        fail(observer, Status.PERMISSION_DENIED);
        return;
      }
    } catch (IllegalArgumentException malformed) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    } catch (RuntimeException unexpectedParserFailure) {
      fail(observer, Status.INTERNAL);
      return;
    }

    final GetCanonicalGameplayAdmissionDecisionResponse response;
    try {
      CanonicalGameplayAdmissionDecision decision =
          repository.readAdmissionDecision(
              selector.bindingDecisionId(), selector.expectedLeaseEvidence());
      if (decision.leaseKind()
          != net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence
              .LeaseKind.NEW_BINDING) {
        fail(observer, Status.FAILED_PRECONDITION);
        return;
      }
      response =
          CanonicalGameplayAdmissionDecisionReadGrpcCodec.toResponse(
              selector,
              decision.bindingDecisionId(),
              decision.leaseEvidence(),
              decision.bindingGeneration(),
              decision.expectedOldBindingGeneration(),
              decision.resumeEpisodeId(),
              toCandidate(decision),
              toTarget(decision));
    } catch (CannotCreateTransactionException
        | DataAccessResourceFailureException
        | TransientDataAccessException unavailable) {
      fail(observer, Status.UNAVAILABLE);
      return;
    } catch (DataAccessException | org.springframework.dao.DataAccessException failure) {
      fail(observer, hasConnectionFailureSqlState(failure) ? Status.UNAVAILABLE : Status.INTERNAL);
      return;
    } catch (RuntimeException incompleteOrConflicting) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    }

    try {
      observer.onNext(response);
    } catch (RuntimeException observerFailure) {
      fail(observer, Status.INTERNAL);
      return;
    }
    // Completion is already terminal: a callback failure must not cause a second terminal signal.
    observer.onCompleted();
  }

  private static Candidate toCandidate(CanonicalGameplayAdmissionDecision decision) {
    var candidate = decision.candidate();
    return new Candidate(
        candidate.accountId(),
        candidate.tenantId(),
        candidate.playableStateNamespaceId(),
        candidate.characterId(),
        candidate.playableStateScope(),
        candidate.gameInstanceId(),
        candidate.runtimeGameInstanceId(),
        java.util.UUID.fromString(candidate.sessionId()),
        candidate.regionId(),
        candidate.regionEpoch(),
        candidate.issuerId(),
        candidate.issuerAuthGeneration(),
        candidate.issuerIndexLayoutVersion(),
        candidate.issuerIndexPartitionCount(),
        candidate.issuerIndexPartitionCapacity());
  }

  private static Target toTarget(CanonicalGameplayAdmissionDecision decision) {
    var target = decision.targetEvidence();
    return new Target(
        target.targetNamespace(),
        target.tenantSlug(),
        target.canonicalTenantId(),
        target.worldSlug(),
        target.worldDisplayName(),
        target.realmId(),
        target.realmSlug(),
        target.realmDisplayName(),
        target.gameSessionTenantId(),
        target.gameInstanceId(),
        target.playableStateNamespaceId(),
        target.playableStateScope(),
        target.canonicalGameInstanceId(),
        target.canonicalVersionId(),
        target.runtimeVersionId(),
        target.catalogRevision(),
        target.pointerVersion(),
        target.admissionPointerSnapshotDigest(),
        target.activeWorldEpoch(),
        target.initialAdmissionRequestId(),
        target.initialAdmissionRequestDigest(),
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.OriginKind.valueOf(
            target.initialAdmissionOriginKind().name()),
        target.expectedPriorPointerVersion(),
        target.holdId(),
        target.holdFence(),
        target.holdBindingDigest(),
        target.auditEventId(),
        target.ownerProofDigest(),
        OwnerProofOutcome.valueOf(target.ownerProofOutcome().name()),
        target.positiveDurableAbort(),
        target.ownerProofTerminalAt(),
        target.characterCreationPolicy());
  }

  private static boolean hasConnectionFailureSqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException exception) {
        String sqlState = exception.getSQLState();
        if (sqlState != null && sqlState.startsWith("08")) return true;
      }
    }
    return false;
  }

  private static void fail(StreamObserver<?> observer, Status status) {
    observer.onError(
        Status.fromCode(status.getCode())
            .withDescription("Canonical Game Session admission decision is unavailable")
            .asRuntimeException());
  }
}
