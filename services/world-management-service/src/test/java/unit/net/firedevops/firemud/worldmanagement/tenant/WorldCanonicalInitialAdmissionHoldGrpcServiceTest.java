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
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldOwner;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalInitialAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalInitialAdmissionHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldIdentityRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldIdentityResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldStateRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldStateResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorldCanonicalInitialAdmissionHoldGrpcServiceTest {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID GAME_INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD_ID = uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID HOLD_FENCE = uuid("77777777-7777-4777-8777-777777777777");
  private static final String NAMESPACE = "test";

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void acceptsOnlyAuthenticatedSameNamespaceGameSessionWithoutEndUserContext() {
    var owner = mock(WorldCanonicalInitialAdmissionHoldOwner.class);
    var service = new WorldCanonicalInitialAdmissionHoldGrpcService(owner, NAMESPACE);
    var malformed = AcquireCanonicalInitialAdmissionHoldRequest.getDefaultInstance();

    Collector<AcquireCanonicalInitialAdmissionHoldResponse> unauthenticated =
        callAcquire(service, malformed, null);
    Collector<AcquireCanonicalInitialAdmissionHoldResponse> wrongService =
        callAcquire(service, malformed, peer("account-service", NAMESPACE));
    Collector<AcquireCanonicalInitialAdmissionHoldResponse> wrongNamespace =
        callAcquire(service, malformed, peer("game-session-service", "other"));
    Request otherNamespaceRequest = holdRequest("other");
    var otherNamespaceLifecycle =
        lifecycleRequest(otherNamespaceRequest, uuid("88888888-8888-4888-8888-888888888888"));
    Collector<AcquireCanonicalInitialAdmissionHoldResponse> wrongRequestNamespace =
        callAcquire(
            service,
            AcquireCanonicalInitialAdmissionHoldRequest.newBuilder()
                .setCanonicalHoldRequestBytes(
                    ByteString.copyFrom(otherNamespaceRequest.canonicalRequestBytes()))
                .setCanonicalLifecycleReadRequestBytes(
                    ByteString.copyFrom(otherNamespaceLifecycle.canonicalBytes()))
                .build(),
            peer("game-session-service", NAMESPACE));
    SessionContext.setContext("11111111-1111-4111-8111-111111111111", List.of(), Map.of());
    Collector<AcquireCanonicalInitialAdmissionHoldResponse> endUserContext =
        callAcquire(service, malformed, peer("game-session-service", NAMESPACE));

    assertThat(unauthenticated.error).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(wrongService.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongRequestNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(endUserContext.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner);
  }

  @Test
  void authenticatedAcquireReturnsTheExactWorldIssuedIdentity() {
    var owner = mock(WorldCanonicalInitialAdmissionHoldOwner.class);
    var service = new WorldCanonicalInitialAdmissionHoldGrpcService(owner, NAMESPACE);
    Request holdRequest = holdRequest(NAMESPACE);
    var lifecycleRequest =
        lifecycleRequest(holdRequest, uuid("88888888-8888-4888-8888-888888888888"));
    HoldIdentity identity = new HoldIdentity(holdRequest, HOLD_ID, HOLD_FENCE);
    when(owner.acquire(holdRequest, lifecycleRequest)).thenReturn(identity);
    var request =
        AcquireCanonicalInitialAdmissionHoldRequest.newBuilder()
            .setCanonicalHoldRequestBytes(ByteString.copyFrom(holdRequest.canonicalRequestBytes()))
            .setCanonicalLifecycleReadRequestBytes(
                ByteString.copyFrom(lifecycleRequest.canonicalBytes()))
            .build();

    Collector<AcquireCanonicalInitialAdmissionHoldResponse> result =
        callAcquire(service, request, peer("game-session-service", NAMESPACE));

    assertThat(result.error).isNull();
    assertThat(result.completed).isTrue();
    assertThat(result.value.getHoldIdentityBytes().toByteArray())
        .isEqualTo(identity.canonicalBytes());
    verify(owner).acquire(holdRequest, lifecycleRequest);
  }

  @Test
  void identityAbsenceAndStateReadsRemainObservationalNotSettlement() {
    var owner = mock(WorldCanonicalInitialAdmissionHoldOwner.class);
    var service = new WorldCanonicalInitialAdmissionHoldGrpcService(owner, NAMESPACE);
    Request holdRequest = holdRequest(NAMESPACE);
    var identityRead =
        ReadCanonicalInitialAdmissionHoldIdentityRequest.newBuilder()
            .setReadRequestId("99999999-9999-4999-8999-999999999999")
            .setCanonicalHoldRequestBytes(ByteString.copyFrom(holdRequest.canonicalRequestBytes()))
            .build();
    when(owner.readIdentity(holdRequest)).thenReturn(Optional.empty());
    Collector<ReadCanonicalInitialAdmissionHoldIdentityResponse> identityResult =
        callIdentity(service, identityRead, peer("game-session-service", NAMESPACE));
    assertThat(identityResult.error).isEqualTo(Status.Code.NOT_FOUND);

    UUID readId = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    var lifecycleRequest = lifecycleRequest(holdRequest, readId);
    HoldIdentity identity = new HoldIdentity(holdRequest, HOLD_ID, HOLD_FENCE);
    var stateRead =
        ReadCanonicalInitialAdmissionHoldStateRequest.newBuilder()
            .setReadRequestId(readId.toString())
            .setHoldIdentityBytes(ByteString.copyFrom(identity.canonicalBytes()))
            .setCanonicalLifecycleReadRequestBytes(
                ByteString.copyFrom(lifecycleRequest.canonicalBytes()))
            .build();
    when(owner.readState(identity, lifecycleRequest)).thenReturn(Optional.empty());
    Collector<ReadCanonicalInitialAdmissionHoldStateResponse> stateResult =
        callState(service, stateRead, peer("game-session-service", NAMESPACE));
    assertThat(stateResult.error).isEqualTo(Status.Code.NOT_FOUND);
    verify(owner).readIdentity(holdRequest);
    verify(owner).readState(identity, lifecycleRequest);
  }

  @Test
  void rejectsMismatchedLifecycleTargetAndStateCorrelationBeforeOwnerAccess() {
    var owner = mock(WorldCanonicalInitialAdmissionHoldOwner.class);
    var service = new WorldCanonicalInitialAdmissionHoldGrpcService(owner, NAMESPACE);
    Request holdRequest = holdRequest(NAMESPACE);
    var lifecycleRequest =
        lifecycleRequest(holdRequest, uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
    var mismatchedLifecycle =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            lifecycleRequest.schemaVersion(),
            lifecycleRequest.readRequestId(),
            lifecycleRequest.targetNamespace(),
            lifecycleRequest.canonicalTenantId(),
            lifecycleRequest.worldSlug(),
            uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            lifecycleRequest.playableStateNamespaceId(),
            lifecycleRequest.playableStateScope(),
            true,
            lifecycleRequest.controlPlaneRequestId(),
            lifecycleRequest.canonicalVersionId(),
            lifecycleRequest.expectedDescriptorRequestDigest(),
            lifecycleRequest.expectedDescriptorResultDigest(),
            lifecycleRequest.expectedReleaseAttestationDigest());
    var acquire =
        AcquireCanonicalInitialAdmissionHoldRequest.newBuilder()
            .setCanonicalHoldRequestBytes(ByteString.copyFrom(holdRequest.canonicalRequestBytes()))
            .setCanonicalLifecycleReadRequestBytes(
                ByteString.copyFrom(mismatchedLifecycle.canonicalBytes()))
            .build();
    var mismatchedTarget = callAcquire(service, acquire, peer("game-session-service", NAMESPACE));
    assertThat(mismatchedTarget.error).isEqualTo(Status.Code.INVALID_ARGUMENT);

    HoldIdentity identity = new HoldIdentity(holdRequest, HOLD_ID, HOLD_FENCE);
    var mismatchedCorrelation =
        ReadCanonicalInitialAdmissionHoldStateRequest.newBuilder()
            .setReadRequestId("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
            .setHoldIdentityBytes(ByteString.copyFrom(identity.canonicalBytes()))
            .setCanonicalLifecycleReadRequestBytes(
                ByteString.copyFrom(lifecycleRequest.canonicalBytes()))
            .build();
    var stateResult =
        callState(service, mismatchedCorrelation, peer("game-session-service", NAMESPACE));
    assertThat(stateResult.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner);
  }

  private static Collector<AcquireCanonicalInitialAdmissionHoldResponse> callAcquire(
      WorldCanonicalInitialAdmissionHoldGrpcService service,
      AcquireCanonicalInitialAdmissionHoldRequest request,
      GrpcPeerIdentity peer) {
    Collector<AcquireCanonicalInitialAdmissionHoldResponse> response = new Collector<>();
    attach(peer, () -> service.acquireCanonicalInitialAdmissionHold(request, response));
    return response;
  }

  private static Collector<ReadCanonicalInitialAdmissionHoldIdentityResponse> callIdentity(
      WorldCanonicalInitialAdmissionHoldGrpcService service,
      ReadCanonicalInitialAdmissionHoldIdentityRequest request,
      GrpcPeerIdentity peer) {
    Collector<ReadCanonicalInitialAdmissionHoldIdentityResponse> response = new Collector<>();
    attach(peer, () -> service.readCanonicalInitialAdmissionHoldIdentity(request, response));
    return response;
  }

  private static Collector<ReadCanonicalInitialAdmissionHoldStateResponse> callState(
      WorldCanonicalInitialAdmissionHoldGrpcService service,
      ReadCanonicalInitialAdmissionHoldStateRequest request,
      GrpcPeerIdentity peer) {
    Collector<ReadCanonicalInitialAdmissionHoldStateResponse> response = new Collector<>();
    attach(peer, () -> service.readCanonicalInitialAdmissionHoldState(request, response));
    return response;
  }

  private static void attach(GrpcPeerIdentity peer, Runnable action) {
    Context context =
        peer == null
            ? Context.ROOT
            : Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
    }
  }

  private static Request holdRequest(String namespace) {
    return new Request(
        namespace,
        TENANT,
        "starter-world",
        REALM,
        PLAYABLE_NAMESPACE,
        "SHARED",
        GAME_INSTANCE,
        VERSION,
        7L,
        "initial-admission-request",
        "a".repeat(64),
        WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
        3L,
        null);
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest(
      Request holdRequest, UUID readRequestId) {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        1,
        readRequestId,
        holdRequest.targetNamespace(),
        holdRequest.canonicalTenantId(),
        holdRequest.worldSlug(),
        holdRequest.canonicalGameInstanceId(),
        holdRequest.playableStateNamespaceId(),
        holdRequest.playableStateScope(),
        true,
        "control-request",
        holdRequest.canonicalVersionId(),
        "sha256:" + "1".repeat(64),
        "sha256:" + "2".repeat(64),
        "sha256:" + "3".repeat(64));
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

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
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
