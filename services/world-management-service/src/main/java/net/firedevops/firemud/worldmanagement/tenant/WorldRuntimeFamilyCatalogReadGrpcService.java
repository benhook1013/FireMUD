package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldRuntimeFamilyCatalogResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldRuntimeFamilyCatalogReadServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manual, unregistered, exact Game Session workload read. Authentication precedes decode and SQL.
 */
public final class WorldRuntimeFamilyCatalogReadGrpcService
    extends WorldRuntimeFamilyCatalogReadServiceGrpc.WorldRuntimeFamilyCatalogReadServiceImplBase {
  private final WorldRuntimeFamilyCatalogRepository repository;
  private final String namespace;

  public WorldRuntimeFamilyCatalogReadGrpcService(
      WorldRuntimeFamilyCatalogRepository repository, String namespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Fixed workload namespace required");
    this.namespace = namespace;
  }

  @Override
  public void readWorldRuntimeFamilyCatalog(
      ReadWorldRuntimeFamilyCatalogRequest wire,
      StreamObserver<ReadWorldRuntimeFamilyCatalogResponse> observer) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      refuse(observer, Status.UNAUTHENTICATED, "Authenticated workload required");
      return;
    }
    if (!peer.isService("game-session-service")
        || !peer.isInNamespace(namespace)
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
      refuse(
          observer,
          Status.PERMISSION_DENIED,
          "Exact same-namespace Game Session workload required without caller context");
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      refuse(observer, Status.FAILED_PRECONDITION, "Independent World owner operation required");
      return;
    }
    Request request;
    try {
      request = WorldRuntimeFamilyCatalogCodec.fromRequest(wire);
    } catch (IllegalArgumentException invalid) {
      refuse(observer, Status.INVALID_ARGUMENT, "Complete bounded catalog request required");
      return;
    }
    if (!namespace.equals(request.targetNamespace())) {
      refuse(
          observer,
          Status.PERMISSION_DENIED,
          "Request namespace differs from authenticated workload");
      return;
    }
    ReadWorldRuntimeFamilyCatalogResponse response;
    try {
      response = WorldRuntimeFamilyCatalogCodec.toResponse(request, repository.read(request));
    } catch (WorldRuntimeFamilyCatalogRepository.CatalogConflictException
        | IllegalArgumentException invalid) {
      refuse(
          observer,
          Status.FAILED_PRECONDITION,
          "World catalog is changed, unsupported or inconsistent");
      return;
    } catch (org.jooq.exception.DataAccessException
        | org.springframework.dao.DataAccessException failure) {
      refuse(
          observer,
          Status.fromCode(WorldPreparationStorageFailureStatus.from(failure)),
          "World catalog storage read failed");
      return;
    } catch (org.springframework.transaction.CannotCreateTransactionException unavailable) {
      refuse(observer, Status.UNAVAILABLE, "World catalog storage unavailable");
      return;
    } catch (RuntimeException failure) {
      refuse(observer, Status.INTERNAL, "World catalog read failed");
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private static void refuse(StreamObserver<?> observer, Status status, String message) {
    observer.onError(status.withDescription(message).asRuntimeException());
  }
}
