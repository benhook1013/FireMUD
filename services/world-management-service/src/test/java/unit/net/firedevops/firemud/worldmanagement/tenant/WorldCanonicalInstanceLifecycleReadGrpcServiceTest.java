package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleGrpcCodec;
import net.firedevops.firemud.common.world.WorldRunOwnedFixtureLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceLifecycleReadGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceLifecycleReadRepository;
import net.firedevops.firemud.worldmanagement.v1.ReadRunOwnedFixtureStartRoomRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadRunOwnedFixtureStartRoomResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCanonicalInstanceLifecycleReadGrpcServiceTest {
  @Test
  void rejectsActualOrSynchronizationTransactionBeforeParsingOrOwnerAccess() {
    var repository = mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    var service = new WorldCanonicalInstanceLifecycleReadGrpcService(repository, "test");
    var malformed = ReadWorldCanonicalInstanceLifecycleRequest.getDefaultInstance();
    Collector<ReadWorldCanonicalInstanceLifecycleResponse> actual;
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      actual = call(service, malformed, peer("game-session-service", "test"));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    Collector<ReadWorldCanonicalInstanceLifecycleResponse> synchronization;
    TransactionSynchronizationManager.initSynchronization();
    try {
      synchronization = call(service, malformed, peer("game-session-service", "test"));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    assertThat(actual.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(synchronization.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsWrongServiceOrNamespaceBeforeDecodingAndStorageAccess() {
    var repository = mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    var service = new WorldCanonicalInstanceLifecycleReadGrpcService(repository, "test");
    var malformed =
        ReadWorldCanonicalInstanceLifecycleRequest.newBuilder()
            .setCanonicalRequestBytes(ByteString.copyFromUtf8("not-json"))
            .build();

    var wrongService = call(service, malformed, peer("account-service", "test"));
    var wrongNamespace = call(service, malformed, peer("game-session-service", "other"));
    var wrongRequestNamespace =
        call(
            service,
            WorldCanonicalInstanceLifecycleGrpcCodec.toRequest(request("other")),
            peer("game-session-service", "test"));
    assertThat(wrongService.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongRequestNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsMissingPeerIdentityAsUnauthenticatedBeforeStorageAccess() {
    var repository = mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    var service = new WorldCanonicalInstanceLifecycleReadGrpcService(repository, "test");

    var result =
        call(service, ReadWorldCanonicalInstanceLifecycleRequest.getDefaultInstance(), null);

    assertThat(result.error).isEqualTo(Status.Code.UNAUTHENTICATED);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsAuthenticatedEndUserContextBeforeDecodingOrStorageAccess() {
    var repository = mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    var service = new WorldCanonicalInstanceLifecycleReadGrpcService(repository, "test");
    SessionContext.setContext("11111111-1111-4111-8111-111111111111", List.of(), Map.of());
    Collector<ReadWorldCanonicalInstanceLifecycleResponse> result;
    try {
      result =
          call(
              service,
              ReadWorldCanonicalInstanceLifecycleRequest.getDefaultInstance(),
              peer("game-session-service", "test"));
    } finally {
      SessionContext.clear();
    }
    assertThat(result.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void exactSameNamespaceGameSessionPeerReachesOnlyExactLifecycleRequest() {
    var repository = mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    var service = new WorldCanonicalInstanceLifecycleReadGrpcService(repository, "test");
    var read = request("test");
    when(repository.read(read)).thenReturn(Optional.empty());

    var result =
        call(
            service,
            WorldCanonicalInstanceLifecycleGrpcCodec.toRequest(read),
            peer("game-session-service", "test"));

    assertThat(result.error).isEqualTo(Status.Code.NOT_FOUND);
    verify(repository).read(read);
  }

  @Test
  void mapsSpringResourceFailureToUnavailableWithoutLeakingStorageDetails() {
    var repository = mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    var service = new WorldCanonicalInstanceLifecycleReadGrpcService(repository, "test");
    var read = request("test");
    when(repository.read(read))
        .thenThrow(new DataAccessResourceFailureException("private storage detail"));

    var result =
        call(
            service,
            WorldCanonicalInstanceLifecycleGrpcCodec.toRequest(read),
            peer("game-session-service", "test"));

    assertThat(result.error).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(result.description).doesNotContain("private storage detail");
    verify(repository).read(read);
  }

  @Test
  void mapsTransactionCreationFailureToUnavailableWithoutLeakingStorageDetails() {
    var repository = mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    var service = new WorldCanonicalInstanceLifecycleReadGrpcService(repository, "test");
    var read = request("test");
    when(repository.read(read))
        .thenThrow(new CannotCreateTransactionException("private transaction detail"));

    var result =
        call(
            service,
            WorldCanonicalInstanceLifecycleGrpcCodec.toRequest(read),
            peer("game-session-service", "test"));

    assertThat(result.error).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(result.description)
        .isEqualTo("Canonical World lifecycle storage is temporarily unavailable")
        .doesNotContain("private transaction detail");
    verify(repository).read(read);
  }

  @Test
  void classifiesJooqAndSpringStorageFailuresAtTheLifecycleRpcBoundary() {
    List<StorageFailureCase> failures =
        List.of(
            new StorageFailureCase(jooqFailure("22P02"), Status.Code.INTERNAL),
            new StorageFailureCase(jooqFailure("40001"), Status.Code.ABORTED),
            new StorageFailureCase(springFailure("22P02"), Status.Code.INTERNAL),
            new StorageFailureCase(springFailure("40001"), Status.Code.ABORTED));

    for (StorageFailureCase failure : failures) {
      var repository = mock(WorldCanonicalInstanceLifecycleReadRepository.class);
      var service = new WorldCanonicalInstanceLifecycleReadGrpcService(repository, "test");
      var read = request("test");
      when(repository.read(read)).thenThrow(failure.failure);

      var result =
          call(
              service,
              WorldCanonicalInstanceLifecycleGrpcCodec.toRequest(read),
              peer("game-session-service", "test"));

      assertThat(result.error).isEqualTo(failure.expectedCode);
      assertThat(result.description).doesNotContain("private SQL detail");
      verify(repository).read(read);
    }
  }

  @Test
  void mapsNonSqlTooManyRowsToFailedPreconditionWithoutLeakingDetails() {
    var repository = mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    var service = new WorldCanonicalInstanceLifecycleReadGrpcService(repository, "test");
    var read = request("test");
    when(repository.read(read))
        .thenThrow(new org.jooq.exception.TooManyRowsException("private duplicate evidence"));

    var result =
        call(
            service,
            WorldCanonicalInstanceLifecycleGrpcCodec.toRequest(read),
            peer("game-session-service", "test"));

    assertThat(result.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(result.description).doesNotContain("private duplicate evidence");
    verify(repository).read(read);
  }

  @Test
  void authenticatedPeerWithMalformedRequestFailsBeforeStorageAccess() {
    var repository = mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    var service = new WorldCanonicalInstanceLifecycleReadGrpcService(repository, "test");
    var malformed =
        ReadWorldCanonicalInstanceLifecycleRequest.newBuilder()
            .setCanonicalRequestBytes(ByteString.copyFromUtf8("not-json"))
            .build();

    var result = call(service, malformed, peer("game-session-service", "test"));

    assertThat(result.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(repository);
  }

  @Test
  void fixtureStartRoomRequiresTheExactGameSessionPeerAndReturnsTheOwnerRoom() {
    var repository = mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    var service = new WorldCanonicalInstanceLifecycleReadGrpcService(repository, "test");
    var read = request("test");
    var request =
        ReadRunOwnedFixtureStartRoomRequest.newBuilder()
            .setLifecycle(WorldCanonicalInstanceLifecycleGrpcCodec.toRequest(read))
            .build();

    var rejected = new Collector<ReadRunOwnedFixtureStartRoomResponse>();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer("game-logic-service", "test"))
        .run(() -> service.readRunOwnedFixtureStartRoom(request, rejected));
    assertThat(rejected.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);

    var evidence =
        new WorldRunOwnedFixtureLifecycleEvidence(
            read,
            read.expectedReleaseAttestationDigest(),
            "sha256:" + "e".repeat(64),
            "ACTIVE",
            3,
            4);
    when(repository.readRunOwnedFixtureStartRoom(read))
        .thenReturn(
            Optional.of(
                new WorldCanonicalInstanceLifecycleReadRepository.FixtureStartRoom(
                    evidence, "R-37")));
    var admitted = new Collector<ReadRunOwnedFixtureStartRoomResponse>();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer("game-session-service", "test"))
        .run(() -> service.readRunOwnedFixtureStartRoom(request, admitted));

    assertThat(admitted.error).isNull();
    assertThat(admitted.completed).isTrue();
    assertThat(admitted.value.getRoomInstanceKey()).isEqualTo("R-37");
    assertThat(
            WorldCanonicalInstanceLifecycleGrpcCodec.fromFixtureResponse(
                read, admitted.value.getLifecycle()))
        .isEqualTo(evidence);
    verify(repository).readRunOwnedFixtureStartRoom(read);
  }

  private static Collector<ReadWorldCanonicalInstanceLifecycleResponse> call(
      WorldCanonicalInstanceLifecycleReadGrpcService service,
      ReadWorldCanonicalInstanceLifecycleRequest request,
      GrpcPeerIdentity peer) {
    var response = new Collector<ReadWorldCanonicalInstanceLifecycleResponse>();
    Context context =
        peer == null
            ? Context.ROOT
            : Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.readWorldCanonicalInstanceLifecycle(request, response);
    } finally {
      context.detach(previous);
    }
    return response;
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request request(String namespace) {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        1,
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        namespace,
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        "starter-world",
        UUID.fromString("33333333-3333-4333-8333-333333333333"),
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        "SHARED",
        true,
        "control-request",
        UUID.fromString("55555555-5555-4555-8555-555555555555"),
        "sha256:" + "a".repeat(64),
        "sha256:" + "b".repeat(64),
        "sha256:" + "c".repeat(64));
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static org.jooq.exception.DataAccessException jooqFailure(String sqlState) {
    return new org.jooq.exception.DataAccessException(
        "private SQL detail", new SQLException("private SQL detail", sqlState));
  }

  private static org.springframework.dao.DataAccessException springFailure(String sqlState) {
    return new org.springframework.dao.DataAccessException(
        "private SQL detail", new SQLException("private SQL detail", sqlState)) {};
  }

  private record StorageFailureCase(Throwable failure, Status.Code expectedCode) {}

  private static final class Collector<T> implements StreamObserver<T> {
    private T value;
    private Status.Code error;
    private String description;
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
      description = Status.fromThrowable(failure).getDescription();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
