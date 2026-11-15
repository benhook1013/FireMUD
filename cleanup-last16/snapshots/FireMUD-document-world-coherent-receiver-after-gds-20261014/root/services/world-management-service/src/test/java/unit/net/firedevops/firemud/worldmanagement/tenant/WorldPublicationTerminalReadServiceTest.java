package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.Status;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalReadService;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalRepository;
import org.junit.jupiter.api.Test;

class WorldPublicationTerminalReadServiceTest {
  @Test
  void rejectsMissingOrWrongAccountPeerBeforeInspectingRequestOrReadingStorage() {
    var repository = mock(WorldPublicationTerminalRepository.class);
    var service = new WorldPublicationTerminalReadService(repository, "test");

    assertThatThrownBy(() -> service.read(null))
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .extracting(failure -> Status.fromThrowable(failure).getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);

    var wrongPeer = peer("world-management-service", "test");
    var context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, wrongPeer);
    var previous = context.attach();
    try {
      assertThatThrownBy(() -> service.read(null))
          .isInstanceOf(io.grpc.StatusRuntimeException.class)
          .extracting(failure -> Status.fromThrowable(failure).getCode())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      context.detach(previous);
    }

    verifyNoInteractions(repository);
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }
}
