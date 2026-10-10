package net.firedevops.firemud.automationscripting.sourceintake;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.automationscripting.v1.AutomationSelectedSourceIntakeCommandServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.RetainSelectedSourceRequest;
import net.firedevops.firemud.automationscripting.v1.RetainSelectedSourceResponse;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeCommandEvidence;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeCommandGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationOrderCredentials;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered Game Design command boundary for retaining exact selected Automation inputs. */
public final class AutomationSelectedSourceIntakeCommandGrpcService
    extends AutomationSelectedSourceIntakeCommandServiceGrpc
        .AutomationSelectedSourceIntakeCommandServiceImplBase {
  private final AutomationEmptySelectedSourceIntakeService owner;
  private final String namespace;

  public AutomationSelectedSourceIntakeCommandGrpcService(
      AutomationEmptySelectedSourceIntakeService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner, "selected-source intake owner is required");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("configured namespace is invalid");
    }
    this.namespace = namespace;
  }

  @Override
  public void retainSelectedSource(
      RetainSelectedSourceRequest request, StreamObserver<RetainSelectedSourceResponse> observer) {
    try {
      requireAuthenticatedGameDesignCaller();
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }
    if (!namespace.equals(request.getTargetNamespace())) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
      return;
    }

    final AutomationSelectedSourceIntakeCommandEvidence.Request decoded;
    try {
      decoded = AutomationSelectedSourceIntakeCommandGrpcCodec.fromRequest(request);
    } catch (RuntimeException malformed) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }

    try {
      var receipt =
          owner.retain(
              decoded.targetNamespace(),
              decoded.originalAuthorizationBinding(),
              decoded.freezeEvidence());
      var evidence = new AutomationSelectedSourceIntakeCommandEvidence(decoded, receipt);
      observer.onNext(AutomationSelectedSourceIntakeCommandGrpcCodec.toResponse(evidence));
      observer.onCompleted();
    } catch (StatusRuntimeException denied) {
      observer.onError(Status.fromCode(denied.getStatus().getCode()).asRuntimeException());
    } catch (SecurityException denied) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
    } catch (AutomationEmptySelectedSourceIntakeRepository.IntakeConflictException conflict) {
      observer.onError(Status.ALREADY_EXISTS.asRuntimeException());
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
    } catch (RuntimeException failure) {
      observer.onError(Status.INTERNAL.asRuntimeException());
    }
  }

  /** Authenticate the exact same-namespace workload and reject user context before decoding. */
  private void requireAuthenticatedGameDesignCaller() {
    if (SessionContext.hasAuthenticatedCallerContext()
        || AccountSelectedPublicationOrderCredentials.CONTEXT_KEY.get() != null) {
      throw Status.PERMISSION_DENIED.asRuntimeException();
    }
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED.asRuntimeException();
    }
    if (!namespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri())) {
      throw Status.PERMISSION_DENIED.asRuntimeException();
    }
  }
}
