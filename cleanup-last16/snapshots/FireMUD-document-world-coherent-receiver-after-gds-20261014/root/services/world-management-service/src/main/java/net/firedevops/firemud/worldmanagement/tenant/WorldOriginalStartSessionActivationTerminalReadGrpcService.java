package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalReadGrpcCodec;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalReadInput;
import net.firedevops.firemud.worldmanagement.v1.ReadOriginalStartSessionActivationTerminalEvidenceRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadOriginalStartSessionActivationTerminalEvidenceResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldOriginalStartSessionActivationTerminalReadServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone authenticated World terminal-read adapter; intentionally not runtime-registered. */
public final class WorldOriginalStartSessionActivationTerminalReadGrpcService
    extends WorldOriginalStartSessionActivationTerminalReadServiceGrpc
        .WorldOriginalStartSessionActivationTerminalReadServiceImplBase {
  private final WorldCanonicalInstanceActivationService activationService;
  private final String trustedNamespace;

  public WorldOriginalStartSessionActivationTerminalReadGrpcService(
      WorldCanonicalInstanceActivationService activationService, String trustedNamespace) {
    this.activationService = Objects.requireNonNull(activationService, "activationService");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void readOriginalStartSessionActivationTerminalEvidence(
      ReadOriginalStartSessionActivationTerminalEvidenceRequest request,
      StreamObserver<ReadOriginalStartSessionActivationTerminalEvidenceResponse> observer) {
    if (!requireAuthenticatedAccountPeer(observer)) return;
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(
          observer,
          Status.FAILED_PRECONDITION,
          "World terminal reads require independent owner operations");
      return;
    }

    WorldOriginalStartSessionActivationTerminalReadInput input;
    try {
      input =
          WorldOriginalStartSessionActivationTerminalReadGrpcCodec.fromRequest(
              request, trustedNamespace);
    } catch (IllegalArgumentException invalid) {
      fail(
          observer,
          Status.INVALID_ARGUMENT,
          "A complete exact Account protection read is required");
      return;
    }

    WorldOriginalStartSessionActivationTerminalEvidence terminal;
    try {
      terminal = activationService.readTerminalEvidence(input.protectionEvidence()).orElse(null);
    } catch (TransientDataAccessException unavailable) {
      fail(observer, Status.UNAVAILABLE, "World activation terminal storage is unavailable");
      return;
    } catch (DataAccessException failure) {
      fail(observer, Status.INTERNAL, "World activation terminal storage failed");
      return;
    } catch (RuntimeException failure) {
      fail(observer, Status.INTERNAL, "World activation terminal read failed");
      return;
    }
    if (terminal == null) {
      fail(
          observer,
          Status.FAILED_PRECONDITION,
          "World has no exact terminal receipt; Account protection remains pending");
      return;
    }
    try {
      ReadOriginalStartSessionActivationTerminalEvidenceResponse response =
          WorldOriginalStartSessionActivationTerminalReadGrpcCodec.toResponse(input, terminal);
      observer.onNext(response);
      observer.onCompleted();
    } catch (IllegalArgumentException mismatch) {
      fail(
          observer,
          Status.FAILED_PRECONDITION,
          "World terminal receipt differs from the exact Account protection read");
    }
  }

  private boolean requireAuthenticatedAccountPeer(StreamObserver<?> observer) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    String expectedUri = "spiffe://firemud/ns/" + trustedNamespace + "/sa/account-service";
    if (peer != null
        && expectedUri.equals(peer.uri())
        && !SessionContext.hasAuthenticatedCallerContext()) {
      return true;
    }
    fail(
        observer,
        Status.PERMISSION_DENIED,
        "Only the exact same-namespace Account workload without end-user context is allowed");
    return false;
  }

  private static void fail(StreamObserver<?> observer, Status status, String description) {
    observer.onError(status.withDescription(description).asRuntimeException());
  }
}
