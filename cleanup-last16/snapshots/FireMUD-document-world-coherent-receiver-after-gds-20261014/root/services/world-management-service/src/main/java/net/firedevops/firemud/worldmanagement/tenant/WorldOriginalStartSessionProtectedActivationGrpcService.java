package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionInput;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionProtectedActivationGrpcCodec;
import net.firedevops.firemud.worldmanagement.v1.ActivateOriginalStartSessionProtectedRequest;
import net.firedevops.firemud.worldmanagement.v1.ActivateOriginalStartSessionProtectedResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldOriginalStartSessionProtectedActivationServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Same-namespace authenticated adapter; deliberately not registered in production runtime wiring.
 */
public final class WorldOriginalStartSessionProtectedActivationGrpcService
    extends WorldOriginalStartSessionProtectedActivationServiceGrpc
        .WorldOriginalStartSessionProtectedActivationServiceImplBase {
  private final WorldOriginalStartSessionProtectedActivationService activationService;
  private final String trustedNamespace;

  public WorldOriginalStartSessionProtectedActivationGrpcService(
      WorldOriginalStartSessionProtectedActivationService activationService,
      String trustedNamespace) {
    this.activationService = Objects.requireNonNull(activationService, "activationService");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void activateOriginalStartSessionProtected(
      ActivateOriginalStartSessionProtectedRequest request,
      StreamObserver<ActivateOriginalStartSessionProtectedResponse> observer) {
    if (!requireAuthenticatedGameSessionPeer(observer)) return;
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(
          observer,
          Status.FAILED_PRECONDITION,
          "World protected activation requires independent owner operations");
      return;
    }

    final AccountStartSessionActivationProtectionAcquisitionInput input;
    try {
      input =
          WorldOriginalStartSessionProtectedActivationGrpcCodec.fromRequest(
              request, trustedNamespace);
    } catch (IllegalArgumentException invalid) {
      fail(
          observer,
          Status.INVALID_ARGUMENT,
          "A complete exact original activation input is required");
      return;
    }

    final WorldOriginalStartSessionActivationTerminalEvidence terminal;
    try {
      terminal = activationService.activate(input);
    } catch (WorldOriginalStartSessionProtectedActivationService.PendingActivationException
        | WorldCanonicalInstanceActivationRepository.ActivationConflictException
        | WorldCanonicalInstanceActivationService.ActivationDeniedException denied) {
      fail(observer, Status.FAILED_PRECONDITION, "World protected activation is not admissible");
      return;
    } catch (IllegalArgumentException invalid) {
      fail(observer, Status.INVALID_ARGUMENT, "World protected activation input is invalid");
      return;
    } catch (TransientDataAccessException unavailable) {
      fail(observer, Status.UNAVAILABLE, "World protected activation storage is unavailable");
      return;
    } catch (DataAccessException failure) {
      fail(observer, Status.INTERNAL, "World protected activation storage failed");
      return;
    } catch (RuntimeException failure) {
      fail(observer, Status.INTERNAL, "World protected activation failed");
      return;
    }

    try {
      var response =
          WorldOriginalStartSessionProtectedActivationGrpcCodec.toResponse(
              input, trustedNamespace, terminal);
      observer.onNext(response);
      observer.onCompleted();
    } catch (IllegalArgumentException mismatch) {
      fail(observer, Status.INTERNAL, "World protected activation terminal failed exact readback");
    }
  }

  private boolean requireAuthenticatedGameSessionPeer(StreamObserver<?> observer) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    String expectedUri = "spiffe://firemud/ns/" + trustedNamespace + "/sa/game-session-service";
    if (peer != null
        && expectedUri.equals(peer.uri())
        && !SessionContext.hasAuthenticatedCallerContext()) {
      return true;
    }
    fail(
        observer,
        Status.PERMISSION_DENIED,
        "Only the exact same-namespace Game Session workload without end-user context is allowed");
    return false;
  }

  private static void fail(StreamObserver<?> observer, Status status, String description) {
    observer.onError(status.withDescription(description).asRuntimeException());
  }
}
