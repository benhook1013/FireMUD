package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.account.v1.ReadHeldGameLogicIntakeAuthorizationRequest;
import net.firedevops.firemud.account.v1.ReadHeldGameLogicIntakeAuthorizationResponse;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.Test;

class AccountGameLogicIntakeAuthorizationReadGrpcServiceTest {
  @Test
  void callerAuthenticationPrecedesBindingParsingAndRepositoryAccess() {
    var owner = mock(AccountGameLogicIntakeAuthorizationReadService.class);
    var service = new AccountGameLogicIntakeAuthorizationReadGrpcService(owner, "test");
    var malformed =
        ReadHeldGameLogicIntakeAuthorizationRequest.newBuilder().setSchemaVersion(-1).build();
    var missing = new Collector();
    service.readHeldGameLogicIntakeAuthorization(malformed, missing);
    assertThat(missing.error).isEqualTo(Status.Code.UNAUTHENTICATED);
    for (var caller :
        new String[] {"world-management-service", "account-service", "game-design-service"}) {
      var result = new Collector();
      Context.current()
          .withValue(
              GrpcPeerIdentity.CONTEXT_KEY,
              GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/" + caller).orElseThrow())
          .run(() -> service.readHeldGameLogicIntakeAuthorization(malformed, result));
      assertThat(result.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    var allowedMalformed = new Collector();
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-logic-service")
                .orElseThrow())
        .run(() -> service.readHeldGameLogicIntakeAuthorization(malformed, allowedMalformed));
    assertThat(allowedMalformed.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner);
  }

  private static final class Collector
      implements StreamObserver<ReadHeldGameLogicIntakeAuthorizationResponse> {
    Status.Code error;

    public void onNext(ReadHeldGameLogicIntakeAuthorizationResponse value) {
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
