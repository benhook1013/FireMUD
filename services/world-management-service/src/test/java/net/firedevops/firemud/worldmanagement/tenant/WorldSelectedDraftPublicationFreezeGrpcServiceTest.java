package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.v1.BeginVersionPublicationFreezeRequest;
import net.firedevops.firemud.worldmanagement.v1.BeginVersionPublicationFreezeResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorldSelectedDraftPublicationFreezeGrpcServiceTest {
  private static final String NAMESPACE = "firemud";

  @AfterEach
  void clearCallerContext() {
    SessionContext.clear();
  }

  @Test
  void authenticatesExactSameNamespaceGameDesignPeerBeforeParsingOrDelegating() {
    var delegate = mock(WorldSelectedDraftPublicationFreezeService.class);
    var receiver = new WorldSelectedDraftPublicationFreezeGrpcService(delegate, NAMESPACE);
    var malformed = BeginVersionPublicationFreezeRequest.getDefaultInstance();

    var anonymous = new RecordingObserver();
    withoutPeer(() -> receiver.beginVersionPublicationFreeze(malformed, anonymous));
    assertThat(Status.fromThrowable(anonymous.error).getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);

    var wrongWorkload = new RecordingObserver();
    withPeer(
        peer(NAMESPACE, "account-service"),
        () -> receiver.beginVersionPublicationFreeze(malformed, wrongWorkload));
    assertThat(Status.fromThrowable(wrongWorkload.error).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);

    var wrongNamespace = new RecordingObserver();
    withPeer(
        peer("other", "game-design-service"),
        () -> receiver.beginVersionPublicationFreeze(malformed, wrongNamespace));
    assertThat(Status.fromThrowable(wrongNamespace.error).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);

    SessionContext.setContext(
        "11111111-1111-4111-8111-111111111111", java.util.List.of(), java.util.Map.of());
    var endUserContext = new RecordingObserver();
    withPeer(
        peer(NAMESPACE, "game-design-service"),
        () -> receiver.beginVersionPublicationFreeze(malformed, endUserContext));
    assertThat(Status.fromThrowable(endUserContext.error).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    SessionContext.clear();

    var authenticatedMalformed = new RecordingObserver();
    withPeer(
        peer(NAMESPACE, "game-design-service"),
        () -> receiver.beginVersionPublicationFreeze(malformed, authenticatedMalformed));
    assertThat(Status.fromThrowable(authenticatedMalformed.error).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(delegate);
  }

  @Test
  void exactGameDesignPeerReceivesOnlyTheCompleteCommittedFreezeAcknowledgement() {
    var fixture = WorldSelectedDraftPublicationFreezeRequestTest.fixture();
    var delegate = mock(WorldSelectedDraftPublicationFreezeService.class);
    var receiver = new WorldSelectedDraftPublicationFreezeGrpcService(delegate, NAMESPACE);
    var acknowledgement =
        new WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement(
            fixture.request(),
            fixture.request().expectedVersionStateEpoch(),
            java.util.UUID.fromString("18181818-1818-4818-8818-181818181818"),
            WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase.FROZEN,
            fixture.binding().commitId().toString(),
            "a".repeat(64),
            3);
    when(delegate.begin(fixture.request())).thenReturn(acknowledgement);
    var observer = new RecordingObserver();

    withPeer(
        peer(NAMESPACE, "game-design-service"),
        () ->
            receiver.beginVersionPublicationFreeze(
                WorldSelectedDraftPublicationFreezeGrpcCodec.toRequest(fixture.request()),
                observer));

    assertThat(observer.response)
        .isEqualTo(WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(acknowledgement));
    assertThat(observer.error).isNull();
    assertThat(observer.completed).isTrue();
    verify(delegate).begin(fixture.request());
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }

  private static void withPeer(GrpcPeerIdentity peer, Runnable action) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
    }
  }

  private static void withoutPeer(Runnable action) {
    Context previous = Context.ROOT.attach();
    try {
      action.run();
    } finally {
      Context.ROOT.detach(previous);
    }
  }

  private static final class RecordingObserver
      implements StreamObserver<BeginVersionPublicationFreezeResponse> {
    BeginVersionPublicationFreezeResponse response;
    Throwable error;
    boolean completed;

    @Override
    public void onNext(BeginVersionPublicationFreezeResponse value) {
      response = value;
    }

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "The test collector retains the original throwable for assertion.")
    public void onError(Throwable failure) {
      error = failure;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
