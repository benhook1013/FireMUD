package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.FixtureInitialAdmissionGrpcCodec;
import net.firedevops.firemud.common.world.FixtureInitialAdmissionGrpcCodec.Lookup;
import net.firedevops.firemud.worldmanagement.v1.FixtureInitialAdmissionHoldOperationRequest;
import net.firedevops.firemud.worldmanagement.v1.FixtureInitialAdmissionHoldOperationResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled context/owner adapter tests, not TLS handshake or database execution proof. */
class WorldFixtureInitialAdmissionHoldGrpcServiceTest {
  private final WorldFixtureInitialAdmissionHoldOwner owner =
      mock(WorldFixtureInitialAdmissionHoldOwner.class);
  private final WorldFixtureInitialAdmissionHoldGrpcService service =
      new WorldFixtureInitialAdmissionHoldGrpcService(owner, "test");

  @AfterEach
  void clearContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void absentOrWrongPeerDeniesBeforeDecodingMalformedBytesOrCallingOwner() {
    var request = FixtureInitialAdmissionHoldOperationRequest.getDefaultInstance();
    var absent = new Collector();
    service.acquireFixtureInitialAdmissionHold(request, absent);
    assertThat(absent.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    for (String uri :
        List.of(
            "spiffe://firemud/ns/other/sa/game-session-service",
            "spiffe://firemud/ns/test/sa/world-management-service")) {
      WorldFixtureInitialAdmissionHoldOwnerTest.withPeer(
          uri,
          "a".repeat(64),
          () -> {
            var response = new Collector();
            service.acquireFixtureInitialAdmissionHold(request, response);
            assertThat(response.code()).isEqualTo(Status.Code.PERMISSION_DENIED);
          });
    }
    verifyNoInteractions(owner);
  }

  @Test
  void callerContextAndAmbientSqlRefuseBeforeOwner() {
    peer(
        () -> {
          SessionContext.setContext("11111111-1111-4111-8111-111111111111", List.of(), Map.of());
          var caller = new Collector();
          service.readFixtureInitialAdmissionHoldIdentity(
              FixtureInitialAdmissionHoldOperationRequest.getDefaultInstance(), caller);
          assertThat(caller.code()).isEqualTo(Status.Code.PERMISSION_DENIED);
          SessionContext.clear();
          TransactionSynchronizationManager.setActualTransactionActive(true);
          var transaction = new Collector();
          service.readFixtureInitialAdmissionHoldState(
              FixtureInitialAdmissionHoldOperationRequest.getDefaultInstance(), transaction);
          assertThat(transaction.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        });
    verifyNoInteractions(owner);
  }

  @Test
  void authenticatedMalformedRequestFailsClosedBeforeOwner() {
    peer(
        () -> {
          var response = new Collector();
          service.acquireFixtureInitialAdmissionHold(
              FixtureInitialAdmissionHoldOperationRequest.getDefaultInstance(), response);
          assertThat(response.code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        });
    verifyNoInteractions(owner);
  }

  @Test
  void exactAcquireEchoesOriginalIdentityAndMissingReadDoesNotInventHold() {
    var request = WorldFixtureInitialAdmissionHoldOwnerTest.request(false, "attempt-1");
    var identity = WorldFixtureInitialAdmissionHoldOwnerTest.identity(request);
    var lookup = Lookup.fresh(request, null);
    when(owner.acquire(request)).thenReturn(identity);
    when(owner.readIdentity(request)).thenReturn(Optional.empty());
    peer(
        () -> {
          var acquired = new Collector();
          service.acquireFixtureInitialAdmissionHold(
              FixtureInitialAdmissionGrpcCodec.toWorldRequest(lookup), acquired);
          assertThat(acquired.completed).isTrue();
          assertThat(acquired.failureCode).isNull();
          assertThat(FixtureInitialAdmissionGrpcCodec.requireWorldIdentity(lookup, acquired.value))
              .isEqualTo(identity);
          var missing = new Collector();
          service.readFixtureInitialAdmissionHoldIdentity(
              FixtureInitialAdmissionGrpcCodec.toWorldRequest(lookup), missing);
          assertThat(missing.code()).isEqualTo(Status.Code.NOT_FOUND);
          assertThat(missing.value).isNull();
          assertThat(missing.completed).isFalse();
        });
  }

  private static void peer(Runnable action) {
    WorldFixtureInitialAdmissionHoldOwnerTest.withPeer(
        "spiffe://firemud/ns/test/sa/game-session-service", "a".repeat(64), action);
  }

  private static final class Collector
      implements StreamObserver<FixtureInitialAdmissionHoldOperationResponse> {
    private FixtureInitialAdmissionHoldOperationResponse value;
    private Status.Code failureCode;
    private boolean completed;

    @Override
    public void onNext(FixtureInitialAdmissionHoldOperationResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      failureCode = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }

    Status.Code code() {
      return failureCode;
    }
  }
}
