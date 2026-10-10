package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.StaleStartSessionOperatorAttemptClaimException;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.StartSessionOperatorAttemptConflictException;
import net.firedevops.firemud.gamesession.v1.OriginalStartSessionCurrentAttemptReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionCurrentAttemptRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionCurrentAttemptResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Directly constructible, deliberately unregistered current-lease observation producer.
 *
 * <p>The response is a point-in-time snapshot. It does not establish continuous Account authority,
 * authorize execution, renew the lease, or prove admission or protection of later writes.
 */
public final class OriginalStartSessionCurrentAttemptReadGrpcService
    extends OriginalStartSessionCurrentAttemptReadServiceGrpc
        .OriginalStartSessionCurrentAttemptReadServiceImplBase {
  private final GameSessionStartSessionOperatorAttemptRepository repository;
  private final OriginalStartSessionCurrentAttemptReadWorkloadGuard workloadGuard;
  private final TransactionOperations ownerReadTransaction;

  public OriginalStartSessionCurrentAttemptReadGrpcService(
      GameSessionStartSessionOperatorAttemptRepository repository,
      PlatformTransactionManager transactionManager,
      String trustedNamespace) {
    this(repository, configuredTransaction(transactionManager), trustedNamespace);
  }

  OriginalStartSessionCurrentAttemptReadGrpcService(
      GameSessionStartSessionOperatorAttemptRepository repository,
      TransactionOperations ownerReadTransaction,
      String trustedNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.ownerReadTransaction =
        Objects.requireNonNull(ownerReadTransaction, "ownerReadTransaction");
    this.workloadGuard = new OriginalStartSessionCurrentAttemptReadWorkloadGuard(trustedNamespace);
  }

  @Override
  public void readOriginalStartSessionCurrentAttempt(
      ReadOriginalStartSessionCurrentAttemptRequest request,
      StreamObserver<ReadOriginalStartSessionCurrentAttemptResponse> responseObserver) {
    Objects.requireNonNull(responseObserver, "responseObserver");

    OriginalStartSessionCurrentAttemptEvidence.Request decoded;
    try {
      // Authenticate the peer before inspecting any request bytes.
      workloadGuard.requireAccountCurrentAttemptReadCaller();
      if (request == null) {
        throw new IllegalArgumentException(
            "Current original StartSession read request is required");
      }
      decoded = OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromRequest(request);
      workloadGuard.requireConfiguredTargetNamespace(decoded.targetNamespace());
    } catch (AdminAuthorizationException denied) {
      responseObserver.onError(
          Status.PERMISSION_DENIED.withDescription(denied.getMessage()).asRuntimeException());
      return;
    } catch (IllegalArgumentException malformed) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription(malformed.getMessage()).asRuntimeException());
      return;
    }

    try {
      requireNoAmbientTransaction();
      AttemptSnapshot snapshot =
          ownerReadTransaction.execute(status -> repository.readCurrentOriginalAttempt(decoded));
      if (snapshot == null) {
        throw new IllegalStateException(
            "Current original-attempt read transaction returned no row");
      }
      requireExactSnapshot(decoded, snapshot);
      var result =
          new OriginalStartSessionCurrentAttemptEvidence.Result(
              decoded, snapshot.leaseExpiresAt(), snapshot.accountRedemptionProjection());
      ReadOriginalStartSessionCurrentAttemptResponse response =
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(result);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (StartSessionOperatorAttemptConflictException
        | StaleStartSessionOperatorAttemptClaimException unavailable) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription(unavailable.getMessage())
              .asRuntimeException());
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Current original StartSession attempt read failed")
              .asRuntimeException());
    }
  }

  private static TransactionOperations configuredTransaction(
      PlatformTransactionManager transactionManager) {
    Objects.requireNonNull(transactionManager, "transactionManager");
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    // SELECT FOR UPDATE requires a writable transaction on PostgreSQL; this operation performs no
    // INSERT, UPDATE, lease renewal, or other durable mutation.
    transaction.setReadOnly(false);
    return transaction;
  }

  private static void requireExactSnapshot(
      OriginalStartSessionCurrentAttemptEvidence.Request request, AttemptSnapshot snapshot) {
    var tuple = request.originalTuple();
    if (!request.targetNamespace().equals(snapshot.targetNamespace())
        || !tuple.controlPlaneRequestId().equals(snapshot.controlPlaneRequestId())
        || !request.expectedOwnerAttemptId().equals(snapshot.ownerAttemptId())
        || !request.expectedOwnerMutationId().equals(snapshot.ownerMutationId())
        || request.expectedOwnerFence() != snapshot.ownerFence()
        || !OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE.equals(snapshot.phaseState())
        || !Arrays.equals(
            request.canonicalPostAuthorizationTuple(), snapshot.postAuthorizationExecutionTuple())
        || !tuple
            .preAuthorizationTuple()
            .action()
            .scope()
            .tenantId()
            .equals(snapshot.canonicalTenantId())
        || snapshot.leaseExpiresAt() == null
        || snapshot.accountRedemptionProjection() == null) {
      throw new IllegalStateException(
          "Repository returned a different or incomplete current original StartSession attempt");
    }
    StartSessionAuthorityEvidenceBundle authority =
        StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
    byte[] expectedProjection =
        new AccountRedemptionProjection(
                tuple.authorizationReferenceFingerprint(),
                tuple.authorityEvidenceBundleBytes(),
                authority.issuanceOperationId(),
                Long.parseLong(tuple.issuanceFence()))
            .canonicalBytes();
    if (!Arrays.equals(expectedProjection, snapshot.accountRedemptionProjection())) {
      throw new IllegalStateException(
          "Repository returned an Account projection different from the exact original tuple");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Current original StartSession read requires its own READ COMMITTED transaction");
    }
  }
}
