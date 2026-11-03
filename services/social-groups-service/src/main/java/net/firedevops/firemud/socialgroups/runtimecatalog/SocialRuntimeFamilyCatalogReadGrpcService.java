package net.firedevops.firemud.socialgroups.runtimecatalog;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
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

  private static Status storageStatus(RuntimeException failure) {
    return net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogStorageStatus.classify(
        failure,
        failure instanceof org.jooq.exception.DataAccessException
            ? Status.UNAVAILABLE
            : Status.INTERNAL);
  }

  private static void refuse(StreamObserver<?> observer, Status status, String message) {
    observer.onError(status.withDescription(message).asRuntimeException());
  }
}
