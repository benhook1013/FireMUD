package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.tenant.WorldOriginalStartSessionProtectedActivationGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldOriginalStartSessionProtectedActivationService;
import net.firedevops.firemud.worldmanagement.v1.ActivateOriginalStartSessionProtectedRequest;
import net.firedevops.firemud.worldmanagement.v1.ActivateOriginalStartSessionProtectedResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Adapter authentication ordering only; synthetic peer contexts are not physical mTLS proof. */
class WorldOriginalStartSessionProtectedActivationGrpcServiceTest {
  @AfterEach
  void clearContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsWrongOrMissingGameSessionPeerBeforeDecodeOrOwnerCalls() {
    var owner = mock(WorldOriginalStartSessionProtectedActivationService.class);
    var service =
        new WorldOriginalStartSessionProtectedActivationGrpcService(owner, "world-runtime");
    var malformed = ActivateOriginalStartSessionProtectedRequest.getDefaultInstance();

    assertCode(Status.Code.PERMISSION_DENIED, invoke(service, null, malformed));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(
            service, peer("spiffe://firemud/ns/other-runtime/sa/game-session-service"), malformed));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(service, peer("spiffe://firemud/ns/world-runtime/sa/account-service"), malformed));
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsEndUserAndAmbientTransactionBeforeDecodeOrOwnerCalls() {
    var owner = mock(WorldOriginalStartSessionProtectedActivationService.class);
    var service =
        new WorldOriginalStartSessionProtectedActivationGrpcService(owner, "world-runtime");
    var caller = peer("spiffe://firemud/ns/world-runtime/sa/game-session-service");
    var malformed = ActivateOriginalStartSessionProtectedRequest.getDefaultInstance();

    SessionContext.setContext("authenticated-user", java.util.List.of(), java.util.Map.of());
    assertCode(Status.Code.PERMISSION_DENIED, invoke(service, caller, malformed));
    SessionContext.clear();

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(Status.Code.FAILED_PRECONDITION, invoke(service, caller, malformed));
    verifyNoInteractions(owner);
  }

  @Test
  void exactWorkloadWithMalformedCarrierFailsBeforeOwnerOperation() {
    var owner = mock(WorldOriginalStartSessionProtectedActivationService.class);
    var service =
        new WorldOriginalStartSessionProtectedActivationGrpcService(owner, "world-runtime");

    assertCode(
        Status.Code.INVALID_ARGUMENT,
        invoke(
            service,
            peer("spiffe://firemud/ns/world-runtime/sa/game-session-service"),
            ActivateOriginalStartSessionProtectedRequest.getDefaultInstance()));
    verifyNoInteractions(owner);
  }

  private static FailureObservation invoke(
      WorldOriginalStartSessionProtectedActivationGrpcService service,
      GrpcPeerIdentity peer,
      ActivateOriginalStartSessionProtectedRequest request) {
    var observer = new Collector();
    Context context = Context.current();
    if (peer != null) context = context.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.activateOriginalStartSessionProtected(request, observer);
    } finally {
      context.detach(previous);
    }
    return new FailureObservation(observer.failureCode, observer.failureWasStatusRuntimeException);
  }

  private static GrpcPeerIdentity peer(String uri) {
    return GrpcPeerIdentity.parseUri(uri).orElseThrow();
  }

  private static void assertCode(Status.Code expected, FailureObservation failure) {
    assertThat(failure.failureWasStatusRuntimeException()).isTrue();
    assertThat(failure.failureCode()).isEqualTo(expected);
  }

  private record FailureObservation(
      Status.Code failureCode, boolean failureWasStatusRuntimeException) {}

  private static final class Collector
      implements StreamObserver<ActivateOriginalStartSessionProtectedResponse> {
    private Status.Code failureCode;
    private boolean failureWasStatusRuntimeException;

    @Override
    public void onNext(ActivateOriginalStartSessionProtectedResponse value) {}

    @Override
    public void onError(Throwable failure) {
      failureCode = Status.fromThrowable(failure).getCode();
      failureWasStatusRuntimeException = failure instanceof StatusRuntimeException;
    }

    @Override
    public void onCompleted() {}
  }
}
