package unit.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.publication.AuthoredInitialActorCoreReadService;
import net.firedevops.firemud.gamedesign.publication.GrpcAuthoredInitialActorCoreReadService;
import net.firedevops.firemud.gamedesign.v1.ReadAuthoredInitialActorCoreRequest;
import net.firedevops.firemud.gamedesign.v1.ReadAuthoredInitialActorCoreResponse;
import org.junit.jupiter.api.Test;

class GrpcAuthoredInitialActorCoreReadServiceTest {
  private final AuthoredInitialActorCoreReadService owner =
      mock(AuthoredInitialActorCoreReadService.class);
  private final GrpcAuthoredInitialActorCoreReadService service =
      new GrpcAuthoredInitialActorCoreReadService(owner, "world-runtime");

  @Test
  void rejectsMissingWorkloadIdentityBeforeDecodingOrCallingOwner() {
    var observer = new Collector();
    service.readAuthoredInitialActorCore(
        ReadAuthoredInitialActorCoreRequest.getDefaultInstance(), observer);
    assertThat(observer.error).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsOtherWorkloadsBeforeDecodingOrCallingOwner() {
    var observer = invokeAs("account-service");
    assertThat(observer.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
    verifyNoInteractions(owner);
  }

  @Test
  void permitsOnlyExactSameNamespaceGameSessionAndEntityIdentities() {
    for (String workload : new String[] {"game-session-service", "entity-management-service"}) {
      var observer = invokeAs(workload);
      assertThat(observer.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(observer.value).isNull();
      assertThat(observer.completed).isFalse();
    }
    verifyNoInteractions(owner);
  }

  private Collector invokeAs(String workload) {
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/world-runtime/sa/" + workload, "world-runtime", workload);
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      var observer = new Collector();
      service.readAuthoredInitialActorCore(
          ReadAuthoredInitialActorCoreRequest.getDefaultInstance(), observer);
      return observer;
    } finally {
      context.detach(previous);
    }
  }

  private static final class Collector
      implements StreamObserver<ReadAuthoredInitialActorCoreResponse> {
    private ReadAuthoredInitialActorCoreResponse value;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(ReadAuthoredInitialActorCoreResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
