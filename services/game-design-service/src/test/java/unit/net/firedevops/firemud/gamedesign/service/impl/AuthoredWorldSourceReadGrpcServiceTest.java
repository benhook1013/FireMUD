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
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.service.impl.AuthoredWorldSourceReadGrpcService;
import net.firedevops.firemud.gamedesign.v1.AuthoredWorldSourceReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.junit.jupiter.api.Test;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;

class AuthoredWorldSourceReadGrpcServiceTest {
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String WORLD_URI = "spiffe://firemud/ns/test/sa/world-management-service";
  private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID REGISTRATION_REQUEST_ID =
      UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID WORLD_TENANT_ID =
      UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final String WORLD_SLUG = "silver-march";
  private static final String WORLD_TENANT_SLUG = "new-kingdom";
  private static final String WORLD_DISPLAY_NAME = "Silver March";
  private static final String SOURCE_KEY = "fresh-owner-key-91";

  private final GameAuthoredWorldSourceRepository authoredWorldSourceRepository =
      mock(GameAuthoredWorldSourceRepository.class);
  private final AuthoredWorldSourceReadGrpcService service =
      new AuthoredWorldSourceReadGrpcService(authoredWorldSourceRepository, "test");

  @Test
  void usesASeparateServiceIdentityFromRegisteredTenantIdentityRpcSurface() {
    assertThat(TenantIdentityServiceGrpc.getServiceDescriptor().getName())
        .isEqualTo("gamedesign.v1.TenantIdentityService");
    assertThat(
            TenantIdentityServiceGrpc.getServiceDescriptor().getMethods().stream()
                .map(method -> method.getFullMethodName())
                .toList())
        .doesNotContain("gamedesign.v1.TenantIdentityService/ResolveAuthoredWorldSource");
    assertThat(AuthoredWorldSourceReadServiceGrpc.getServiceDescriptor().getName())
        .isEqualTo("gamedesign.v1.AuthoredWorldSourceReadService");
    assertThat(
            AuthoredWorldSourceReadServiceGrpc.getServiceDescriptor().getMethods().stream()
                .map(method -> method.getFullMethodName())
                .toList())
        .contains("gamedesign.v1.AuthoredWorldSourceReadService/ResolveAuthoredWorldSource");
  }

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
  void classifiesJooqStorageFailuresByConnectionSqlStateWithoutLeakingDetails() {
    when(authoredWorldSourceRepository.read(
            SOURCE_OPERATION_ID, WORLD_TENANT_ID, WORLD_SLUG, "test"))
        .thenThrow(
            new DataAccessException(
                "unique constraint failed", new SQLException("constraint", "23505")))
        .thenThrow(
            new DataAccessException("relation missing", new SQLException("missing", "42P01")))
        .thenThrow(new DataAccessException("storage failed"))
        .thenThrow(
            new DataAccessException("connection dropped", new SQLException("dropped", "08006")));

    assertStorageFailure(callWorldSource(worldSourceRequest(), WORLD_URI), Status.Code.INTERNAL);
    assertStorageFailure(callWorldSource(worldSourceRequest(), WORLD_URI), Status.Code.INTERNAL);
    assertStorageFailure(callWorldSource(worldSourceRequest(), WORLD_URI), Status.Code.INTERNAL);
    assertStorageFailure(callWorldSource(worldSourceRequest(), WORLD_URI), Status.Code.UNAVAILABLE);
  }

  private static void assertStorageFailure(WorldSourceObserver observer, Status.Code expectedCode) {
    assertThat(observer.failure).isEqualTo(expectedCode);
    assertThat(observer.response).isNull();
    assertThat(observer.completed).isFalse();
    assertThat(Status.fromThrowable(observer.error).getDescription())
        .isEqualTo(
            expectedCode == Status.Code.UNAVAILABLE
                ? "Authored-world source evidence is temporarily unavailable"
                : "Authored-world source evidence could not be read");
    assertThat(((io.grpc.StatusRuntimeException) observer.error).getTrailers()).isNull();
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

  private static Status.Code status(WorldSourceObserver observer) {
    return observer.failure;
  }

  private static final class WorldSourceObserver
      implements StreamObserver<ResolveAuthoredWorldSourceResponse> {
    private ResolveAuthoredWorldSourceResponse response;
    private Status.Code failure;
    private Throwable error;
    private boolean completed;

    @Override
    public void onNext(ResolveAuthoredWorldSourceResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable throwable) {
      error = throwable;
      failure = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
