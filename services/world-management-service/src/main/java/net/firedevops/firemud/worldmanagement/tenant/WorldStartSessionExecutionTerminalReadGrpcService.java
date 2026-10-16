package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminalReadGrpcCodec;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminalReadRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldStartSessionExecutionTerminalRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldStartSessionExecutionTerminalResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldStartSessionExecutionTerminalReadServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone Account-authenticated World terminal read; deliberately not registered at runtime. */
public final class WorldStartSessionExecutionTerminalReadGrpcService
    extends WorldStartSessionExecutionTerminalReadServiceGrpc
        .WorldStartSessionExecutionTerminalReadServiceImplBase {
  private final WorldCanonicalInstancePreparationService preparationService;
  private final String trustedNamespace;

  public WorldStartSessionExecutionTerminalReadGrpcService(
      WorldCanonicalInstancePreparationService preparationService, String trustedNamespace) {
    this.preparationService = Objects.requireNonNull(preparationService, "preparationService");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void readWorldStartSessionExecutionTerminal(
      ReadWorldStartSessionExecutionTerminalRequest request,
      StreamObserver<ReadWorldStartSessionExecutionTerminalResponse> responseObserver) {
    if (!requireAuthenticatedAccountPeer(responseObserver)) return;
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "World StartSession terminal recovery requires an independent owner operation");
      return;
    }

    final WorldStartSessionExecutionTerminalReadRequest decoded;
    try {
      decoded = WorldStartSessionExecutionTerminalReadGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException malformed) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "A complete canonical original StartSession identity and fresh read UUID are required");
      return;
    }
    if (!trustedNamespace.equals(decoded.targetNamespace())) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "World StartSession terminal namespace must match the authenticated Account workload");
      return;
    }

    final WorldCanonicalInstanceExecutionIdentity identity;
    try {
      identity =
          new WorldCanonicalInstanceExecutionIdentity(
              decoded.originalPostAuthorizationTuple(),
              decoded.accountWorldParticipationId(),
              decoded.accountWorldParticipationFence(),
              decoded.gameSessionOwnerAttemptId(),
              decoded.gameSessionOwnerFence(),
              decoded.canonicalGameInstanceId(),
              decoded.preparationInputJson());
    } catch (IllegalArgumentException malformed) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "World StartSession terminal identity does not match its original tuple");
      return;
    }

    final WorldStartSessionExecutionTerminal terminal;
    try {
      terminal = preparationService.recoverExactTerminal(identity).orElse(null);
    } catch (WorldCanonicalInstancePreparationService.PreparationDeniedException denied) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "The exact original World StartSession operation is not authenticated for recovery");
      return;
    } catch (DataAccessException | org.springframework.dao.DataAccessException unavailable) {
      fail(
          responseObserver,
          Status.UNAVAILABLE,
          "World StartSession terminal storage is temporarily unavailable");
      return;
    } catch (StatusRuntimeException ownerFailure) {
      failForOwnerRpcFailure(responseObserver, ownerFailure);
      return;
    } catch (IllegalArgumentException inconsistent) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Exact World StartSession terminal owner evidence is inconsistent");
      return;
    } catch (RuntimeException failure) {
      fail(responseObserver, Status.INTERNAL, "World StartSession terminal recovery failed");
      return;
    }
    if (terminal == null) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "World StartSession execution remains unresolved: missing or PENDING is not terminal proof");
      return;
    }

    final ReadWorldStartSessionExecutionTerminalResponse response;
    try {
      response = WorldStartSessionExecutionTerminalReadGrpcCodec.toResponse(decoded, terminal);
    } catch (IllegalArgumentException inconsistent) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Recovered World terminal differs from the complete original StartSession identity");
      return;
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private boolean requireAuthenticatedAccountPeer(StreamObserver<?> responseObserver) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      fail(
          responseObserver,
          Status.UNAUTHENTICATED,
          "Verified same-namespace Account workload identity is required");
      return false;
    }
    if (!peer.isService("account-service")
        || !peer.isInNamespace(trustedNamespace)
        || !peer.uri().equals("spiffe://firemud/ns/" + trustedNamespace + "/sa/account-service")
        || SessionContext.hasAuthenticatedCallerContext()) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Only the exact same-namespace Account workload without end-user context is allowed");
      return false;
    }
    return true;
  }

  private static void fail(StreamObserver<?> responseObserver, Status status, String description) {
    responseObserver.onError(status.withDescription(description).asRuntimeException());
  }

  private static void failForOwnerRpcFailure(
      StreamObserver<?> responseObserver, StatusRuntimeException ownerFailure) {
    switch (Status.fromThrowable(ownerFailure).getCode()) {
      case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED ->
          fail(
              responseObserver,
              Status.UNAVAILABLE,
              "A required original-operation owner read is temporarily unavailable");
      case PERMISSION_DENIED, UNAUTHENTICATED ->
          fail(
              responseObserver,
              Status.PERMISSION_DENIED,
              "A required original-operation owner denied the authenticated Account workload");
      case INVALID_ARGUMENT ->
          fail(
              responseObserver,
              Status.INVALID_ARGUMENT,
              "A required original-operation owner rejected the recovery selector");
      case FAILED_PRECONDITION, NOT_FOUND, ALREADY_EXISTS, ABORTED ->
          fail(
              responseObserver,
              Status.FAILED_PRECONDITION,
              "Required original-operation owner evidence is unavailable or inconsistent");
      default -> fail(responseObserver, Status.INTERNAL, "Original-operation owner read failed");
    }
  }
}
