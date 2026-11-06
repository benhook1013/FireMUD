package net.firedevops.firemud.entitymanagement.runtimecatalog;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogReadGuard;
import net.firedevops.firemud.entitymanagement.repository.EntityRuntimeFamilyCatalogRepository;
import net.firedevops.firemud.entitymanagement.v1.EntityRuntimeFamilyCatalogReadServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ReadEntityRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadEntityRuntimeFamilyCatalogResponse;

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
    var refusal = RuntimeFamilyCatalogReadGuard.refuseBeforeDecode(peer, namespace);
    if (refusal.isPresent()) {
      RuntimeFamilyCatalogReadGuard.Refusal reason = refusal.orElseThrow();
      String description =
          switch (reason) {
            case UNAUTHENTICATED -> "Authenticated workload required";
            case PERMISSION_DENIED ->
                "Exact same-namespace Game Session workload required without caller context";
            case FAILED_PRECONDITION -> "Independent Entity owner operation required";
          };
      refuse(observer, reason.status(), description);
      return;
    }
    Request request;
    try {
      request = EntityRuntimeFamilyCatalogCodec.fromRequest(wire);
    } catch (IllegalArgumentException invalid) {
      refuse(observer, Status.INVALID_ARGUMENT, "Complete bounded catalog request required");
      return;
    }
    if (!RuntimeFamilyCatalogReadGuard.matchesNamespace(namespace, request.targetNamespace())) {
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

  private static Status storageStatus(RuntimeException failure) {
    return net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogStorageStatus.classify(
        failure,
        failure instanceof org.jooq.exception.DataAccessException
            ? Status.UNAVAILABLE
            : Status.INTERNAL);
  }

  private static void refuse(StreamObserver<?> observer, Status status, String description) {
    observer.onError(status.withDescription(description).asRuntimeException());
  }
}
