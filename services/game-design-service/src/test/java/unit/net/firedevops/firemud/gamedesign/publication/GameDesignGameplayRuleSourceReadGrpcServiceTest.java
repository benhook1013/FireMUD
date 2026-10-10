package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.v1.ReadSelectedGameplayRuleSourceRequest;
import net.firedevops.firemud.gamedesign.v1.ReadSelectedGameplayRuleSourceResponse;
import org.junit.jupiter.api.Test;

class GameDesignGameplayRuleSourceReadGrpcServiceTest {
  @Test
  void authenticatesExactAccountOrGameLogicBeforeDecoding() {
    var owner = mock(GameDesignGameplayRuleSourceReadService.class);
    var service = new GameDesignGameplayRuleSourceReadGrpcService(owner, "test");
    var malformed = ReadSelectedGameplayRuleSourceRequest.newBuilder().setSchemaVersion(-1).build();
    var missing = new Collector();
    service.readSelectedGameplayRuleSource(malformed, missing);
    assertThat(missing.error).isEqualTo(Status.Code.UNAUTHENTICATED);
    for (String caller : new String[] {"game-design-service", "world-management-service"}) {
      var result = new Collector();
      peer("test", caller).run(() -> service.readSelectedGameplayRuleSource(malformed, result));
      assertThat(result.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    for (String caller : new String[] {"account-service", "game-logic-service"}) {
      var result = new Collector();
      peer("test", caller).run(() -> service.readSelectedGameplayRuleSource(malformed, result));
      assertThat(result.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
    verifyNoInteractions(owner);
  }

  private static Context peer(String namespace, String service) {
    return Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
                .orElseThrow());
  }

  private static final class Collector
      implements StreamObserver<ReadSelectedGameplayRuleSourceResponse> {
    Status.Code error;

    public void onNext(ReadSelectedGameplayRuleSourceResponse value) {
      throw new AssertionError("No positive result");
    }

    public void onError(Throwable value) {
      error = Status.fromThrowable(value).getCode();
    }

    public void onCompleted() {
      throw new AssertionError("No successful read");
    }
  }
}
