package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityResponse;
import org.jooq.exception.TooManyRowsException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class TenantIdentityGrpcServiceTest {
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final UUID TENANT_ID = UUID.fromString("87426bb3-a733-43f0-9c8e-2e379cbdf7ec");
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  private final GameRepository repository = mock(GameRepository.class);
  private final TenantIdentityGrpcService service =
      new TenantIdentityGrpcService(repository, "test");

  @Test
  void resolvesExactOwnerIdentityForAuthenticatedGameSessionPeer() {
    when(repository.findRuntimeTenantIdentityByCanonicalTenantId(TENANT_ID))
        .thenReturn(
            Optional.of(
                new GameTenantIdentity(
                    TENANT_ID,
                    GameTenantIdentity.ProvenanceKind.RETAINED_GAME_V29,
                    42L,
                    "legacy-owner-key-42")));

    TestObserver observer =
        call(request(TENANT_ID.toString(), REQUEST_ID.toString()), GAME_SESSION_URI);

    assertThat(observer.failure).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(observer.value.getSchemaVersion()).isEqualTo(1);
    assertThat(observer.value.getTargetNamespace()).isEqualTo("test");
    assertThat(observer.value.getRequestId()).isEqualTo(REQUEST_ID.toString());
    assertThat(observer.value.getCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
    assertThat(observer.value.getSourceGameRowId()).isEqualTo(42L);
    assertThat(observer.value.getSourceGameTenantKey()).isEqualTo("legacy-owner-key-42");
    assertThat(observer.value.getProvenanceKind()).isEqualTo("RETAINED_GAME_V29");
    verify(repository).findRuntimeTenantIdentityByCanonicalTenantId(TENANT_ID);
  }

  @Test
  void rejectsUnauthenticatedWrongNamespaceAndWrongServiceBeforeOwnerRead() {
    assertThat(status(call(request(TENANT_ID.toString(), REQUEST_ID.toString()), null)))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            status(
                call(
                    request(TENANT_ID.toString(), REQUEST_ID.toString()),
                    "spiffe://firemud/ns/other/sa/game-session-service")))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            status(
                call(
                    request(TENANT_ID.toString(), REQUEST_ID.toString()),
                    "spiffe://firemud/ns/test/sa/account-service")))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void leavesRuntimeReadInactiveWhenWorkloadNamespaceIsNotConfigured() {
    TenantIdentityGrpcService inactiveService = new TenantIdentityGrpcService(repository, "");
    TestObserver observer = new TestObserver();
    GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(GAME_SESSION_URI).orElseThrow();

    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(
            () ->
                inactiveService.resolveRuntimeTenantIdentity(
                    request(TENANT_ID.toString(), REQUEST_ID.toString()), observer));

    assertThat(status(observer)).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsNoncanonicalNilAndOpenMetadataRequestsBeforeOwnerRead() {
    assertThat(
            status(
                call(
                    request(TENANT_ID.toString().toUpperCase(), REQUEST_ID.toString()),
                    GAME_SESSION_URI)))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            status(
                call(
                    request("00000000-0000-0000-0000-000000000000", REQUEST_ID.toString()),
                    GAME_SESSION_URI)))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            status(
                call(
                    request(TENANT_ID.toString(), "00000000-0000-0000-0000-000000000000"),
                    GAME_SESSION_URI)))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    ResolveRuntimeTenantIdentityRequest openRequest =
        request(TENANT_ID.toString(), REQUEST_ID.toString()).toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertThat(status(call(openRequest, GAME_SESSION_URI))).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(repository);
  }

  @Test
  void returnsNotFoundAndFailsClosedForInvalidOrAmbiguousOwnerEvidence() {
    when(repository.findRuntimeTenantIdentityByCanonicalTenantId(TENANT_ID))
        .thenReturn(Optional.empty())
        .thenReturn(
            Optional.of(
                new GameTenantIdentity(
                    TENANT_ID, GameTenantIdentity.ProvenanceKind.NEW_GAME_ROW, 42L, " ")))
        .thenThrow(new TooManyRowsException("duplicate owner identity"));

    assertThat(status(call(request(TENANT_ID.toString(), REQUEST_ID.toString()), GAME_SESSION_URI)))
        .isEqualTo(Status.Code.NOT_FOUND);
    assertThat(status(call(request(TENANT_ID.toString(), REQUEST_ID.toString()), GAME_SESSION_URI)))
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(status(call(request(TENANT_ID.toString(), REQUEST_ID.toString()), GAME_SESSION_URI)))
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
  }

  @Test
  void mapsUnavailableOwnerReadWithoutReturningPartialEvidence() {
    when(repository.findRuntimeTenantIdentityByCanonicalTenantId(TENANT_ID))
        .thenThrow(new DataAccessResourceFailureException("unavailable"));

    TestObserver observer =
        call(request(TENANT_ID.toString(), REQUEST_ID.toString()), GAME_SESSION_URI);

    assertThat(status(observer)).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
  }

  private static ResolveRuntimeTenantIdentityRequest request(String tenantId, String requestId) {
    return ResolveRuntimeTenantIdentityRequest.newBuilder()
        .setCanonicalTenantId(tenantId)
        .setRequestId(requestId)
        .build();
  }

  private TestObserver call(ResolveRuntimeTenantIdentityRequest request, String peerUri) {
    TestObserver observer = new TestObserver();
    Runnable invocation = () -> service.resolveRuntimeTenantIdentity(request, observer);
    if (peerUri == null) {
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, null).run(invocation);
    } else {
      GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(invocation);
    }
    return observer;
  }

  private static Status.Code status(TestObserver observer) {
    assertThat(observer.grpcFailure).isTrue();
    assertThat(observer.failure).isNotNull();
    return observer.failure;
  }

  private static final class TestObserver
      implements StreamObserver<ResolveRuntimeTenantIdentityResponse> {
    private ResolveRuntimeTenantIdentityResponse value;
    private Status.Code failure;
    private boolean grpcFailure;
    private boolean completed;

    @Override
    public void onNext(ResolveRuntimeTenantIdentityResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable throwable) {
      grpcFailure = throwable instanceof StatusRuntimeException;
      failure = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
