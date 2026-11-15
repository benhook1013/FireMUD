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
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceActivationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldOriginalStartSessionActivationTerminalReadGrpcService;
import net.firedevops.firemud.worldmanagement.v1.ReadOriginalStartSessionActivationTerminalEvidenceRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadOriginalStartSessionActivationTerminalEvidenceResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Receiver denial ordering only; synthetic TLS context is not authenticated transport proof. */
class WorldOriginalStartSessionActivationTerminalReadGrpcServiceTest {
  @AfterEach
  void clearContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsWrongOrMissingAccountPeerBeforeRequestDecodeOrOwnerRead() {
    var owner = mock(WorldCanonicalInstanceActivationService.class);
    var service =
        new WorldOriginalStartSessionActivationTerminalReadGrpcService(owner, "world-runtime");

    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(
            service,
            null,
            ReadOriginalStartSessionActivationTerminalEvidenceRequest.getDefaultInstance()));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(service, peer("spiffe://firemud/ns/other-runtime/sa/account-service"), null));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(service, peer("spiffe://firemud/ns/world-runtime/sa/game-session-service"), null));
    verifyNoInteractions(owner);

    assertCode(
        Status.Code.INVALID_ARGUMENT,
        invoke(service, peer("spiffe://firemud/ns/world-runtime/sa/account-service"), null));
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsEndUserAndAmbientSqlBeforeDecodingOrOwnerRead() {
    var owner = mock(WorldCanonicalInstanceActivationService.class);
    var service =
        new WorldOriginalStartSessionActivationTerminalReadGrpcService(owner, "world-runtime");
    SessionContext.setContext("authenticated-user", java.util.List.of(), java.util.Map.of());
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(service, peer("spiffe://firemud/ns/world-runtime/sa/account-service"), null));
    SessionContext.clear();

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        invoke(service, peer("spiffe://firemud/ns/world-runtime/sa/account-service"), null));
    verifyNoInteractions(owner);
  }

  private static FailureObservation invoke(
      WorldOriginalStartSessionActivationTerminalReadGrpcService service,
      GrpcPeerIdentity peer,
      ReadOriginalStartSessionActivationTerminalEvidenceRequest request) {
    var observer = new Collector();
    Context context = Context.current();
    if (peer != null) context = context.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.readOriginalStartSessionActivationTerminalEvidence(request, observer);
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
      implements StreamObserver<ReadOriginalStartSessionActivationTerminalEvidenceResponse> {
    private Status.Code failureCode;
    private boolean failureWasStatusRuntimeException;

    @Override
    public void onNext(ReadOriginalStartSessionActivationTerminalEvidenceResponse value) {}

    @Override
    public void onError(Throwable failure) {
      failureCode = Status.fromThrowable(failure).getCode();
      failureWasStatusRuntimeException = failure instanceof StatusRuntimeException;
    }

    @Override
    public void onCompleted() {}
  }
}
