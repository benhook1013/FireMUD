package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldFinalizationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldTerminalGrpcService;
import net.firedevops.firemud.worldmanagement.v1.FinalizeCanonicalInitialAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.FinalizeCanonicalInitialAdmissionHoldResponse;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transport component tests; downstream finalization is an explicit service double. */
class WorldCanonicalInitialAdmissionHoldTerminalGrpcServiceTest {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID GAME_INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID HOLD_FENCE = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

  @Test
  void rejectsUnverifiedWrongServiceAndWrongNamespacePeersBeforeOwnerAccess() {
    var finalizer = mock(WorldCanonicalInitialAdmissionHoldFinalizationService.class);
    var grpc = service(finalizer);
    var malformed =
        request(
            new byte[] {1}, FinalizeCanonicalInitialAdmissionHoldRequest.ExpectedOutcome.COMMITTED);

    assertThat(call(grpc, malformed, null).error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(call(grpc, malformed, peer("account-service", "test")).error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(call(grpc, malformed, peer("game-session-service", "other")).error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(finalizer);
  }

  @Test
  void rejectsEndUserContextBeforeParsingOrOwnerAccess() {
    var finalizer = mock(WorldCanonicalInitialAdmissionHoldFinalizationService.class);
    var grpc = service(finalizer);
    SessionContext.setContext("11111111-1111-4111-8111-111111111111", List.of(), Map.of());
    Collector<FinalizeCanonicalInitialAdmissionHoldResponse> result;
    try {
      result =
          call(
              grpc,
              request(
                  new byte[] {1},
                  FinalizeCanonicalInitialAdmissionHoldRequest.ExpectedOutcome.ABORTED),
              peer("game-session-service", "test"));
    } finally {
      SessionContext.clear();
    }

    assertThat(result.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(finalizer);
  }

  @Test
  void rejectsAmbientTransactionsBeforeParsingOrOwnerAccess() {
    var finalizer = mock(WorldCanonicalInitialAdmissionHoldFinalizationService.class);
    var grpc = service(finalizer);
    var malformed =
        request(
            new byte[] {1}, FinalizeCanonicalInitialAdmissionHoldRequest.ExpectedOutcome.COMMITTED);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    Collector<FinalizeCanonicalInitialAdmissionHoldResponse> actual;
    try {
      actual = call(grpc, malformed, peer("game-session-service", "test"));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    TransactionSynchronizationManager.initSynchronization();
    Collector<FinalizeCanonicalInitialAdmissionHoldResponse> synchronization;
    try {
      synchronization = call(grpc, malformed, peer("game-session-service", "test"));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    assertThat(actual.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(synchronization.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(finalizer);
  }

  @Test
  void rejectsUnknownFieldsMalformedIdentityAndUnspecifiedOutcome() {
    var finalizer = mock(WorldCanonicalInitialAdmissionHoldFinalizationService.class);
    var grpc = service(finalizer);
    var fixture = fixture("test");
    var peer = peer("game-session-service", "test");
    var unknown =
        request(
                fixture.identity.canonicalBytes(),
                FinalizeCanonicalInitialAdmissionHoldRequest.ExpectedOutcome.COMMITTED)
            .toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();

    assertThat(call(grpc, unknown, peer).error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            call(
                    grpc,
                    request(
                        new byte[] {1},
                        FinalizeCanonicalInitialAdmissionHoldRequest.ExpectedOutcome.COMMITTED),
                    peer)
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            call(
                    grpc,
                    request(
                        fixture.identity.canonicalBytes(),
                        FinalizeCanonicalInitialAdmissionHoldRequest.ExpectedOutcome
                            .EXPECTED_OUTCOME_UNSPECIFIED),
                    peer)
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(finalizer);
  }

  @Test
  void rejectsNamespaceMismatchBeforeOwnerAccess() {
    var finalizer = mock(WorldCanonicalInitialAdmissionHoldFinalizationService.class);
    var grpc = service(finalizer);
    var fixture = fixture("other");

    assertThat(
            call(
                    grpc,
                    request(
                        fixture.identity.canonicalBytes(),
                        FinalizeCanonicalInitialAdmissionHoldRequest.ExpectedOutcome.COMMITTED),
                    peer("game-session-service", "test"))
                .error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(finalizer);
  }

  @Test
  void componentOnlyCommittedSelectionReturnsCompleteOwnerProofBytes() {
    var finalizer = mock(WorldCanonicalInitialAdmissionHoldFinalizationService.class);
    var grpc = service(finalizer);
    var fixture = fixture("test");
    var proof =
        proof(fixture.identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    when(finalizer.finalizeHold(
            fixture.identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED))
        .thenReturn(proof);

    var result =
        call(
            grpc,
            request(
                fixture.identity.canonicalBytes(),
                FinalizeCanonicalInitialAdmissionHoldRequest.ExpectedOutcome.COMMITTED),
            peer("game-session-service", "test"));

    assertThat(result.error).isNull();
    assertThat(result.completed).isTrue();
    assertThat(result.value.getOwnerProofBytes().toByteArray())
        .containsExactly(GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(proof));
    verify(finalizer)
        .finalizeHold(
            fixture.identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
  }

  @Test
  void componentOnlyAbortedSelectionReturnsCompleteOwnerProofBytes() {
    var finalizer = mock(WorldCanonicalInitialAdmissionHoldFinalizationService.class);
    var grpc = service(finalizer);
    var fixture = fixture("test");
    var proof =
        proof(fixture.identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
    when(finalizer.finalizeHold(
            fixture.identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED))
        .thenReturn(proof);

    var result =
        call(
            grpc,
            request(
                fixture.identity.canonicalBytes(),
                FinalizeCanonicalInitialAdmissionHoldRequest.ExpectedOutcome.ABORTED),
            peer("game-session-service", "test"));

    assertThat(result.error).isNull();
    assertThat(result.completed).isTrue();
    assertThat(result.value.getOwnerProofBytes().toByteArray())
        .containsExactly(GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(proof));
    verify(finalizer)
        .finalizeHold(
            fixture.identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
  }

  @Test
  void rejectsSubstitutedReturnedIdentityOrOutcome() {
    var finalizer = mock(WorldCanonicalInitialAdmissionHoldFinalizationService.class);
    var grpc = service(finalizer);
    var fixture = fixture("test");
    var substituted = fixture("test", uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"));
    when(finalizer.finalizeHold(
            fixture.identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED))
        .thenReturn(
            proof(
                substituted.identity,
                GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED));

    var wrongIdentity =
        call(
            grpc,
            request(
                fixture.identity.canonicalBytes(),
                FinalizeCanonicalInitialAdmissionHoldRequest.ExpectedOutcome.COMMITTED),
            peer("game-session-service", "test"));
    when(finalizer.finalizeHold(
            fixture.identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED))
        .thenReturn(
            proof(
                fixture.identity,
                GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED));
    var wrongOutcome =
        call(
            grpc,
            request(
                fixture.identity.canonicalBytes(),
                FinalizeCanonicalInitialAdmissionHoldRequest.ExpectedOutcome.ABORTED),
            peer("game-session-service", "test"));

    assertThat(wrongIdentity.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(wrongOutcome.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
  }

  private static WorldCanonicalInitialAdmissionHoldTerminalGrpcService service(
      WorldCanonicalInitialAdmissionHoldFinalizationService finalizer) {
    return new WorldCanonicalInitialAdmissionHoldTerminalGrpcService(finalizer, "test");
  }

  private static Fixture fixture(String namespace) {
    return fixture(namespace, HOLD_ID);
  }

  private static Fixture fixture(String namespace, UUID holdId) {
    Request request =
        new Request(
            namespace,
            TENANT,
            "green-hollow",
            REALM,
            PLAYABLE_NAMESPACE,
            "SHARED",
            GAME_INSTANCE,
            VERSION,
            7L,
            "gs-initial-admission-17",
            "a".repeat(64),
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null);
    return new Fixture(request, new HoldIdentity(request, holdId, HOLD_FENCE));
  }

  private static GameSessionCanonicalInitialAdmissionOwnerProof proof(
      HoldIdentity identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome) {
    return outcome == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED
        ? new GameSessionCanonicalInitialAdmissionOwnerProof(
            identity,
            outcome,
            1L,
            42L,
            "sha256:" + "b".repeat(64),
            false,
            Instant.parse("2026-10-07T00:00:00Z"))
        : new GameSessionCanonicalInitialAdmissionOwnerProof(
            identity,
            outcome,
            null,
            null,
            "sha256:" + "b".repeat(64),
            true,
            Instant.parse("2026-10-07T00:00:00Z"));
  }

  private static FinalizeCanonicalInitialAdmissionHoldRequest request(
      byte[] identityBytes, FinalizeCanonicalInitialAdmissionHoldRequest.ExpectedOutcome outcome) {
    return FinalizeCanonicalInitialAdmissionHoldRequest.newBuilder()
        .setHoldIdentityBytes(ByteString.copyFrom(identityBytes))
        .setExpectedOutcome(outcome)
        .build();
  }

  private static Collector<FinalizeCanonicalInitialAdmissionHoldResponse> call(
      WorldCanonicalInitialAdmissionHoldTerminalGrpcService service,
      FinalizeCanonicalInitialAdmissionHoldRequest request,
      GrpcPeerIdentity peer) {
    Collector<FinalizeCanonicalInitialAdmissionHoldResponse> response = new Collector<>();
    Context context =
        peer == null
            ? Context.current()
            : Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.finalizeCanonicalInitialAdmissionHold(request, response);
    } finally {
      context.detach(previous);
    }
    return response;
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(Request request, HoldIdentity identity) {}

  private static final class Collector<T> implements StreamObserver<T> {
    private T value;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(T response) {
      value = response;
    }

    @Override
    public void onError(Throwable throwable) {
      error = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
