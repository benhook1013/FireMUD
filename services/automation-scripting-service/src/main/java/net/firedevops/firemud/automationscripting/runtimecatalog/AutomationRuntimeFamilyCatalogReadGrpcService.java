package net.firedevops.firemud.automationscripting.runtimecatalog;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.automationscripting.v1.AutomationRuntimeFamilyCatalogReadServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.ReadAutomationRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.automationscripting.v1.ReadAutomationRuntimeFamilyCatalogResponse;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogReadGuard;

/** Manual unregistered producer. Exact Game Session workload authentication precedes decode/SQL. */
public final class AutomationRuntimeFamilyCatalogReadGrpcService
    extends AutomationRuntimeFamilyCatalogReadServiceGrpc
        .AutomationRuntimeFamilyCatalogReadServiceImplBase {
  private final AutomationRuntimeFamilyCatalogRepository repository;
  private final String namespace;

  public AutomationRuntimeFamilyCatalogReadGrpcService(
      AutomationRuntimeFamilyCatalogRepository repository, String namespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Fixed workload namespace required");
    this.namespace = namespace;
  }

  @Override
  public void readAutomationRuntimeFamilyCatalog(
      ReadAutomationRuntimeFamilyCatalogRequest wire,
      StreamObserver<ReadAutomationRuntimeFamilyCatalogResponse> observer) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    var refusal = RuntimeFamilyCatalogReadGuard.refuseBeforeDecode(peer, namespace);
    if (refusal.isPresent()) {
      RuntimeFamilyCatalogReadGuard.Refusal reason = refusal.orElseThrow();
      String description =
          switch (reason) {
            case UNAUTHENTICATED -> "Authenticated workload required";
            case PERMISSION_DENIED ->
                "Exact same-namespace Game Session workload required without caller context";
            case FAILED_PRECONDITION -> "Independent Automation owner operation required";
          };
      refuse(observer, reason.status(), description);
      return;
    }
    Request request;
    try {
      request = AutomationRuntimeFamilyCatalogCodec.fromRequest(wire);
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
    ReadAutomationRuntimeFamilyCatalogResponse response;
    try {
      response = AutomationRuntimeFamilyCatalogCodec.toResponse(request, repository.read(request));
    } catch (AutomationRuntimeFamilyCatalogRepository.CatalogConflictException
        | IllegalArgumentException invalid) {
      refuse(
          observer,
          Status.FAILED_PRECONDITION,
          "Automation catalog is changed, unsupported or inconsistent");
      return;
    } catch (org.jooq.exception.DataAccessException
        | org.springframework.dao.DataAccessException failure) {
      refuse(observer, storageStatus(failure), "Automation catalog storage read failed");
      return;
    } catch (org.springframework.transaction.CannotCreateTransactionException unavailable) {
      refuse(observer, Status.UNAVAILABLE, "Automation catalog storage unavailable");
      return;
    } catch (RuntimeException failure) {
      refuse(observer, Status.INTERNAL, "Automation catalog read failed");
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
