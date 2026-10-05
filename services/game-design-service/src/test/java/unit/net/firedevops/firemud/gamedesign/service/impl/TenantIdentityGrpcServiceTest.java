package unit.net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.service.impl.TenantIdentityGrpcService;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

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
  void returnsExactImmutableReceiptOnlyToSameNamespaceAccountPeer() {
    FreshTenantCreationEvidence receipt = evidence("test", REQUEST_DIGEST);
    when(repository.read(REQUEST_ID, "test")).thenReturn(Optional.of(receipt));

    Observer observer = call(request(REQUEST_ID.toString(), REQUEST_DIGEST), ACCOUNT_URI);

    assertThat(observer.failure).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(observer.response)
        .isEqualTo(
            ResolveFreshTenantCreationResponse.newBuilder()
                .setSchemaVersion(1)
                .setTargetNamespace("test")
                .setCreationRequestId(REQUEST_ID.toString())
                .setOperationId(OPERATION_ID.toString())
                .setRequestDigest(REQUEST_DIGEST)
                .setCanonicalTenantId(TENANT_ID.toString())
                .setSourceGameRowId(91L)
                .setSourceGameTenantKey(SOURCE_KEY)
                .setProvenanceKind("NEW_GAME_ROW")
                .setEvidenceDigest(receipt.evidenceDigest())
                .build());
    verify(repository).read(REQUEST_ID, "test");
  }

  @Test
  void deniesMissingWrongServiceWrongNamespaceAndUnconfiguredPeerBeforeRead() {
    for (String peer :
        new String[] {null, GAME_SESSION_URI, "spiffe://firemud/ns/other/sa/account-service"}) {
      assertThat(status(call(request(REQUEST_ID.toString(), REQUEST_DIGEST), peer)))
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    TenantIdentityGrpcService unconfigured = new TenantIdentityGrpcService(repository, "");
    Observer observer = new Observer();
    GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(ACCOUNT_URI).orElseThrow();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(
            () ->
                unconfigured.resolveFreshTenantCreation(
                    request(REQUEST_ID.toString(), REQUEST_DIGEST), observer));
    assertThat(status(observer)).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsMalformedAndOpenRequestsBeforeOwnerRead() {
    for (ResolveFreshTenantCreationRequest malformed :
        new ResolveFreshTenantCreationRequest[] {
          request("22222222-2222-4222-8222-22222222222", REQUEST_DIGEST),
          request("00000000-0000-0000-0000-000000000000", REQUEST_DIGEST),
          request("22222222-2222-4222-8222-222222222222z", REQUEST_DIGEST),
          request(REQUEST_ID.toString(), "SHA256:" + "a".repeat(64)),
          request(REQUEST_ID.toString(), "sha256:short"),
          requestWithUnknownField()
        }) {
      assertThat(status(call(malformed, ACCOUNT_URI))).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
    verifyNoInteractions(repository);
  }

  @Test
  void missingOrMismatchedOwnerEvidenceFailsClosed() {
    String wrongRequestDigest = "sha256:" + "b".repeat(64);
    String otherNamespaceDigest =
        GameTenantCreationDigest.requestDigest("other", REQUEST_ID, SOURCE_KEY, NAME, null);
    when(repository.read(REQUEST_ID, "test"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(evidence("test", wrongRequestDigest)))
        .thenReturn(Optional.of(evidence("other", otherNamespaceDigest)));

    assertThat(status(call(request(REQUEST_ID.toString(), REQUEST_DIGEST), ACCOUNT_URI)))
        .isEqualTo(Status.Code.NOT_FOUND);
    Observer digestMismatch = call(request(REQUEST_ID.toString(), REQUEST_DIGEST), ACCOUNT_URI);
    assertThat(status(digestMismatch)).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(digestMismatch.response).isNull();
    Observer namespaceMismatch = call(request(REQUEST_ID.toString(), REQUEST_DIGEST), ACCOUNT_URI);
    assertThat(status(namespaceMismatch)).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(namespaceMismatch.response).isNull();
  }

  @Test
  void mapsCorruptAndUnavailableOwnerReadsWithoutReturningEvidence() {
    when(repository.read(REQUEST_ID, "test"))
        .thenThrow(new GameTenantCreationRepository.InvalidCreationEvidenceException("corrupt"))
        .thenThrow(new DataAccessResourceFailureException("offline"));
    Observer corrupt = call(request(REQUEST_ID.toString(), REQUEST_DIGEST), ACCOUNT_URI);
    Observer offline = call(request(REQUEST_ID.toString(), REQUEST_DIGEST), ACCOUNT_URI);

    assertThat(status(corrupt)).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(corrupt.response).isNull();
    assertThat(status(offline)).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(offline.response).isNull();
  }

  private Observer call(ResolveFreshTenantCreationRequest request, String peerUri) {
    Observer observer = new Observer();
    Runnable invocation = () -> service.resolveFreshTenantCreation(request, observer);
    if (peerUri == null) {
      invocation.run();
    } else {
      GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(invocation);
    }
    return observer;
  }

  private static ResolveFreshTenantCreationRequest request(String requestId, String digest) {
    return ResolveFreshTenantCreationRequest.newBuilder()
        .setCreationRequestId(requestId)
        .setExpectedRequestDigest(digest)
        .build();
  }

  private static ResolveFreshTenantCreationRequest requestWithUnknownField() {
    return request(REQUEST_ID.toString(), REQUEST_DIGEST).toBuilder()
        .setUnknownFields(
            UnknownFieldSet.newBuilder()
                .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                .build())
        .build();
  }

  private static FreshTenantCreationEvidence evidence(String namespace, String requestDigest) {
    String evidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            namespace,
            REQUEST_ID,
            OPERATION_ID,
            requestDigest,
            TENANT_ID,
            91L,
            SOURCE_KEY,
            "NEW_GAME_ROW");
    return new FreshTenantCreationEvidence(
        1,
        namespace,
        REQUEST_ID,
        OPERATION_ID,
        requestDigest,
        TENANT_ID,
        91L,
        SOURCE_KEY,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static Status.Code status(Observer observer) {
    return observer.failure == null ? null : Status.fromThrowable(observer.failure).getCode();
  }

  private static final class Observer
      implements StreamObserver<ResolveFreshTenantCreationResponse> {
    private ResolveFreshTenantCreationResponse response;
    private Throwable failure;
    private boolean completed;

    @Override
    public void onNext(ResolveFreshTenantCreationResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable throwable) {
      failure = throwable;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
