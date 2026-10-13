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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.service.impl.TenantIdentityGrpcService;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class TenantIdentityGrpcServiceTest {
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String WORLD_URI = "spiffe://firemud/ns/test/sa/world-management-service";
  private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID OPERATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID TENANT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final String SOURCE_KEY = "fresh-owner-key-91";
  private static final String NAME = "Fresh Realm";
  private static final String REQUEST_DIGEST =
      GameTenantCreationDigest.requestDigest("test", REQUEST_ID, SOURCE_KEY, NAME, null);
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID REGISTRATION_REQUEST_ID =
      UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID WORLD_TENANT_ID =
      UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final String WORLD_SLUG = "silver-march";
  private static final String WORLD_TENANT_SLUG = "new-kingdom";
  private static final String WORLD_DISPLAY_NAME = "Silver March";

  private final GameTenantCreationRepository repository = mock(GameTenantCreationRepository.class);
  private final GameAuthoredWorldSourceRepository authoredWorldSourceRepository =
      mock(GameAuthoredWorldSourceRepository.class);
  private final TenantIdentityGrpcService service =
      new TenantIdentityGrpcService(repository, authoredWorldSourceRepository, "test");

  @Test
  void resolvesExactPersistedWorldSourceOnlyForSameNamespaceWorldManagementPeer() {
    AuthoredWorldSourceEvidence source = authoredWorldSource("test");
    when(authoredWorldSourceRepository.read(
            SOURCE_OPERATION_ID, WORLD_TENANT_ID, WORLD_SLUG, "test"))
        .thenReturn(Optional.of(source));

    WorldSourceObserver observer = callWorldSource(worldSourceRequest(), WORLD_URI);

    assertThat(observer.failure).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(observer.response)
        .isEqualTo(AuthoredWorldSourceGrpcCodec.toReadResponse(worldSourceReadRequest(), source));
    verify(authoredWorldSourceRepository)
        .read(SOURCE_OPERATION_ID, WORLD_TENANT_ID, WORLD_SLUG, "test");
  }

  @Test
  void deniesNonWorldPeersCrossNamespaceAndCallerContextBeforeAuthoredSourceRead() {
    for (String peer :
        new String[] {
          null,
          ACCOUNT_URI,
          GAME_SESSION_URI,
          "spiffe://firemud/ns/test/sa/entity-management-service",
          "spiffe://firemud/ns/other/sa/world-management-service"
        }) {
      assertThat(status(callWorldSource(worldSourceRequest(), peer)))
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    SessionContext.setContext("account-uuid", List.of("player"), Map.of());
    try {
      assertThat(status(callWorldSource(worldSourceRequest(), WORLD_URI)))
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      SessionContext.clear();
    }
    verifyNoInteractions(authoredWorldSourceRepository);
  }

  @Test
  void rejectsMalformedOrOpenAuthoredSourceRequestsBeforeOwnerRead() {
    for (ResolveAuthoredWorldSourceRequest malformed :
        new ResolveAuthoredWorldSourceRequest[] {
          worldSourceRequest().toBuilder().setRequestId("not-a-uuid").build(),
          worldSourceRequest().toBuilder()
              .setOperationId("00000000-0000-0000-0000-000000000000")
              .build(),
          worldSourceRequest().toBuilder().setCanonicalTenantId("not-a-uuid").build(),
          worldSourceRequest().toBuilder().setWorldSlug("Not A Slug").build(),
          worldSourceRequest().toBuilder().setUnknownFields(unknownField()).build()
        }) {
      assertThat(status(callWorldSource(malformed, WORLD_URI)))
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
    verifyNoInteractions(authoredWorldSourceRepository);
  }

  @Test
  void missingOrSubstitutedAuthoredSourceFailsClosed() {
    when(authoredWorldSourceRepository.read(
            SOURCE_OPERATION_ID, WORLD_TENANT_ID, WORLD_SLUG, "test"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(authoredWorldSource("other")))
        .thenReturn(Optional.of(authoredWorldSource("test", WORLD_TENANT_ID, "other-world")));

    assertThat(status(callWorldSource(worldSourceRequest(), WORLD_URI)))
        .isEqualTo(Status.Code.NOT_FOUND);
    assertThat(status(callWorldSource(worldSourceRequest(), WORLD_URI)))
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(status(callWorldSource(worldSourceRequest(), WORLD_URI)))
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
  }

  @Test
  void mapsCorruptAndUnavailableAuthoredSourceReadsWithoutReturningEvidence() {
    when(authoredWorldSourceRepository.read(
            SOURCE_OPERATION_ID, WORLD_TENANT_ID, WORLD_SLUG, "test"))
        .thenThrow(new GameAuthoredWorldSourceRepository.InvalidSourceEvidenceException("corrupt"))
        .thenThrow(new DataAccessResourceFailureException("offline"));

    WorldSourceObserver corrupt = callWorldSource(worldSourceRequest(), WORLD_URI);
    WorldSourceObserver offline = callWorldSource(worldSourceRequest(), WORLD_URI);

    assertThat(status(corrupt)).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(corrupt.response).isNull();
    assertThat(status(offline)).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(offline.response).isNull();
  }

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
  void returnsExactImmutableReceiptToSameNamespaceGameSessionPeer() {
    FreshTenantCreationEvidence receipt = evidence("test", REQUEST_DIGEST);
    when(repository.read(REQUEST_ID, "test")).thenReturn(Optional.of(receipt));

    Observer observer = call(request(REQUEST_ID.toString(), REQUEST_DIGEST), GAME_SESSION_URI);

    assertThat(observer.failure).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(observer.response.getCreationRequestId()).isEqualTo(REQUEST_ID.toString());
    assertThat(observer.response.getCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
    assertThat(observer.response.getEvidenceDigest()).isEqualTo(receipt.evidenceDigest());
    verify(repository).read(REQUEST_ID, "test");
  }

  @Test
  void deniesCallerContextEvenForExactOwnerPeersBeforeRead() {
    SessionContext.setContext("account-uuid", List.of("player"), Map.of());
    try {
      for (String peer : List.of(ACCOUNT_URI, GAME_SESSION_URI)) {
        assertThat(status(call(request(REQUEST_ID.toString(), REQUEST_DIGEST), peer)))
            .isEqualTo(Status.Code.PERMISSION_DENIED);
      }
      verifyNoInteractions(repository);
    } finally {
      SessionContext.clear();
    }
  }

  @Test
  void deniesMissingWrongServiceWrongNamespaceAndUnconfiguredPeerBeforeRead() {
    for (String peer :
        new String[] {
          null,
          "spiffe://firemud/ns/other/sa/account-service",
          "spiffe://firemud/ns/other/sa/game-session-service",
          "spiffe://firemud/ns/test/sa/entity-management-service"
        }) {
      assertThat(status(call(request(REQUEST_ID.toString(), REQUEST_DIGEST), peer)))
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    TenantIdentityGrpcService unconfigured =
        new TenantIdentityGrpcService(repository, authoredWorldSourceRepository, "");
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
    when(repository.read(REQUEST_ID, "test"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(evidence("test", wrongRequestDigest)))
        .thenReturn(Optional.of(evidence("other", REQUEST_DIGEST)));

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

  private WorldSourceObserver callWorldSource(
      ResolveAuthoredWorldSourceRequest request, String peerUri) {
    WorldSourceObserver observer = new WorldSourceObserver();
    Runnable invocation = () -> service.resolveAuthoredWorldSource(request, observer);
    if (peerUri == null) {
      invocation.run();
    } else {
      GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(invocation);
    }
    return observer;
  }

  private static ResolveAuthoredWorldSourceRequest worldSourceRequest() {
    return ResolveAuthoredWorldSourceRequest.newBuilder()
        .setRequestId(REQUEST_ID.toString())
        .setOperationId(SOURCE_OPERATION_ID.toString())
        .setCanonicalTenantId(WORLD_TENANT_ID.toString())
        .setWorldSlug(WORLD_SLUG)
        .build();
  }

  private static AuthoredWorldSourceGrpcCodec.ReadRequest worldSourceReadRequest() {
    return new AuthoredWorldSourceGrpcCodec.ReadRequest(
        "test", REQUEST_ID, SOURCE_OPERATION_ID, WORLD_TENANT_ID, WORLD_SLUG);
  }

  private static AuthoredWorldSourceEvidence authoredWorldSource(String namespace) {
    return authoredWorldSource(namespace, WORLD_TENANT_ID, WORLD_SLUG);
  }

  private static AuthoredWorldSourceEvidence authoredWorldSource(
      String namespace, UUID canonicalTenantId, String worldSlug) {
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            namespace,
            REGISTRATION_REQUEST_ID,
            canonicalTenantId,
            WORLD_TENANT_SLUG,
            worldSlug,
            WORLD_DISPLAY_NAME);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            namespace,
            REGISTRATION_REQUEST_ID,
            SOURCE_OPERATION_ID,
            requestDigest,
            canonicalTenantId,
            WORLD_TENANT_SLUG,
            worldSlug,
            WORLD_DISPLAY_NAME,
            91L,
            SOURCE_KEY,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        namespace,
        REGISTRATION_REQUEST_ID,
        SOURCE_OPERATION_ID,
        requestDigest,
        canonicalTenantId,
        WORLD_TENANT_SLUG,
        worldSlug,
        WORLD_DISPLAY_NAME,
        91L,
        SOURCE_KEY,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static UnknownFieldSet unknownField() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
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
    return observer.failure;
  }

  private static Status.Code status(WorldSourceObserver observer) {
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
      failure = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  private static final class WorldSourceObserver
      implements StreamObserver<ResolveAuthoredWorldSourceResponse> {
    private ResolveAuthoredWorldSourceResponse response;
    private Status.Code failure;
    private boolean completed;

    @Override
    public void onNext(ResolveAuthoredWorldSourceResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable throwable) {
      failure = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
