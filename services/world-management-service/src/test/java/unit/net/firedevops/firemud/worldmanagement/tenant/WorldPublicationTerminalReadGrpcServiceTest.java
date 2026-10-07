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
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalReadGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalRepository;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublicationTerminalRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublicationTerminalResponse;
import org.junit.jupiter.api.Test;

class WorldPublicationTerminalReadGrpcServiceTest {
  @Test
  void rejectsWrongOrMissingAccountPeerBeforeDecodingOrOwnerRead() {
    var repository = mock(WorldPublicationTerminalRepository.class);
    var service = new WorldPublicationTerminalReadGrpcService(repository, "test");
    var malformed =
        ReadWorldPublicationTerminalRequest.newBuilder()
            .setCanonicalRequestBytes(ByteString.copyFromUtf8("malformed"))
            .build();

    Collector missing = call(service, malformed, null);
    Collector wrongService = call(service, malformed, peer("game-design-service", "test"));
    Collector wrongNamespace = call(service, malformed, peer("account-service", "other"));

    assertThat(missing.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongService.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsAuthenticatedEndUserBeforeDecodingOrOwnerRead() {
    var repository = mock(WorldPublicationTerminalRepository.class);
    var service = new WorldPublicationTerminalReadGrpcService(repository, "test");
    SessionContext.setContext("11111111-1111-4111-8111-111111111111", List.of(), Map.of());
    Collector result;
    try {
      result =
          call(
              service,
              ReadWorldPublicationTerminalRequest.getDefaultInstance(),
              peer("account-service", "test"));
    } finally {
      SessionContext.clear();
    }

    assertThat(result.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void malformedRequestFromExactAccountPeerFailsBeforeOwnerRead() {
    var repository = mock(WorldPublicationTerminalRepository.class);
    var service = new WorldPublicationTerminalReadGrpcService(repository, "test");
    var malformed =
        ReadWorldPublicationTerminalRequest.newBuilder()
            .setCanonicalRequestBytes(ByteString.copyFromUtf8("malformed"))
            .build();

    Collector result = call(service, malformed, peer("account-service", "test"));

    assertThat(result.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(repository);
  }

  @Test
  void invalidConfiguredNamespaceIsRejected() {
    var repository = mock(WorldPublicationTerminalRepository.class);

    assertThatThrownBy(() -> new WorldPublicationTerminalReadGrpcService(repository, "bad ns"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(repository);
  }

  private static Collector call(
      WorldPublicationTerminalReadGrpcService service,
      ReadWorldPublicationTerminalRequest request,
      GrpcPeerIdentity peer) {
    Collector response = new Collector();
    Context context = Context.current();
    if (peer != null) context = context.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.readWorldPublicationTerminal(request, response);
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
      implements StreamObserver<ReadWorldPublicationTerminalResponse> {
    private ReadWorldPublicationTerminalResponse value;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(ReadWorldPublicationTerminalResponse response) {
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
