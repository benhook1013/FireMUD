package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadGrpcCodec;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.v1.ReadSelectedOwnerWorldInventoryRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadSelectedOwnerWorldInventoryResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldSelectedOwnerInventoryReadServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered recipient-specific gRPC boundary; authenticates before parsing the request. */
public final class WorldSelectedOwnerInventoryReadGrpcService
    extends WorldSelectedOwnerInventoryReadServiceGrpc
        .WorldSelectedOwnerInventoryReadServiceImplBase {
  private final String trustedNamespace;
  private final WorldSelectedOwnerInventoryReadService readService;

  public WorldSelectedOwnerInventoryReadGrpcService(
      String trustedNamespace, WorldSelectedOwnerInventoryReadService readService) {
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
    this.readService = Objects.requireNonNull(readService, "readService");
  }

  @Override
  public void readSelectedOwnerWorldInventory(
      ReadSelectedOwnerWorldInventoryRequest request,
      StreamObserver<ReadSelectedOwnerWorldInventoryResponse> responseObserver) {
    if (!requireAuthenticatedOwnerPeer(responseObserver)) return;
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "World owner inventory read requires an independent owner operation");
      return;
    }

    final SelectedOwnerWorldInventoryReadEvidence.Request decoded;
    try {
      decoded = SelectedOwnerWorldInventoryReadGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException invalid) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "A complete bounded selected-owner World inventory request is required");
      return;
    }
    if (!trustedNamespace.equals(decoded.targetNamespace())
        || !peerMatchesOwner(decoded.authorizationBinding().owner())) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "World inventory namespace and owner must match the authenticated recipient");
      return;
    }

    final ReadSelectedOwnerWorldInventoryResponse response;
    try {
      var evidence = readService.read(decoded);
      response = SelectedOwnerWorldInventoryReadGrpcCodec.toResponse(decoded, evidence.inventory());
    } catch (WorldSelectedOwnerInventoryReadService.ReadFailure failure) {
      fail(responseObserver, Status.fromCode(failure.statusCode()), failure.description());
      return;
    } catch (StatusRuntimeException failure) {
      Status status =
          failure.getStatus().getCode() == Status.Code.FAILED_PRECONDITION
              ? Status.FAILED_PRECONDITION
              : Status.UNAVAILABLE;
      fail(
          responseObserver,
          status,
          status.getCode() == Status.Code.FAILED_PRECONDITION
              ? "Exact retained World artifact inventory is unavailable"
              : "World selected inventory owner storage is unavailable");
      return;
    } catch (SecurityException denied) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Exact same-namespace selected-owner workload is required");
      return;
    } catch (WorldDesignPublicationFenceRepository.ConflictException
        | IllegalArgumentException inconsistent) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Exact retained World artifact inventory is unavailable");
      return;
    } catch (org.springframework.dao.DataAccessException
        | org.jooq.exception.DataAccessException unavailable) {
      fail(
          responseObserver,
          Status.UNAVAILABLE,
          "World selected inventory owner storage is unavailable");
      return;
    } catch (RuntimeException failure) {
      fail(
          responseObserver,
          Status.UNAVAILABLE,
          "World selected-owner authorization or inventory read failed");
      return;
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private boolean requireAuthenticatedOwnerPeer(StreamObserver<?> responseObserver) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      fail(
          responseObserver,
          Status.UNAUTHENTICATED,
          "Verified Entity or Automation workload required");
      return false;
    }
    boolean entity = isExpectedPeer(peer, "entity-management-service");
    boolean automation = isExpectedPeer(peer, "automation-scripting-service");
    if ((entity || automation) && !SessionContext.hasAuthenticatedCallerContext()) return true;
    fail(
        responseObserver,
        Status.PERMISSION_DENIED,
        "Only a same-namespace Entity or Automation workload without end-user context is allowed");
    return false;
  }

  private boolean isExpectedPeer(GrpcPeerIdentity peer, String service) {
    String expected = "spiffe://firemud/ns/" + trustedNamespace + "/sa/" + service;
    return expected.equals(peer.uri())
        && peer.isService(service)
        && peer.isInNamespace(trustedNamespace);
  }

  private boolean peerMatchesOwner(Owner owner) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    String expectedService = ownerService(owner);
    return expectedService != null && peer != null && isExpectedPeer(peer, expectedService);
  }

  private static String ownerService(Owner owner) {
    return switch (owner) {
      case ENTITY_MANAGEMENT -> "entity-management-service";
      case AUTOMATION_SCRIPTING -> "automation-scripting-service";
      default -> null;
    };
  }

  private static void fail(StreamObserver<?> observer, Status status, String description) {
    observer.onError(status.withDescription(description).asRuntimeException());
  }
}
