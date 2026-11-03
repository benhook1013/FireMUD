package net.firedevops.firemud.socialgroups.runtimecatalog;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.socialgroups.v1.ReadSocialRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.socialgroups.v1.ReadSocialRuntimeFamilyCatalogResponse;
import net.firedevops.firemud.socialgroups.v1.SocialRuntimeFamilyCatalogReadServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manual, unregistered, exact Game Session workload read. Authentication precedes decode and SQL.
 */
public final class SocialRuntimeFamilyCatalogReadGrpcService
    extends SocialRuntimeFamilyCatalogReadServiceGrpc
        .SocialRuntimeFamilyCatalogReadServiceImplBase {
  private final SocialRuntimeFamilyCatalogRepository repository;
  private final String namespace;

  public SocialRuntimeFamilyCatalogReadGrpcService(
      SocialRuntimeFamilyCatalogRepository repository, String namespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Fixed Social workload namespace required");
    }
    this.namespace = namespace;
  }

  @Override
  public void readSocialRuntimeFamilyCatalog(
      ReadSocialRuntimeFamilyCatalogRequest wire,
      StreamObserver<ReadSocialRuntimeFamilyCatalogResponse> observer) {
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
      refuse(observer, Status.FAILED_PRECONDITION, "Independent Social owner operation required");
      return;
    }
    Request request;
    try {
      request = SocialRuntimeFamilyCatalogCodec.fromRequest(wire);
    } catch (IllegalArgumentException invalid) {
      refuse(observer, Status.INVALID_ARGUMENT, "Complete bounded Social catalog request required");
      return;
    }
    if (!namespace.equals(request.targetNamespace())) {
      refuse(
          observer,
          Status.PERMISSION_DENIED,
          "Request namespace differs from authenticated workload");
      return;
    }
    ReadSocialRuntimeFamilyCatalogResponse response;
    try {
      response = SocialRuntimeFamilyCatalogCodec.toResponse(request, repository.read(request));
    } catch (SocialRuntimeFamilyCatalogRepository.CatalogConflictException
        | IllegalArgumentException invalid) {
      refuse(observer, Status.FAILED_PRECONDITION, "Social catalog is changed or inconsistent");
      return;
    } catch (org.jooq.exception.DataAccessException
        | org.springframework.dao.DataAccessException failure) {
      refuse(observer, storageStatus(failure), "Social catalog storage read failed");
      return;
    } catch (org.springframework.transaction.CannotCreateTransactionException unavailable) {
      refuse(observer, Status.UNAVAILABLE, "Social catalog storage unavailable");
      return;
    } catch (RuntimeException failure) {
      refuse(observer, Status.INTERNAL, "Social catalog read failed");
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private static Status storageStatus(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null) {
        String state = sql.getSQLState();
        if (state.startsWith("23") || state.startsWith("P0") || state.equals("55000")) {
          return Status.FAILED_PRECONDITION;
        }
        if (state.equals("40001") || state.equals("40P01")) return Status.ABORTED;
        if (state.startsWith("08") || state.startsWith("57") || state.equals("55P03")) {
          return Status.UNAVAILABLE;
        }
        if (state.startsWith("22") || state.startsWith("42") || state.startsWith("XX")) {
          return Status.INTERNAL;
        }
      }
    }
    if (failure instanceof org.springframework.dao.DataIntegrityViolationException) {
      return Status.FAILED_PRECONDITION;
    }
    if (failure instanceof org.springframework.dao.TransientDataAccessException
        || failure instanceof org.springframework.dao.DataAccessResourceFailureException
        || failure instanceof org.jooq.exception.DataAccessException) {
      return Status.UNAVAILABLE;
    }
    return Status.INTERNAL;
  }

  private static void refuse(StreamObserver<?> observer, Status status, String message) {
    observer.onError(status.withDescription(message).asRuntimeException());
  }
}
