package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionProtectedActivationGrpcCodec;
import net.firedevops.firemud.worldmanagement.tenant.WorldOriginalStartSessionProtectedActivationGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldOriginalStartSessionProtectedActivationService;
import net.firedevops.firemud.worldmanagement.v1.ActivateOriginalStartSessionProtectedRequest;
import net.firedevops.firemud.worldmanagement.v1.ActivateOriginalStartSessionProtectedResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Adapter status classification and authentication ordering; synthetic peers are not mTLS proof.
 */
class WorldOriginalStartSessionProtectedActivationGrpcServiceTest {
  private static final String OWNER_DESCRIPTION =
      "World protected activation owner request could not complete";
  private static final Metadata.Key<String> PRIVATE_TRAILER =
      Metadata.Key.of("private-owner-detail", Metadata.ASCII_STRING_MARSHALLER);

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

  @Test
  void foreignOwnerStatusesAreSanitizedWhileInfrastructureStatusesRemainCanonical()
      throws Exception {
    var fixture = WorldOriginalStartSessionActivationTransportTestSupport.fixture();
    var request =
        WorldOriginalStartSessionProtectedActivationGrpcCodec.toRequest(fixture.input(), "test");
    var caller = peer("spiffe://firemud/ns/test/sa/game-session-service");
    var preserved =
        java.util.Set.of(
            Status.Code.UNAVAILABLE,
            Status.Code.DEADLINE_EXCEEDED,
            Status.Code.CANCELLED,
            Status.Code.RESOURCE_EXHAUSTED,
            Status.Code.INTERNAL,
            Status.Code.UNKNOWN);

    for (Status.Code sourceCode : Status.Code.values()) {
      var owner = mock(WorldOriginalStartSessionProtectedActivationService.class);
      var service = new WorldOriginalStartSessionProtectedActivationGrpcService(owner, "test");
      var privateCause = new IllegalStateException("private owner cause");
      var privateTrailers = new Metadata();
      privateTrailers.put(PRIVATE_TRAILER, "private owner trailer");
      when(owner.activate(any()))
          .thenThrow(
              Status.fromCode(sourceCode)
                  .withDescription("private owner description")
                  .withCause(privateCause)
                  .asRuntimeException(privateTrailers));

      var failure = invoke(service, caller, request);
      Status.Code expectedCode =
          preserved.contains(sourceCode) ? sourceCode : Status.Code.FAILED_PRECONDITION;

      assertThat(failure.failureCode()).as("source status %s", sourceCode).isEqualTo(expectedCode);
      assertThat(failure.failureDescription()).isEqualTo(OWNER_DESCRIPTION);
      assertThat(failure.failureCause()).isNull();
      assertThat(failure.failureTrailers()).isNull();
      assertThat(failure.responseCount()).isZero();
      assertThat(failure.completionCount()).isZero();
      verify(owner).activate(any());
    }
  }

  @Test
  void invalidOwnerEvidenceIsInternalAndDoesNotExposeFailureDetails() throws Exception {
    var fixture = WorldOriginalStartSessionActivationTransportTestSupport.fixture();
    var request =
        WorldOriginalStartSessionProtectedActivationGrpcCodec.toRequest(fixture.input(), "test");
    var owner = mock(WorldOriginalStartSessionProtectedActivationService.class);
    when(owner.activate(any())).thenThrow(new IllegalArgumentException("private evidence detail"));
    var service = new WorldOriginalStartSessionProtectedActivationGrpcService(owner, "test");

    var failure =
        invoke(service, peer("spiffe://firemud/ns/test/sa/game-session-service"), request);

    assertThat(failure.failureCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(failure.failureDescription())
        .isEqualTo("World protected activation owner evidence is invalid");
    assertThat(failure.failureCause()).isNull();
    assertThat(failure.failureTrailers()).isNull();
    assertThat(failure.responseCount()).isZero();
    assertThat(failure.completionCount()).isZero();
    verify(owner).activate(any());
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
    return new FailureObservation(
        observer.failureCode,
        observer.failureWasStatusRuntimeException,
        observer.failureDescription,
        observer.failureCause,
        observer.failureTrailers,
        observer.responseCount,
        observer.completionCount);
  }

  private static GrpcPeerIdentity peer(String uri) {
    return GrpcPeerIdentity.parseUri(uri).orElseThrow();
  }

  private static void assertCode(Status.Code expected, FailureObservation failure) {
    assertThat(failure.failureWasStatusRuntimeException()).isTrue();
    assertThat(failure.failureCode()).isEqualTo(expected);
  }

  private record FailureObservation(
      Status.Code failureCode,
      boolean failureWasStatusRuntimeException,
      String failureDescription,
      Throwable failureCause,
      Metadata failureTrailers,
      int responseCount,
      int completionCount) {}

  private static final class Collector
      implements StreamObserver<ActivateOriginalStartSessionProtectedResponse> {
    private Status.Code failureCode;
    private boolean failureWasStatusRuntimeException;
    private String failureDescription;
    private Throwable failureCause;
    private Metadata failureTrailers;
    private int responseCount;
    private int completionCount;

    @Override
    public void onNext(ActivateOriginalStartSessionProtectedResponse value) {
      responseCount++;
    }

    @Override
    public void onError(Throwable failure) {
      Status status = Status.fromThrowable(failure);
      failureCode = status.getCode();
      failureDescription = status.getDescription();
      failureWasStatusRuntimeException = failure instanceof StatusRuntimeException;
      failureCause = failure.getCause();
      if (failure instanceof StatusRuntimeException statusFailure) {
        failureTrailers = statusFailure.getTrailers();
      }
    }

    @Override
    public void onCompleted() {
      completionCount++;
    }
  }
}
