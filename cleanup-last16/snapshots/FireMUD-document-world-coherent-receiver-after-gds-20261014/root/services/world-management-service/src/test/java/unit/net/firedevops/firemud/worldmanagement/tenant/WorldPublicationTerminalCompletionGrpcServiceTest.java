package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.protobuf.UnknownFieldSet;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalCompletionGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalService;
import net.firedevops.firemud.worldmanagement.v1.CompleteVersionPublicationRequest;
import net.firedevops.firemud.worldmanagement.v1.CompleteVersionPublicationResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Receiver boundary units; positive workload identity is exercised with loopback mTLS separately.
 */
class WorldPublicationTerminalCompletionGrpcServiceTest {
  @AfterEach
  void clearCallerContext() {
    SessionContext.clear();
  }

  @Test
  void rejectsMissingWrongServiceAndWrongNamespaceBeforeDecodingOrOwnerCalls() {
    var owner = mock(WorldPublicationTerminalService.class);
    var service = new WorldPublicationTerminalCompletionGrpcService(owner, "test");
    var malformed = CompleteVersionPublicationRequest.getDefaultInstance();

    assertFailure(Status.Code.UNAUTHENTICATED, invoke(service, null, malformed));
    assertFailure(
        Status.Code.PERMISSION_DENIED,
        invoke(service, peer("test", "game-session-service"), malformed));
    assertFailure(
        Status.Code.PERMISSION_DENIED,
        invoke(service, peer("other", "game-design-service"), malformed));
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsEndUserContextBeforeDecodingOrOwnerCalls() {
    var owner = mock(WorldPublicationTerminalService.class);
    var service = new WorldPublicationTerminalCompletionGrpcService(owner, "test");
    SessionContext.setContext("authenticated-user", java.util.List.of(), java.util.Map.of());

    assertFailure(
        Status.Code.PERMISSION_DENIED,
        invoke(
            service,
            peer("test", "game-design-service"),
            CompleteVersionPublicationRequest.getDefaultInstance()));
    verifyNoInteractions(owner);
  }

  @Test
  void authenticatedMalformedAndUnknownFieldRequestsFailBeforeOwnerCalls() {
    var owner = mock(WorldPublicationTerminalService.class);
    var service = new WorldPublicationTerminalCompletionGrpcService(owner, "test");
    var peer = peer("test", "game-design-service");
    var malformed = CompleteVersionPublicationRequest.getDefaultInstance();
    var unknown =
        malformed.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build();

    assertFailure(Status.Code.INVALID_ARGUMENT, invoke(service, peer, malformed));
    assertFailure(Status.Code.INVALID_ARGUMENT, invoke(service, peer, unknown));
    verifyNoInteractions(owner);
  }

  private static void assertFailure(Status.Code expected, Observation observation) {
    assertThat(observation.code()).isEqualTo(expected);
    assertThat(observation.response()).isNull();
    assertThat(observation.completed()).isFalse();
  }

  private static Observation invoke(
      WorldPublicationTerminalCompletionGrpcService service,
      GrpcPeerIdentity peer,
      CompleteVersionPublicationRequest request) {
    var observer = new Collector();
    Context context = Context.current();
    if (peer != null) context = context.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.completeVersionPublication(request, observer);
    } finally {
      context.detach(previous);
    }
    return new Observation(
        observer.failure == null ? null : Status.fromThrowable(observer.failure).getCode(),
        observer.response,
        observer.completed);
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private record Observation(
      Status.Code code, CompleteVersionPublicationResponse response, boolean completed) {}

  private static final class Collector
      implements StreamObserver<CompleteVersionPublicationResponse> {
    private CompleteVersionPublicationResponse response;
    private Throwable failure;
    private boolean completed;

    @Override
    public void onNext(CompleteVersionPublicationResponse value) {
      response = value;
    }

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "The test collector retains the original failure for status assertions.")
    public void onError(Throwable error) {
      failure = error;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
