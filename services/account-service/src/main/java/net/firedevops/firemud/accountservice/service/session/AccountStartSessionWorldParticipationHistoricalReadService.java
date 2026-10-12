package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import java.util.Objects;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationHistoricalReadEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationHistoricalReadRequest;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered lookup-only Account read for exact historical World StartSession reconciliation.
 *
 * <p>It returns retained identity only. It does not establish fresh currentness, admit or restart
 * World execution, renew an authorization reference, or prove the original Game Session operation.
 */
public final class AccountStartSessionWorldParticipationHistoricalReadService {
  private final AccountStartSessionWorldParticipationRepository repository;
  private final TransactionTemplate ownerTransaction;
  private final String workloadNamespace;

  public AccountStartSessionWorldParticipationHistoricalReadService(
      AccountStartSessionWorldParticipationRepository repository,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.repository = Objects.requireNonNull(repository, "participation repository is required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    ownerTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ownerTransaction.setReadOnly(false);
  }

  /**
   * Resolves the exact retained participation from its complete original tuple, including after
   * original ingress expiry or prior immutable terminal settlement.
   */
  public AccountStartSessionWorldParticipationHistoricalReadEvidence read(
      AccountStartSessionWorldParticipationHistoricalReadRequest request) {
    requireWorldPeer();
    requireNoAmbientTransaction();
    Objects.requireNonNull(request, "historical participation request is required");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw Status.PERMISSION_DENIED
          .withDescription(
              "Historical participation namespace differs from the configured World peer")
          .asRuntimeException();
    }

    StoredParticipation participation =
        Objects.requireNonNull(
            ownerTransaction.execute(
                ignored ->
                    repository
                        .findHistoricalForOriginalTuple(request.originalPostAuthorizationTuple())
                        .orElseThrow(
                            () ->
                                Status.FAILED_PRECONDITION
                                    .withDescription(
                                        "Exact historical World participation is unavailable")
                                    .asRuntimeException())),
            "Account historical participation transaction returned no result");

    if (!java.security.MessageDigest.isEqual(
            participation.originalPostAuthorizationTuple(),
            request.originalPostAuthorizationTuple())
        || !request
            .decodedOriginalTuple()
            .controlPlaneRequestId()
            .equals(participation.controlPlaneRequestId())
        || !workloadNamespace.equals(participation.targetNamespace())) {
      throw Status.FAILED_PRECONDITION
          .withDescription(
              "Stored World participation differs from the complete original tuple lookup")
          .asRuntimeException();
    }
    try {
      return new AccountStartSessionWorldParticipationHistoricalReadEvidence(
          request,
          participation.participationId(),
          participation.participationFence(),
          participation.gameSessionOwnerAttemptId(),
          participation.gameSessionOwnerFence(),
          participation.canonicalGameInstanceId(),
          participation.preparationInputJson(),
          participation.preparationInputDigest());
    } catch (IllegalArgumentException malformedStorage) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Stored World participation identity is not exact")
          .withCause(malformedStorage)
          .asRuntimeException();
    }
  }

  private void requireWorldPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified World workload identity required")
          .asRuntimeException();
    }
    if (SessionContext.hasAuthenticatedCallerContext()
        || !workloadNamespace.equals(peer.namespace())
        || !peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service")) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace World workload without end-user context required")
          .asRuntimeException();
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription(
              "Historical World participation read requires an independent Account transaction")
          .asRuntimeException();
    }
  }
}
