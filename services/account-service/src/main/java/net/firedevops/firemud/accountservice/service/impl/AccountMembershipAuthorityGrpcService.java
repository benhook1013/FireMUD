package net.firedevops.firemud.accountservice.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLTransientException;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unwired transport candidate for Account-owned runtime membership readback.
 *
 * <p>This service is deliberately not a Spring component. The active Account service continues to
 * deny {@code GetTenantMembershipForRuntime}; callers must explicitly construct this candidate with
 * the Account-owned producer and transaction manager.
 */
public final class AccountMembershipAuthorityGrpcService
    extends AccountServiceGrpc.AccountServiceImplBase {
  private static final String GAME_SESSION_SERVICE = "game-session-service";

  private final AccountMembershipAuthorityEventProducer producer;
  private final TransactionTemplate ownerTransaction;
  private final String expectedGameSessionPeerUri;
  private final CandidateEncoder candidateEncoder;

  public AccountMembershipAuthorityGrpcService(
      AccountMembershipAuthorityEventProducer producer,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this(
        producer,
        transactionManager,
        workloadNamespace,
        AccountGrpcService::encodeRuntimeMembershipCandidate);
  }

  AccountMembershipAuthorityGrpcService(
      AccountMembershipAuthorityEventProducer producer,
      PlatformTransactionManager transactionManager,
      String workloadNamespace,
      CandidateEncoder candidateEncoder) {
    this.producer = Objects.requireNonNull(producer, "membership authority producer is required");
    Objects.requireNonNull(transactionManager, "Account transaction manager is required");
    if (workloadNamespace == null || workloadNamespace.isBlank()) {
      throw new IllegalArgumentException("Account workload namespace is required");
    }
    GrpcPeerIdentity expectedPeer =
        GrpcPeerIdentity.parseUri(
                "spiffe://firemud/ns/" + workloadNamespace + "/sa/" + GAME_SESSION_SERVICE)
            .filter(identity -> workloadNamespace.equals(identity.namespace()))
            .filter(identity -> identity.isService(GAME_SESSION_SERVICE))
            .orElseThrow(
                () -> new IllegalArgumentException("Account workload namespace is invalid"));
    this.expectedGameSessionPeerUri = expectedPeer.uri();
    this.candidateEncoder =
        Objects.requireNonNull(candidateEncoder, "runtime membership encoder is required");

    this.ownerTransaction = new TransactionTemplate(transactionManager);
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerTransaction.setReadOnly(false);
  }

  @Override
  public void getTenantMembershipForRuntime(
      GetTenantMembershipForRuntimeRequest request,
      StreamObserver<GetTenantMembershipForRuntimeResponse> responseObserver) {
    if (!hasExactGameSessionPeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Verified Game Session workload identity is required")
              .asRuntimeException());
      return;
    }

    final RequestSelection selection;
    try {
      selection = validateRequest(request);
    } catch (IllegalArgumentException invalidRequest) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Runtime membership request is invalid")
              .asRuntimeException());
      return;
    }

    final GetTenantMembershipForRuntimeResponse response;
    try {
      response =
          ownerTransaction.execute(
              status -> {
                RuntimeMembershipSnapshotDto snapshot =
                    producer.readRuntimeMembershipSnapshot(
                        selection.accountId(), selection.tenantId());
                return candidateEncoder.encode(selection.playerContext(), snapshot);
              });
      if (response == null) {
        throw new IllegalStateException("Runtime membership transaction returned no response");
      }
    } catch (RuntimeException sourceFailure) {
      Status.Code code = sourceFailureCode(sourceFailure);
      String description =
          code == Status.Code.UNAVAILABLE
              ? "Runtime membership source is temporarily unavailable"
              : "Runtime membership source evidence is unavailable or contradictory";
      responseObserver.onError(
          Status.fromCode(code).withDescription(description).asRuntimeException());
      return;
    }

    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private boolean hasExactGameSessionPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    return peer != null && expectedGameSessionPeerUri.equals(peer.uri());
  }

  private static RequestSelection validateRequest(GetTenantMembershipForRuntimeRequest request) {
    if (request == null || !request.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Runtime membership request contains unknown fields");
    }

    PlayerExecutionContext playerContext = request.getPlayerContext();
    AccountGrpcService.requireCompleteRuntimePlayerContext(playerContext);
    return new RequestSelection(
        UUID.fromString(playerContext.getAccountId()),
        UUID.fromString(playerContext.getTenantId()),
        playerContext);
  }

  private static Status.Code sourceFailureCode(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof TransientDataAccessException || cause instanceof SQLTransientException) {
        return Status.Code.UNAVAILABLE;
      }
    }
    return Status.Code.FAILED_PRECONDITION;
  }

  @FunctionalInterface
  interface CandidateEncoder {
    GetTenantMembershipForRuntimeResponse encode(
        PlayerExecutionContext playerContext, RuntimeMembershipSnapshotDto snapshot);
  }

  private record RequestSelection(
      UUID accountId, UUID tenantId, PlayerExecutionContext playerContext) {
    private RequestSelection {
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(playerContext, "playerContext");
    }
  }
}
