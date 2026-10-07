package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldRepository;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalInitialAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalInitialAdmissionHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldIdentityRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldIdentityResponse;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCanonicalInitialAdmissionHoldGrpcServiceTest {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID OTHER_PLAYABLE_NAMESPACE =
      uuid("34343434-3434-4434-8434-343434343434");
  private static final UUID GAME_INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID LIFECYCLE_READ_ID = uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID HOLD_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID HOLD_FENCE = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID HOLD_READ_ID = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final String REQUEST_ID = "gs-initial-admission-17";
  private static final String REQUEST_DIGEST = "a".repeat(64);

  @Test
  void rejectsMissingWrongAndWrongNamespacePeersBeforeParsingOrOwnerAccess() {
    var repository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var grpc = service(repository);
    var malformed = acquire(new byte[] {1}, new byte[] {1});

    Collector<AcquireCanonicalInitialAdmissionHoldResponse> missing =
        callAcquire(grpc, malformed, null);
    Collector<AcquireCanonicalInitialAdmissionHoldResponse> wrongService =
        callAcquire(grpc, malformed, peer("account-service", "test"));
    Collector<AcquireCanonicalInitialAdmissionHoldResponse> wrongNamespace =
        callAcquire(grpc, malformed, peer("game-session-service", "other"));

    assertThat(missing.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongService.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsEndUserContextBeforeParsingOrOwnerAccess() {
    var repository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var grpc = service(repository);
    SessionContext.setContext("11111111-1111-4111-8111-111111111111", List.of(), Map.of());
    Collector<AcquireCanonicalInitialAdmissionHoldResponse> result;
    try {
      result = callAcquire(grpc, acquire(new byte[] {1}, new byte[] {1}), peer("game-session-service", "test"));
    } finally {
      SessionContext.clear();
    }

    assertThat(result.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsAmbientActualAndSynchronizationTransactionsBeforeParsingOrOwnerAccess() {
    var repository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var grpc = service(repository);
    var malformed = acquire(new byte[] {1}, new byte[] {1});

    TransactionSynchronizationManager.setActualTransactionActive(true);
    Collector<AcquireCanonicalInitialAdmissionHoldResponse> actualTransaction;
    try {
      actualTransaction = callAcquire(grpc, malformed, peer("game-session-service", "test"));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    TransactionSynchronizationManager.initSynchronization();
    Collector<AcquireCanonicalInitialAdmissionHoldResponse> synchronization;
    try {
      synchronization = callAcquire(grpc, malformed, peer("game-session-service", "test"));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    assertThat(actualTransaction.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(synchronization.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsUnknownFieldsMalformedCanonicalBytesAndNoncanonicalReadIds() {
    var repository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var grpc = service(repository);
    Fixture fixture = fixture("test", "test", PLAYABLE_NAMESPACE);
    var unknownAcquire =
        acquire(fixture.holdBytes, fixture.lifecycleBytes).toBuilder()
            .setUnknownFields(unknownFields())
            .build();
    var unknownRead =
        read(HOLD_READ_ID.toString(), fixture.holdBytes).toBuilder()
            .setUnknownFields(unknownFields())
            .build();

    assertThat(callAcquire(grpc, unknownAcquire, peer("game-session-service", "test")).error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(callRead(grpc, unknownRead, peer("game-session-service", "test")).error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            callAcquire(
                    grpc,
                    acquire(
                        "not-json".getBytes(StandardCharsets.UTF_8), fixture.lifecycleBytes),
                    peer("game-session-service", "test"))
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            callAcquire(
                    grpc,
                    acquire(fixture.holdBytes, "not-json".getBytes(StandardCharsets.UTF_8)),
                    peer("game-session-service", "test"))
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            callRead(grpc, read(HOLD_READ_ID.toString().toUpperCase(), fixture.holdBytes), peer("game-session-service", "test"))
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            callRead(grpc, read("00000000-0000-0000-0000-000000000000", fixture.holdBytes), peer("game-session-service", "test"))
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(repository);
  }

  @Test
  void requiresTrustedNamespaceOnBothAcquireTuplesAndExactMatchingTargetScope() {
    var repository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    var grpc = service(repository);
    Fixture wrongHoldNamespace = fixture("other", "test", PLAYABLE_NAMESPACE);
    Fixture wrongLifecycleNamespace = fixture("test", "other", PLAYABLE_NAMESPACE);
    Fixture mismatchedScope = fixture("test", "test", OTHER_PLAYABLE_NAMESPACE);

    assertThat(
            callAcquire(grpc, acquire(wrongHoldNamespace), peer("game-session-service", "test")).error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            callAcquire(
                    grpc,
                    acquire(wrongLifecycleNamespace),
                    peer("game-session-service", "test"))
                .error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(callAcquire(grpc, acquire(mismatchedScope), peer("game-session-service", "test")).error)
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(repository);
  }

  @Test
  void acquireReturnsExactCanonicalIdentityAfterRepositoryValidation() {
    Fixture fixture = fixture("test", "test", PLAYABLE_NAMESPACE);
    var repository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    when(repository.acquire(any(Request.class), any(WorldCanonicalInstanceLifecycleEvidence.Request.class)))
        .thenReturn(fixture.identity);
    var grpc = service(repository);

    Collector<AcquireCanonicalInitialAdmissionHoldResponse> result =
        callAcquire(grpc, acquire(fixture), peer("game-session-service", "test"));

    assertThat(result.error).isNull();
    assertThat(result.completed).isTrue();
    assertThat(result.value.getHoldIdentityBytes().toByteArray())
        .containsExactly(fixture.identity.canonicalBytes());
    assertThat(HoldIdentity.fromStored(result.value.getHoldIdentityBytes().toByteArray()))
        .isEqualTo(fixture.identity);
    verify(repository).acquire(fixture.holdRequest, fixture.lifecycleRequest);
  }

  @Test
  void readReturnsExactEchoAndHistoricalImmutableIdentityOnly() {
    Fixture fixture = fixture("test", "test", PLAYABLE_NAMESPACE);
    var repository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    when(repository.readIdentity(any(Request.class))).thenReturn(Optional.of(fixture.identity));
    var grpc = service(repository);

    Collector<ReadCanonicalInitialAdmissionHoldIdentityResponse> result =
        callRead(
            grpc,
            read(HOLD_READ_ID.toString(), fixture.holdBytes),
            peer("game-session-service", "test"));

    assertThat(result.error).isNull();
    assertThat(result.completed).isTrue();
    assertThat(result.value.getReadRequestId()).isEqualTo(HOLD_READ_ID.toString());
    assertThat(result.value.getHoldIdentityBytes().toByteArray())
        .containsExactly(fixture.identity.canonicalBytes());
    verify(repository).readIdentity(fixture.holdRequest);
  }

  @Test
  void emptyHistoricalReadReturnsNotFoundWithoutAnIdentityPayload() {
    Fixture fixture = fixture("test", "test", PLAYABLE_NAMESPACE);
    var repository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    when(repository.readIdentity(any(Request.class))).thenReturn(Optional.empty());
    var grpc = service(repository);

    Collector<ReadCanonicalInitialAdmissionHoldIdentityResponse> result =
        callRead(
            grpc,
            read(HOLD_READ_ID.toString(), fixture.holdBytes),
            peer("game-session-service", "test"));

    assertThat(result.error).isEqualTo(Status.Code.NOT_FOUND);
    assertThat(result.value).isNull();
    verify(repository).readIdentity(fixture.holdRequest);
  }

  @Test
  void mapsOwnerConflictsAndInconsistentIdentitiesToTheirExactStatuses() {
    Fixture fixture = fixture("test", "test", PLAYABLE_NAMESPACE);
    var conflictRepository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    when(conflictRepository.acquire(any(Request.class), any(WorldCanonicalInstanceLifecycleEvidence.Request.class)))
        .thenThrow(new WorldCanonicalInitialAdmissionHoldRepository.HoldConflictException("conflict"));
    when(conflictRepository.readIdentity(any(Request.class)))
        .thenThrow(new WorldCanonicalInitialAdmissionHoldRepository.HoldConflictException("conflict"));
    var conflictService = service(conflictRepository);
    assertThat(
            callAcquire(conflictService, acquire(fixture), peer("game-session-service", "test")).error)
        .isEqualTo(Status.Code.ALREADY_EXISTS);
    assertThat(
            callRead(
                    conflictService,
                    read(HOLD_READ_ID.toString(), fixture.holdBytes),
                    peer("game-session-service", "test"))
                .error)
        .isEqualTo(Status.Code.ALREADY_EXISTS);

    var invalidRepository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    when(invalidRepository.acquire(any(Request.class), any(WorldCanonicalInstanceLifecycleEvidence.Request.class)))
        .thenThrow(
            new WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldIdentityException(
                "invalid identity"));
    when(invalidRepository.readIdentity(any(Request.class)))
        .thenThrow(
            new WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldIdentityException(
                "invalid identity"));
    var invalidService = service(invalidRepository);
    assertThat(
            callAcquire(invalidService, acquire(fixture), peer("game-session-service", "test")).error)
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(
            callRead(
                    invalidService,
                    read(HOLD_READ_ID.toString(), fixture.holdBytes),
                    peer("game-session-service", "test"))
                .error)
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
  }

  @Test
  void rejectsNullOrSubstitutedOwnerIdentityAsFailedPrecondition() {
    Fixture fixture = fixture("test", "test", PLAYABLE_NAMESPACE);
    var nullRepository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    when(nullRepository.acquire(any(Request.class), any(WorldCanonicalInstanceLifecycleEvidence.Request.class)))
        .thenReturn(null);
    var nullResult =
        callAcquire(
            service(nullRepository), acquire(fixture), peer("game-session-service", "test"));
    assertThat(nullResult.error).isEqualTo(Status.Code.FAILED_PRECONDITION);

    Request changedRequest = holdRequest("test", "b".repeat(64));
    HoldIdentity substituted = new HoldIdentity(changedRequest, HOLD_ID, HOLD_FENCE);
    var substitutedRepository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    when(substitutedRepository.readIdentity(any(Request.class)))
        .thenReturn(Optional.of(substituted));
    var substitutedResult =
        callRead(
            service(substitutedRepository),
            read(HOLD_READ_ID.toString(), fixture.holdBytes),
            peer("game-session-service", "test"));
    assertThat(substitutedResult.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
  }

  @Test
  void mapsTransientStorageToUnavailableAndOtherStorageOrUnknownFailuresToInternal() {
    Fixture fixture = fixture("test", "test", PLAYABLE_NAMESPACE);
    var transientRepository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    when(transientRepository.acquire(any(Request.class), any(WorldCanonicalInstanceLifecycleEvidence.Request.class)))
        .thenThrow(new TransientDataAccessException("temporary storage issue") {});
    var transientResult =
        callAcquire(
            service(transientRepository), acquire(fixture), peer("game-session-service", "test"));
    assertThat(transientResult.error).isEqualTo(Status.Code.UNAVAILABLE);

    var permanentRepository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    when(permanentRepository.readIdentity(any(Request.class)))
        .thenThrow(
            new DataAccessException("permanent storage issue", new SQLException("bad schema")) {});
    var permanentResult =
        callRead(
            service(permanentRepository),
            read(HOLD_READ_ID.toString(), fixture.holdBytes),
            peer("game-session-service", "test"));
    assertThat(permanentResult.error).isEqualTo(Status.Code.INTERNAL);

    var unknownRepository = mock(WorldCanonicalInitialAdmissionHoldRepository.class);
    when(unknownRepository.readIdentity(any(Request.class)))
        .thenThrow(new IllegalStateException("unexpected owner failure"));
    var unknownResult =
        callRead(
            service(unknownRepository),
            read(HOLD_READ_ID.toString(), fixture.holdBytes),
            peer("game-session-service", "test"));
    assertThat(unknownResult.error).isEqualTo(Status.Code.INTERNAL);
  }

  private static WorldCanonicalInitialAdmissionHoldGrpcService service(
      WorldCanonicalInitialAdmissionHoldRepository repository) {
    return new WorldCanonicalInitialAdmissionHoldGrpcService(repository, "test");
  }

  private static Fixture fixture(
      String holdNamespace, String lifecycleNamespace, UUID lifecyclePlayableNamespace) {
    Request holdRequest = holdRequest(holdNamespace, REQUEST_DIGEST);
    var lifecycleRequest =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
            LIFECYCLE_READ_ID,
            lifecycleNamespace,
            TENANT,
            "green-hollow",
            GAME_INSTANCE,
            lifecyclePlayableNamespace,
            "SHARED",
            true,
            REQUEST_ID,
            VERSION,
            "sha256:" + "c".repeat(64),
            "sha256:" + "d".repeat(64),
            "sha256:" + "e".repeat(64));
    return new Fixture(
        holdRequest,
        holdRequest.canonicalRequestBytes(),
        lifecycleRequest,
        lifecycleRequest.canonicalBytes(),
        new HoldIdentity(holdRequest, HOLD_ID, HOLD_FENCE));
  }

  private static Request holdRequest(String namespace, String digest) {
    return new Request(
        namespace,
        TENANT,
        "green-hollow",
        REALM,
        PLAYABLE_NAMESPACE,
        "SHARED",
        GAME_INSTANCE,
        VERSION,
        7L,
        REQUEST_ID,
        digest,
        InitialAdmissionOrigin.NO_PRIOR_POINTER,
        12L,
        null);
  }

  private static AcquireCanonicalInitialAdmissionHoldRequest acquire(Fixture fixture) {
    return acquire(fixture.holdBytes, fixture.lifecycleBytes);
  }

  private static AcquireCanonicalInitialAdmissionHoldRequest acquire(
      byte[] holdBytes, byte[] lifecycleBytes) {
    return AcquireCanonicalInitialAdmissionHoldRequest.newBuilder()
        .setCanonicalHoldRequestBytes(ByteString.copyFrom(holdBytes))
        .setCanonicalLifecycleReadRequestBytes(ByteString.copyFrom(lifecycleBytes))
        .build();
  }

  private static ReadCanonicalInitialAdmissionHoldIdentityRequest read(
      String readId, byte[] holdBytes) {
    return ReadCanonicalInitialAdmissionHoldIdentityRequest.newBuilder()
        .setReadRequestId(readId)
        .setCanonicalHoldRequestBytes(ByteString.copyFrom(holdBytes))
        .build();
  }

  private static UnknownFieldSet unknownFields() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static Collector<AcquireCanonicalInitialAdmissionHoldResponse> callAcquire(
      WorldCanonicalInitialAdmissionHoldGrpcService service,
      AcquireCanonicalInitialAdmissionHoldRequest request,
      GrpcPeerIdentity peer) {
    Collector<AcquireCanonicalInitialAdmissionHoldResponse> response = new Collector<>();
    Context context =
        peer == null
            ? Context.current()
            : Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.acquireCanonicalInitialAdmissionHold(request, response);
    } finally {
      context.detach(previous);
    }
    return response;
  }

  private static Collector<ReadCanonicalInitialAdmissionHoldIdentityResponse> callRead(
      WorldCanonicalInitialAdmissionHoldGrpcService service,
      ReadCanonicalInitialAdmissionHoldIdentityRequest request,
      GrpcPeerIdentity peer) {
    Collector<ReadCanonicalInitialAdmissionHoldIdentityResponse> response = new Collector<>();
    Context context =
        peer == null
            ? Context.current()
            : Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.readCanonicalInitialAdmissionHoldIdentity(request, response);
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

  private record Fixture(
      Request holdRequest,
      byte[] holdBytes,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest,
      byte[] lifecycleBytes,
      HoldIdentity identity) {}

  private static final class Collector<T> implements StreamObserver<T> {
    private T value;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(T response) {
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
