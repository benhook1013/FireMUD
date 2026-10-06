package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalClient;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalCompletionGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalRepository;
import net.firedevops.firemud.worldmanagement.v1.CompleteWorldPublicationTerminalRequest;
import net.firedevops.firemud.worldmanagement.v1.CompleteWorldPublicationTerminalResponse;
import org.junit.jupiter.api.Test;

class WorldPublicationTerminalCompletionGrpcServiceTest {
  @Test
  void rejectsWrongOrMissingGameDesignPeerBeforeDecodeClientOrOwnerStorage() {
    var repository = mock(WorldPublicationTerminalRepository.class);
    var client = mock(GameDesignPublicationTerminalClient.class);
    var service = new WorldPublicationTerminalCompletionGrpcService(repository, client, "test");
    var malformed =
        CompleteWorldPublicationTerminalRequest.newBuilder()
            .setCanonicalRequestBytes(ByteString.copyFromUtf8("malformed"))
            .build();

    Collector missing = call(service, malformed, null);
    Collector wrongService = call(service, malformed, peer("account-service", "test"));
    Collector wrongNamespace = call(service, malformed, peer("game-design-service", "other"));

    assertThat(missing.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongService.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(client, repository);
  }

  @Test
  void rejectsAuthenticatedEndUserBeforeDecodeClientOrOwnerStorage() {
    var repository = mock(WorldPublicationTerminalRepository.class);
    var client = mock(GameDesignPublicationTerminalClient.class);
    var service = new WorldPublicationTerminalCompletionGrpcService(repository, client, "test");
    SessionContext.setContext("11111111-1111-4111-8111-111111111111", List.of(), Map.of());
    Collector result;
    try {
      result =
          call(
              service,
              CompleteWorldPublicationTerminalRequest.getDefaultInstance(),
              peer("game-design-service", "test"));
    } finally {
      SessionContext.clear();
    }

    assertThat(result.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(client, repository);
  }

  @Test
  void malformedRequestFromExactPeerFailsBeforeClientOrOwnerStorage() {
    var repository = mock(WorldPublicationTerminalRepository.class);
    var client = mock(GameDesignPublicationTerminalClient.class);
    var service = new WorldPublicationTerminalCompletionGrpcService(repository, client, "test");
    var malformed =
        CompleteWorldPublicationTerminalRequest.newBuilder()
            .setCanonicalRequestBytes(ByteString.copyFromUtf8("malformed"))
            .build();

    Collector result = call(service, malformed, peer("game-design-service", "test"));

    assertThat(result.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(client, repository);
  }

  @Test
  void invalidConfiguredNamespaceIsRejected() {
    var repository = mock(WorldPublicationTerminalRepository.class);
    var client = mock(GameDesignPublicationTerminalClient.class);

    assertThatThrownBy(
            () -> new WorldPublicationTerminalCompletionGrpcService(repository, client, "bad ns"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(client, repository);
  }

  private static Collector call(
      WorldPublicationTerminalCompletionGrpcService service,
      CompleteWorldPublicationTerminalRequest request,
      GrpcPeerIdentity peer) {
    Collector response = new Collector();
    Context context = Context.current();
    if (peer != null) context = context.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.completeWorldPublicationTerminal(request, response);
    } finally {
      context.detach(previous);
    }
    return response;
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static final class Collector
      implements StreamObserver<CompleteWorldPublicationTerminalResponse> {
    private CompleteWorldPublicationTerminalResponse value;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(CompleteWorldPublicationTerminalResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      assertThat(value).isNull();
      assertThat(completed).isFalse();
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
