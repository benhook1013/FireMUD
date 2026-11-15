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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleGrpcCodec;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceLifecycleReadGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceLifecycleReadRepository;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleResponse;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCanonicalInstanceLifecycleReadGrpcServiceTest {
  @Test
  void rejectsActualOrSynchronizationTransactionBeforeParsingOrOwnerAccess() {
    var repository = mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    var service = new WorldCanonicalInstanceLifecycleReadGrpcService(repository, "test");
    var malformed = ReadWorldCanonicalInstanceLifecycleRequest.getDefaultInstance();
    Collector actual;
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      actual = call(service, malformed, peer("game-session-service", "test"));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    Collector synchronization;
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

    Collector wrongService = call(service, malformed, peer("account-service", "test"));
    Collector wrongNamespace = call(service, malformed, peer("game-session-service", "other"));
    Collector wrongRequestNamespace =
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
  void rejectsAuthenticatedEndUserContextBeforeDecodingOrStorageAccess() {
    var repository = mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    var service = new WorldCanonicalInstanceLifecycleReadGrpcService(repository, "test");
    SessionContext.setContext("11111111-1111-4111-8111-111111111111", List.of(), Map.of());
    Collector result;
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

    Collector result =
        call(
            service,
            WorldCanonicalInstanceLifecycleGrpcCodec.toRequest(read),
            peer("game-session-service", "test"));

    assertThat(result.error).isEqualTo(Status.Code.NOT_FOUND);
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

    Collector result = call(service, malformed, peer("game-session-service", "test"));

    assertThat(result.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(repository);
  }

  private static Collector call(
      WorldCanonicalInstanceLifecycleReadGrpcService service,
      ReadWorldCanonicalInstanceLifecycleRequest request,
      GrpcPeerIdentity peer) {
    Collector response = new Collector();
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
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

  private static final class Collector
      implements StreamObserver<ReadWorldCanonicalInstanceLifecycleResponse> {
    private ReadWorldCanonicalInstanceLifecycleResponse value;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(ReadWorldCanonicalInstanceLifecycleResponse response) {
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
