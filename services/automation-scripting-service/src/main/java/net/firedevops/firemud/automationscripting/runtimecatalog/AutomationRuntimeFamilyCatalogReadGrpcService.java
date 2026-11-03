package net.firedevops.firemud.automationscripting.runtimecatalog;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Objects;
import net.firedevops.firemud.automationscripting.v1.AutomationRuntimeFamilyCatalogReadServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.ReadAutomationRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.automationscripting.v1.ReadAutomationRuntimeFamilyCatalogResponse;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
      refuse(
          observer, Status.FAILED_PRECONDITION, "Independent Automation owner operation required");
      return;
    }
    Request request;
    try {
      request = AutomationRuntimeFamilyCatalogCodec.fromRequest(wire);
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
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql) {
        String state = sql.getSQLState();
        if (state == null) continue;
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
    if (failure instanceof org.jooq.exception.DataAccessException
        || failure instanceof org.springframework.dao.TransientDataAccessException
        || failure instanceof org.springframework.dao.DataAccessResourceFailureException)
      return Status.UNAVAILABLE;
    return Status.INTERNAL;
  }

  private static void refuse(StreamObserver<?> observer, Status status, String message) {
    observer.onError(status.withDescription(message).asRuntimeException());
  }
}
