package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.GetAuthoredWorldVersionStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetAuthoredWorldVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import org.junit.jupiter.api.Test;

class AuthoredWorldVersionStateGrpcServiceTest {
  private static final String NAMESPACE = "test";
  private static final UUID READ_REQUEST_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID SOURCE_OPERATION_ID = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID REGISTRATION_REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID CANONICAL_VERSION_ID = uuid("55555555-5555-4555-8555-555555555555");

  @Test
  void exactWorldPeerMayReadAndReceivesClosedEvidence() {
    AuthoredWorldVersionStateService service = mock(AuthoredWorldVersionStateService.class);
    var request = request();
    var evidence =
        AuthoredWorldVersionStateEvidence.create(
            request,
            sourceEvidence(),
            CANONICAL_VERSION_ID,
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
            7L);
    when(service.read(request)).thenReturn(evidence);
    var handler = new AuthoredWorldVersionStateGrpcService(service, NAMESPACE);
    TestObserver<GetAuthoredWorldVersionStateResponse> observer = new TestObserver<>();

    withPeer(
        peer(NAMESPACE, "world-management-service"),
        () ->
            handler.getAuthoredWorldVersionState(
                AuthoredWorldVersionStateGrpcCodec.toRequest(request), observer));

    assertThat(observer.error).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(observer.value.getEvidence().getCanonicalVersionId())
        .isEqualTo(CANONICAL_VERSION_ID.toString());
    assertThat(
            observer
                .value
                .getEvidence()
                .getDescriptorForType()
                .findFieldByName("canonical_version_id")
                .getNumber())
        .isEqualTo(13);
    assertThat(AuthoredWorldVersionStateGrpcCodec.fromResponse(request, observer.value))
        .isEqualTo(evidence);
    verify(service).read(request);
  }

  @Test
  void exactPeerGuardRunsBeforeMalformedRequestDecodeOrOwnerAccess() {
    AuthoredWorldVersionStateService service = mock(AuthoredWorldVersionStateService.class);
    var handler = new AuthoredWorldVersionStateGrpcService(service, NAMESPACE);
    GetAuthoredWorldVersionStateRequest malformed =
        GetAuthoredWorldVersionStateRequest.newBuilder()
            .setSchemaVersion(99)
            .setReadRequestId("not-a-uuid")
            .build();

    TestObserver<GetAuthoredWorldVersionStateResponse> absent = new TestObserver<>();
    handler.getAuthoredWorldVersionState(malformed, absent);
    assertStatus(absent, Status.Code.PERMISSION_DENIED);

    TestObserver<GetAuthoredWorldVersionStateResponse> wrongService = new TestObserver<>();
    withPeer(
        peer(NAMESPACE, "game-session-service"),
        () -> handler.getAuthoredWorldVersionState(malformed, wrongService));
    assertStatus(wrongService, Status.Code.PERMISSION_DENIED);

    TestObserver<GetAuthoredWorldVersionStateResponse> wrongNamespace = new TestObserver<>();
    withPeer(
        peer("other", "world-management-service"),
        () -> handler.getAuthoredWorldVersionState(malformed, wrongNamespace));
    assertStatus(wrongNamespace, Status.Code.PERMISSION_DENIED);

    verifyNoInteractions(service);
  }

  @Test
  void exactPeerGetsInvalidArgumentBeforeAnyOwnerReadForMalformedClosedRequest() {
    AuthoredWorldVersionStateService service = mock(AuthoredWorldVersionStateService.class);
    var handler = new AuthoredWorldVersionStateGrpcService(service, NAMESPACE);
    GetAuthoredWorldVersionStateRequest malformed =
        GetAuthoredWorldVersionStateRequest.newBuilder()
            .setSchemaVersion(2)
            .setTargetNamespace(NAMESPACE)
            .setReadRequestId(READ_REQUEST_ID.toString())
            .build();
    TestObserver<GetAuthoredWorldVersionStateResponse> observer = new TestObserver<>();

    withPeer(
        peer(NAMESPACE, "world-management-service"),
        () -> handler.getAuthoredWorldVersionState(malformed, observer));

    assertStatus(observer, Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(service);
  }

  private static void assertStatus(TestObserver<?> observer, Status.Code expectedCode) {
    assertThat(observer.error).isNotNull();
    assertThat(observer.error.type()).isEqualTo(StatusRuntimeException.class);
    assertThat(observer.error.code()).isEqualTo(expectedCode);
  }

  private static void withPeer(GrpcPeerIdentity peer, Runnable action) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
    }
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static AuthoredWorldVersionStateEvidence.Request request() {
    AuthoredWorldSourceEvidence source = sourceEvidence();
    return new AuthoredWorldVersionStateEvidence.Request(
        1,
        NAMESPACE,
        READ_REQUEST_ID,
        TENANT_ID,
        "cafe-coast",
        SOURCE_OPERATION_ID,
        source.evidenceDigest(),
        19L);
  }

  private static AuthoredWorldSourceEvidence sourceEvidence() {
    String displayName = "Café 🐉";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, REGISTRATION_REQUEST_ID, TENANT_ID, "tenant-one", "cafe-coast", displayName);
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        REGISTRATION_REQUEST_ID,
        SOURCE_OPERATION_ID,
        requestDigest,
        TENANT_ID,
        "tenant-one",
        "cafe-coast",
        displayName,
        42L,
        "game-owner-tenant",
        "NEW_GAME_ROW",
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            REGISTRATION_REQUEST_ID,
            SOURCE_OPERATION_ID,
            requestDigest,
            TENANT_ID,
            "tenant-one",
            "cafe-coast",
            displayName,
            42L,
            "game-owner-tenant",
            "NEW_GAME_ROW"));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class TestObserver<T> implements StreamObserver<T> {
    private T value;
    private ErrorEvidence error;
    private boolean completed;

    @Override
    public void onNext(T next) {
      value = next;
    }

    @Override
    public void onError(Throwable failure) {
      error = new ErrorEvidence(failure.getClass(), Status.fromThrowable(failure).getCode());
    }

    @Override
    public void onCompleted() {
      completed = true;
    }

    private record ErrorEvidence(Class<?> type, Status.Code code) {}
  }
}
