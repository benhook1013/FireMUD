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
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import org.junit.jupiter.api.Test;

class TenantIdentityGrpcServiceTest {
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID OPERATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID TENANT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final String SOURCE_KEY = "fresh-owner-key-91";
  private static final String NAME = "Fresh Realm";
  private static final String REQUEST_DIGEST =
      GameTenantCreationDigest.requestDigest("test", REQUEST_ID, SOURCE_KEY, NAME, null);

  private final GameTenantCreationRepository repository = mock(GameTenantCreationRepository.class);
  private final TenantIdentityGrpcService service =
      new TenantIdentityGrpcService(repository, "test");

  @Test
  void returnsOnlyTheExactPersistedFreshCreationReceiptToSameNamespaceAccount() {
    FreshTenantCreationEvidence evidence = evidence("test", REQUEST_DIGEST);
    when(repository.read(REQUEST_ID, "test")).thenReturn(Optional.of(evidence));

    Observer observer = call(request(REQUEST_ID.toString(), REQUEST_DIGEST), ACCOUNT_URI);

    assertThat(observer.failure).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(observer.response.getSchemaVersion()).isEqualTo(1);
    assertThat(observer.response.getTargetNamespace()).isEqualTo("test");
    assertThat(observer.response.getCreationRequestId()).isEqualTo(REQUEST_ID.toString());
    assertThat(observer.response.getOperationId()).isEqualTo(OPERATION_ID.toString());
    assertThat(observer.response.getRequestDigest()).isEqualTo(REQUEST_DIGEST);
    assertThat(observer.response.getCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
    assertThat(observer.response.getSourceGameRowId()).isEqualTo(91L);
    assertThat(observer.response.getSourceGameTenantKey()).isEqualTo(SOURCE_KEY);
    assertThat(observer.response.getProvenanceKind()).isEqualTo("NEW_GAME_ROW");
    assertThat(observer.response.getEvidenceDigest()).isEqualTo(evidence.evidenceDigest());
    verify(repository).read(REQUEST_ID, "test");
  }

  @Test
  void rejectsMissingWrongNamespaceAndWrongWorkloadPeersBeforeOwnerRead() {
    ResolveFreshTenantCreationRequest request = request(REQUEST_ID.toString(), REQUEST_DIGEST);

    assertThat(status(call(request, null))).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(status(call(request, "spiffe://firemud/ns/other/sa/account-service")))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(status(call(request, GAME_SESSION_URI))).isEqualTo(Status.Code.PERMISSION_DENIED);

    TenantIdentityGrpcService inactive = new TenantIdentityGrpcService(repository, "");
    assertThat(status(call(inactive, request, ACCOUNT_URI)))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsMalformedAndOpenRequestsBeforeOwnerRead() {
    for (ResolveFreshTenantCreationRequest malformed :
        new ResolveFreshTenantCreationRequest[] {
          request("22222222-2222-4222-8222-22222222222", REQUEST_DIGEST),
          request("00000000-0000-0000-0000-000000000000", REQUEST_DIGEST),
          request(REQUEST_ID.toString(), "SHA256:" + "a".repeat(64)),
          request(REQUEST_ID.toString(), "sha256:short")
        }) {
      assertThat(status(call(malformed, ACCOUNT_URI))).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
    ResolveFreshTenantCreationRequest open =
        request(REQUEST_ID.toString(), REQUEST_DIGEST).toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertThat(status(call(open, ACCOUNT_URI))).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(repository);
  }

  @Test
  void refusesMissingOrMismatchedPersistedEvidence() {
    when(repository.read(REQUEST_ID, "test"))
        .thenReturn(Optional.empty())
        .thenReturn(
            Optional.of(
                evidence(
                    "other",
                    GameTenantCreationDigest.requestDigest(
                        "other", REQUEST_ID, SOURCE_KEY, NAME, null))));

    assertThat(status(call(request(REQUEST_ID.toString(), REQUEST_DIGEST), ACCOUNT_URI)))
        .isEqualTo(Status.Code.NOT_FOUND);
    Observer mismatch = call(request(REQUEST_ID.toString(), REQUEST_DIGEST), ACCOUNT_URI);
    assertThat(status(mismatch)).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(mismatch.response).isNull();
  }

  private static ResolveFreshTenantCreationRequest request(String requestId, String digest) {
    return ResolveFreshTenantCreationRequest.newBuilder()
        .setCreationRequestId(requestId)
        .setExpectedRequestDigest(digest)
        .build();
  }

  private static FreshTenantCreationEvidence evidence(String namespace, String digest) {
    return new FreshTenantCreationEvidence(
        1,
        namespace,
        REQUEST_ID,
        OPERATION_ID,
        digest,
        TENANT_ID,
        91L,
        SOURCE_KEY,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            namespace,
            REQUEST_ID,
            OPERATION_ID,
            digest,
            TENANT_ID,
            91L,
            SOURCE_KEY,
            "NEW_GAME_ROW"));
  }

  private Observer call(ResolveFreshTenantCreationRequest request, String peerUri) {
    return call(service, request, peerUri);
  }

  private static Observer call(
      TenantIdentityGrpcService target, ResolveFreshTenantCreationRequest request, String peerUri) {
    Observer observer = new Observer();
    Runnable invocation = () -> target.resolveFreshTenantCreation(request, observer);
    if (peerUri == null) {
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, null).run(invocation);
    } else {
      GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(invocation);
    }
    return observer;
  }

  private static Status.Code status(Observer observer) {
    assertThat(observer.failure).isNotNull();
    return observer.failure;
  }

  private static final class Observer
      implements StreamObserver<ResolveFreshTenantCreationResponse> {
    private ResolveFreshTenantCreationResponse response;
    private Status.Code failure;
    private boolean completed;

    @Override
    public void onNext(ResolveFreshTenantCreationResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable throwable) {
      assertThat(throwable).isInstanceOf(StatusRuntimeException.class);
      failure = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
