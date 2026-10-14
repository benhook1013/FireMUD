package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.protobuf.ByteString;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalReadGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalReadService;
import net.firedevops.firemud.worldmanagement.v1.ReadPublicationTerminalRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadPublicationTerminalResponse;
import org.junit.jupiter.api.Test;

class WorldPublicationTerminalReadGrpcServiceTest {
  @Test
  void authenticatesExactAccountPeerBeforeDecodingOrCallingOwner() {
    var owner = mock(WorldPublicationTerminalReadService.class);
    var service = new WorldPublicationTerminalReadGrpcService(owner, "test");
    var malformed = malformedRequest();

    Collector missingPeer = new Collector();
    service.readPublicationTerminal(malformed, missingPeer);
    assertThat(Status.fromThrowable(missingPeer.error).getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);

    Collector wrongPeer = new Collector();
    callAs(service, peer("world-management-service", "test"), malformed, wrongPeer);
    assertThat(Status.fromThrowable(wrongPeer.error).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);

    Collector wrongNamespace = new Collector();
    callAs(service, peer("account-service", "other"), malformed, wrongNamespace);
    assertThat(Status.fromThrowable(wrongNamespace.error).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner);
  }

  @Test
  void authenticatedAccountMalformedRequestFailsBeforeOwnerStorage() {
    var owner = mock(WorldPublicationTerminalReadService.class);
    var service = new WorldPublicationTerminalReadGrpcService(owner, "test");
    Collector response = new Collector();
    callAs(service, peer("account-service", "test"), malformedRequest(), response);

    assertThat(Status.fromThrowable(response.error).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(response.value).isNull();
    assertThat(response.completed).isFalse();
    verifyNoInteractions(owner);
  }

  private static ReadPublicationTerminalRequest malformedRequest() {
    return ReadPublicationTerminalRequest.newBuilder()
        .setSchemaVersion(1)
        .setTargetNamespace("test")
        .setReadRequestId(UUID.randomUUID().toString())
        .setOriginalOperation(ByteString.copyFrom(new byte[] {1}))
        .setExpectedGameDesignTerminalEvidence(ByteString.copyFrom(new byte[] {2}))
        .build();
  }

  private static void callAs(
      WorldPublicationTerminalReadGrpcService service,
      GrpcPeerIdentity peer,
      ReadPublicationTerminalRequest request,
      Collector collector) {
    var context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    var previous = context.attach();
    try {
      service.readPublicationTerminal(request, collector);
    } finally {
      context.detach(previous);
    }
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static final class Collector implements StreamObserver<ReadPublicationTerminalResponse> {
    private ReadPublicationTerminalResponse value;
    private Throwable error;
    private boolean completed;

    @Override
    public void onNext(ReadPublicationTerminalResponse response) {
      value = response;
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
