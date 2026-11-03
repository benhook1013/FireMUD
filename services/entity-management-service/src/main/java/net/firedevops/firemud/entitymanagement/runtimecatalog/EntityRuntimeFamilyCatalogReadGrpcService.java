package net.firedevops.firemud.entitymanagement.runtimecatalog;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Objects;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.repository.EntityRuntimeFamilyCatalogRepository;
import net.firedevops.firemud.entitymanagement.v1.EntityRuntimeFamilyCatalogReadServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ReadEntityRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadEntityRuntimeFamilyCatalogResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Manually composed and unregistered. Workload guard precedes decoding and every SQL access. */
public final class EntityRuntimeFamilyCatalogReadGrpcService
    extends EntityRuntimeFamilyCatalogReadServiceGrpc
        .EntityRuntimeFamilyCatalogReadServiceImplBase {
  private final EntityRuntimeFamilyCatalogRepository repository;
  private final String namespace;

  public EntityRuntimeFamilyCatalogReadGrpcService(
      EntityRuntimeFamilyCatalogRepository repository, String namespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Fixed workload namespace required");
    this.namespace = namespace;
  }

  @Override
  public void readEntityRuntimeFamilyCatalog(
      ReadEntityRuntimeFamilyCatalogRequest wire,
      StreamObserver<ReadEntityRuntimeFamilyCatalogResponse> observer) {
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
      refuse(observer, Status.FAILED_PRECONDITION, "Independent Entity owner operation required");
      return;
    }
    Request request;
    try {
      request = EntityRuntimeFamilyCatalogCodec.fromRequest(wire);
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
    ReadEntityRuntimeFamilyCatalogResponse response;
    try {
      response = EntityRuntimeFamilyCatalogCodec.toResponse(request, repository.read(request));
    } catch (EntityRuntimeFamilyCatalogRepository.CatalogConflictException
        | IllegalArgumentException invalid) {
      refuse(
          observer,
          Status.FAILED_PRECONDITION,
          "Entity catalog is changed, unsupported or inconsistent");
      return;
    } catch (org.jooq.exception.DataAccessException
        | org.springframework.dao.DataAccessException failure) {
      refuse(observer, storageStatus(failure), "Entity catalog storage read failed");
      return;
    } catch (org.springframework.transaction.CannotCreateTransactionException unavailable) {
      refuse(observer, Status.UNAVAILABLE, "Entity catalog storage unavailable");
      return;
    } catch (RuntimeException failure) {
      refuse(observer, Status.INTERNAL, "Entity catalog read failed");
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private static Status storageStatus(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null) {
        String state = sql.getSQLState();
        if (state.startsWith("23") || state.startsWith("P0") || state.equals("55000"))
          return Status.FAILED_PRECONDITION;
        if (state.equals("40001") || state.equals("40P01")) return Status.ABORTED;
        if (state.startsWith("08") || state.startsWith("57") || state.equals("55P03"))
          return Status.UNAVAILABLE;
        if (state.startsWith("22") || state.startsWith("42") || state.startsWith("XX"))
          return Status.INTERNAL;
      }
    }
    if (failure instanceof org.springframework.dao.DataIntegrityViolationException)
      return Status.FAILED_PRECONDITION;
    if (failure instanceof org.springframework.dao.TransientDataAccessException
        || failure instanceof org.springframework.dao.DataAccessResourceFailureException
        || failure instanceof org.jooq.exception.DataAccessException) return Status.UNAVAILABLE;
    return Status.INTERNAL;
  }

  private static void refuse(StreamObserver<?> observer, Status status, String description) {
    observer.onError(status.withDescription(description).asRuntimeException());
  }
}
