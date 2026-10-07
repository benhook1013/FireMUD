package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesRequest;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyRequest;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityResponse;
import org.jooq.exception.DataAccessException;
import org.jooq.exception.TooManyRowsException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

class TenantIdentityGrpcServiceTest {
  private static final String SOURCE_ACCOUNT_PEER = "spiffe://firemud/ns/test/sa/account-service";
  private static final String SOURCE_ACCOUNT_MIGRATOR_PEER =
      "spiffe://firemud/ns/test/sa/account-tenant-migrator";
  private static final String SOURCE_GAME_SESSION_PEER =
      "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String SOURCE_WRONG_NAMESPACE_GAME_SESSION_PEER =
      "spiffe://firemud/ns/other/sa/game-session-service";
  private static final String SOURCE_WORLD_MANAGEMENT_PEER =
      "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String SOURCE_WRONG_NAMESPACE_WORLD_MANAGEMENT_PEER =
      "spiffe://firemud/ns/other/sa/world-management-service";
  private static final String SOURCE_GAME_DESIGN_PEER =
      "spiffe://firemud/ns/test/sa/game-design-service";
  private static final UUID SOURCE_FRESH_CREATION_REQUEST_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID SOURCE_FRESH_OPERATION_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SOURCE_RUNTIME_REQUEST_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID SOURCE_RUNTIME_TENANT_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private final GameAuthoredWorldSourceRepository authoredWorldRepository =
      mock(GameAuthoredWorldSourceRepository.class);

  @Test
  void authoredSourceReadRejectsUnrelatedOrWrongNamespacePeersBeforeOwnerAccess() {
    assertThat(GrpcPeerIdentity.parseUri(SOURCE_ACCOUNT_MIGRATOR_PEER)).isEmpty();
    for (String peer :
        new String[] {
          null,
          SOURCE_ACCOUNT_PEER,
          SOURCE_ACCOUNT_MIGRATOR_PEER,
          SOURCE_WRONG_NAMESPACE_GAME_SESSION_PEER,
          SOURCE_WRONG_NAMESPACE_WORLD_MANAGEMENT_PEER,
          SOURCE_GAME_DESIGN_PEER
        }) {
      AuthoredSourceObserver observer = authoredSourceCall(authoredSourceRequest(), peer);
      assertEquals(Status.Code.PERMISSION_DENIED, observer.errorCode);
      assertNull(observer.value);
      assertFalse(observer.completed);
    }
    AuthoredSourceObserver malformedRequestWithWrongPeer =
        authoredSourceCall(
            authoredSourceRequest().toBuilder().setRequestId("malformed").build(),
            SOURCE_GAME_DESIGN_PEER);
    assertEquals(Status.Code.PERMISSION_DENIED, malformedRequestWithWrongPeer.errorCode);
    verifyNoInteractions(authoredWorldRepository);
  }

  @Test
  void authoredSourceReadAllowsExactSameNamespaceWorldManagementPeer() {
    AuthoredWorldSourceEvidence evidence = authoredSourceEvidence("test");
    when(authoredWorldRepository.read(
            SOURCE_FRESH_OPERATION_ID, SOURCE_RUNTIME_TENANT_ID, "world-one", "test"))
        .thenReturn(Optional.of(evidence));

    AuthoredSourceObserver observer =
        authoredSourceCall(authoredSourceRequest(), SOURCE_WORLD_MANAGEMENT_PEER);

    assertNull(observer.errorCode);
    assertTrue(observer.completed);
    assertNotNull(observer.value);
    assertEquals(SOURCE_RUNTIME_REQUEST_ID.toString(), observer.value.getRequestId());
    assertEquals(evidence.evidenceDigest(), observer.value.getEvidenceDigest());
    verify(authoredWorldRepository)
        .read(SOURCE_FRESH_OPERATION_ID, SOURCE_RUNTIME_TENANT_ID, "world-one", "test");
  }

  @Test
  void authoredSourceRejectsMalformedTupleAndUnknownFieldsBeforeRead() {
    ResolveAuthoredWorldSourceRequest exact = authoredSourceRequest();
    for (ResolveAuthoredWorldSourceRequest request :
        new ResolveAuthoredWorldSourceRequest[] {
          exact.toBuilder().setOperationId("19").build(),
          exact.toBuilder().setCanonicalTenantId("00000000-0000-0000-0000-000000000000").build(),
          exact.toBuilder().setRequestId("1-1-1-1-1").build(),
          exact.toBuilder().setWorldSlug(" World").build(),
          exact.toBuilder().setWorldSlug("w".repeat(121)).build(),
          exact.toBuilder()
              .setUnknownFields(
                  UnknownFieldSet.newBuilder()
                      .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                      .build())
              .build()
        }) {
      AuthoredSourceObserver observer = authoredSourceCall(request, SOURCE_GAME_SESSION_PEER);
      assertEquals(Status.Code.INVALID_ARGUMENT, observer.errorCode);
      assertNull(observer.value);
    }
    verifyNoInteractions(authoredWorldRepository);
  }

  @Test
  void authoredSourceEchoesDistinctReadIdentityAndCompleteImmutableReceipt() {
    AuthoredWorldSourceEvidence evidence = authoredSourceEvidence("test");
    when(authoredWorldRepository.read(
            SOURCE_FRESH_OPERATION_ID, SOURCE_RUNTIME_TENANT_ID, "world-one", "test"))
        .thenReturn(Optional.of(evidence));
    AuthoredSourceObserver first =
        authoredSourceCall(authoredSourceRequest(), SOURCE_GAME_SESSION_PEER);
    AuthoredSourceObserver retry =
        authoredSourceCall(authoredSourceRequest(), SOURCE_GAME_SESSION_PEER);
    assertNull(first.errorCode);
    assertTrue(first.completed);
    assertEquals(first.value, retry.value);
    assertEquals(SOURCE_RUNTIME_REQUEST_ID.toString(), first.value.getRequestId());
    assertEquals(
        SOURCE_FRESH_CREATION_REQUEST_ID.toString(), first.value.getRegistrationRequestId());
    assertEquals(evidence.evidenceDigest(), first.value.getEvidenceDigest());
    assertEquals("tenant-one", first.value.getTenantSlug());
    assertEquals("world-one", first.value.getWorldSlug());
    assertEquals("Wörld", first.value.getWorldDisplayName());
    assertEquals(19L, first.value.getSourceGameRowId());
    verifyNoInteractions(repository, associationRepository);
  }

  @Test
  void authoredSourceReadDoesNotReturnAbsentChangedOrUnavailableEvidence() {
    when(authoredWorldRepository.read(
            SOURCE_FRESH_OPERATION_ID, SOURCE_RUNTIME_TENANT_ID, "world-one", "test"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(authoredSourceEvidence("other")))
        .thenThrow(new IllegalStateException("contradictory source"))
        .thenThrow(new DataAccessException("db", new SQLException("offline", "08006")));
    for (Status.Code expected :
        new Status.Code[] {
          Status.Code.NOT_FOUND, Status.Code.FAILED_PRECONDITION,
          Status.Code.FAILED_PRECONDITION, Status.Code.UNAVAILABLE
        }) {
      AuthoredSourceObserver observer =
          authoredSourceCall(authoredSourceRequest(), SOURCE_GAME_SESSION_PEER);
      assertEquals(expected, observer.errorCode);
      assertNull(observer.value);
      assertFalse(observer.completed);
    }
  }

  private static ResolveAuthoredWorldSourceRequest authoredSourceRequest() {
    return ResolveAuthoredWorldSourceRequest.newBuilder()
        .setRequestId(SOURCE_RUNTIME_REQUEST_ID.toString())
        .setOperationId(SOURCE_FRESH_OPERATION_ID.toString())
        .setCanonicalTenantId(SOURCE_RUNTIME_TENANT_ID.toString())
        .setWorldSlug("world-one")
        .build();
  }

  private static AuthoredWorldSourceEvidence authoredSourceEvidence(String namespace) {
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            namespace,
            SOURCE_FRESH_CREATION_REQUEST_ID,
            SOURCE_RUNTIME_TENANT_ID,
            "tenant-one",
            "world-one",
            "Wörld");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            namespace,
            SOURCE_FRESH_CREATION_REQUEST_ID,
            SOURCE_FRESH_OPERATION_ID,
            requestDigest,
            SOURCE_RUNTIME_TENANT_ID,
            "tenant-one",
            "world-one",
            "Wörld",
            19L,
            "source-key",
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        namespace,
        SOURCE_FRESH_CREATION_REQUEST_ID,
        SOURCE_FRESH_OPERATION_ID,
        requestDigest,
        SOURCE_RUNTIME_TENANT_ID,
        "tenant-one",
        "world-one",
        "Wörld",
        19L,
        "source-key",
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private AuthoredSourceObserver authoredSourceCall(
      ResolveAuthoredWorldSourceRequest request, String peer) {
    AuthoredSourceObserver observer = new AuthoredSourceObserver();
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            peer == null ? null : GrpcPeerIdentity.parseUri(peer).orElse(null))
        .run(() -> service.resolveAuthoredWorldSource(request, observer));
    return observer;
  }

  private static final class AuthoredSourceObserver
      implements StreamObserver<ResolveAuthoredWorldSourceResponse> {
    private ResolveAuthoredWorldSourceResponse value;
    private Status.Code errorCode;
    private boolean completed;

    @Override
    public void onNext(ResolveAuthoredWorldSourceResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      errorCode = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final UUID TENANT_ID = UUID.fromString("87426bb3-a733-43f0-9c8e-2e379cbdf7ec");
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  private final GameRepository repository = mock(GameRepository.class);
  private final GameSessionTenantAssociationRepository associationRepository =
      mock(GameSessionTenantAssociationRepository.class);
  private final PublishedReleaseBundleService publishedReleaseBundleService =
      mock(PublishedReleaseBundleService.class);
  private final TenantIdentityGrpcService service =
      new TenantIdentityGrpcService(
          mock(GameTenantCreationRepository.class),
          repository,
          associationRepository,
          publishedReleaseBundleService,
          "test",
          new ObjectMapper(),
          authoredWorldRepository);

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
    verifyNoInteractions(associationRepository);
  }

  @Test
  void leavesRuntimeReadInactiveWhenWorkloadNamespaceIsNotConfigured() {
    TenantIdentityGrpcService inactiveService =
        new TenantIdentityGrpcService(
            mock(GameTenantCreationRepository.class),
            repository,
            associationRepository,
            publishedReleaseBundleService,
            "",
            new ObjectMapper(),
            authoredWorldRepository);
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

  @Test
  void mapsQueryTimeoutOwnerReadToUnavailableWithoutReturningPartialEvidence() {
    when(repository.findRuntimeTenantIdentityByCanonicalTenantId(TENANT_ID))
        .thenThrow(new QueryTimeoutException("query timed out"));

    TestObserver observer =
        call(request(TENANT_ID.toString(), REQUEST_ID.toString()), GAME_SESSION_URI);

    assertThat(status(observer)).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
  }

  @Test
  void resolvesPublishedPolicyForExactSameNamespaceGameSessionPeer() {
    RealmEntryPolicy policy =
        RealmEntryPolicy.parse(
            "{\"schemaVersion\":1,\"worldSlug\":\"earth\",\"worldDisplayName\":\"Earth\","
                + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main Realm\","
                + "\"visible\":true,\"publicProduction\":false,\"stateScope\":\"ISOLATED\","
                + "\"entryPolicy\":\"PRESEEDED_ONLY\"}",
            new ObjectMapper());
    var evidence =
        PublishedRealmEntryPolicyEvidence.create(
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            TENANT_ID,
            "NEW_GAME_ROW",
            42L,
            "source-game-key",
            7L,
            3,
            18L,
            "sha256:" + "1".repeat(64),
            "publish-workflow-3",
            "manifest-3",
            policy,
            new ObjectMapper());
    when(publishedReleaseBundleService.resolvePublishedRealmEntryPolicy(
            TENANT_ID, 7L, "earth", "main"))
        .thenReturn(evidence);

    PolicyObserver observer =
        callPolicy(policyRequest(TENANT_ID.toString(), 7L, "earth", "main"), GAME_SESSION_URI);

    assertThat(observer.failure).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(observer.value.getSchemaVersion()).isEqualTo(1);
    assertThat(observer.value.getTargetNamespace()).isEqualTo("test");
    assertThat(observer.value.getCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
    assertThat(observer.value.getVersionId()).isEqualTo(7L);
    assertThat(observer.value.getVersionNumber()).isEqualTo(3);
    assertThat(observer.value.getPolicyId()).isEqualTo(evidence.policyId().toString());
    assertThat(observer.value.getSourceRevisionId()).isEqualTo(18L);
    assertThat(observer.value.getSourceGameRowId()).isEqualTo(42L);
    assertThat(observer.value.getSourceGameTenantKey()).isEqualTo("source-game-key");
    assertThat(observer.value.getReleaseBundleIdentity())
        .isEqualTo(evidence.releaseBundleIdentity());
    assertThat(observer.value.getWorldSlug()).isEqualTo("earth");
    assertThat(observer.value.getRealmSlug()).isEqualTo("main");
    assertThat(observer.value.getVisible()).isTrue();
    assertThat(observer.value.getPublicProduction()).isFalse();
    assertThat(observer.value.getPolicyDigest()).isEqualTo(evidence.policyDigest());
    verify(publishedReleaseBundleService)
        .resolvePublishedRealmEntryPolicy(TENANT_ID, 7L, "earth", "main");
  }

  @Test
  void publishedPolicyReadRejectsWrongPeerAndOpenOrMalformedSelectorsBeforeOwnerRead() {
    assertThat(
            status(
                callPolicy(
                    policyRequest(TENANT_ID.toString(), 7L, "earth", "main"),
                    "spiffe://firemud/ns/other/sa/game-session-service")))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            status(
                callPolicy(
                    policyRequest(TENANT_ID.toString(), 7L, "earth", "main"),
                    "spiffe://firemud/ns/test/sa/account-service")))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            status(
                callPolicy(
                    policyRequest(TENANT_ID.toString().toUpperCase(), 7L, "earth", "main"),
                    GAME_SESSION_URI)))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            status(
                callPolicy(
                    policyRequest(TENANT_ID.toString(), 7L, "Earth", "main"), GAME_SESSION_URI)))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    ResolvePublishedRealmEntryPolicyRequest unknownFieldRequest =
        policyRequest(TENANT_ID.toString(), 7L, "earth", "main").toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertThat(status(callPolicy(unknownFieldRequest, GAME_SESSION_URI)))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    org.mockito.Mockito.verifyNoInteractions(publishedReleaseBundleService);
  }

  @Test
  void publishedPolicyReadMapsMissingAndUnavailableEvidenceWithoutPartialResponse() {
    when(publishedReleaseBundleService.resolvePublishedRealmEntryPolicy(
            TENANT_ID, 7L, "earth", "main"))
        .thenThrow(new PublishedRealmEntryPolicyNotFoundException());
    PolicyObserver missing =
        callPolicy(policyRequest(TENANT_ID.toString(), 7L, "earth", "main"), GAME_SESSION_URI);
    assertThat(status(missing)).isEqualTo(Status.Code.NOT_FOUND);
    assertThat(missing.value).isNull();

    doThrow(new DataAccessResourceFailureException("unavailable"))
        .when(publishedReleaseBundleService)
        .resolvePublishedRealmEntryPolicy(TENANT_ID, 7L, "earth", "main");
    PolicyObserver unavailable =
        callPolicy(policyRequest(TENANT_ID.toString(), 7L, "earth", "main"), GAME_SESSION_URI);
    assertThat(status(unavailable)).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(unavailable.value).isNull();
  }

  @Test
  void resolvesCompletePublishedPolicySetWithOwnerBoundCountAndDigest() {
    ObjectMapper mapper = new ObjectMapper();
    RealmEntryPolicy policy =
        RealmEntryPolicy.parse(
            "{\"schemaVersion\":1,\"worldSlug\":\"earth\",\"worldDisplayName\":\"Earth\","
                + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main Realm\","
                + "\"visible\":true,\"publicProduction\":true,\"stateScope\":\"ISOLATED\","
                + "\"entryPolicy\":\"PRESEEDED_ONLY\"}",
            mapper);
    var row =
        PublishedRealmEntryPolicyEvidence.create(
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            TENANT_ID,
            "NEW_GAME_ROW",
            42L,
            "source-game-key",
            7L,
            3,
            18L,
            "sha256:" + "1".repeat(64),
            "publish-workflow-3",
            "manifest-3",
            policy,
            mapper);
    var set =
        PublishedRealmEntryPolicySetEvidence.create(
            TENANT_ID,
            7L,
            3,
            row.releaseBundleIdentity(),
            "publish-workflow-3",
            "manifest-3",
            java.util.List.of(row),
            mapper);
    when(publishedReleaseBundleService.listPublishedRealmEntryPolicies(TENANT_ID, 7L))
        .thenReturn(set);

    PolicySetObserver observer =
        callPolicySet(policySetRequest(TENANT_ID.toString(), 7L), GAME_SESSION_URI);

    assertThat(observer.failure).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(observer.value.getSchemaVersion()).isEqualTo(1);
    assertThat(observer.value.getTargetNamespace()).isEqualTo("test");
    assertThat(observer.value.getCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
    assertThat(observer.value.getVersionId()).isEqualTo(7L);
    assertThat(observer.value.getVersionNumber()).isEqualTo(3);
    assertThat(observer.value.getPolicyCount()).isEqualTo(1);
    assertThat(observer.value.getPoliciesCount()).isEqualTo(1);
    assertThat(observer.value.getPolicySetDigest()).isEqualTo(set.policySetDigest());
    assertThat(observer.value.getPolicies(0).getPolicyDigest()).isEqualTo(row.policyDigest());
    assertThat(observer.value.getPolicies(0).getPolicyJson()).isEqualTo(policy.canonicalJson());
    verify(publishedReleaseBundleService).listPublishedRealmEntryPolicies(TENANT_ID, 7L);
  }

  @Test
  void completePolicySetReadRejectsWrongPeerAndOpenRequestsBeforeOwnerRead() {
    assertThat(
            status(
                callPolicySet(
                    policySetRequest(TENANT_ID.toString(), 7L),
                    "spiffe://firemud/ns/other/sa/game-session-service")))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            status(
                callPolicySet(
                    policySetRequest(TENANT_ID.toString(), 7L),
                    "spiffe://firemud/ns/test/sa/account-service")))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            status(
                callPolicySet(
                    policySetRequest(TENANT_ID.toString().toUpperCase(), 7L), GAME_SESSION_URI)))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(status(callPolicySet(policySetRequest(TENANT_ID.toString(), 0L), GAME_SESSION_URI)))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    ListPublishedRealmEntryPoliciesRequest unknownFieldRequest =
        policySetRequest(TENANT_ID.toString(), 7L).toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertThat(status(callPolicySet(unknownFieldRequest, GAME_SESSION_URI)))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(publishedReleaseBundleService);
  }

  @Test
  void completePolicySetReadMapsMissingAndUnavailableWithoutPartialResponse() {
    when(publishedReleaseBundleService.listPublishedRealmEntryPolicies(TENANT_ID, 7L))
        .thenThrow(new PublishedRealmEntryPolicyNotFoundException());
    PolicySetObserver missing =
        callPolicySet(policySetRequest(TENANT_ID.toString(), 7L), GAME_SESSION_URI);
    assertThat(status(missing)).isEqualTo(Status.Code.NOT_FOUND);
    assertThat(missing.value).isNull();

    doThrow(new DataAccessResourceFailureException("unavailable"))
        .when(publishedReleaseBundleService)
        .listPublishedRealmEntryPolicies(TENANT_ID, 7L);
    PolicySetObserver unavailable =
        callPolicySet(policySetRequest(TENANT_ID.toString(), 7L), GAME_SESSION_URI);
    assertThat(status(unavailable)).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(unavailable.value).isNull();
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

  private PolicyObserver callPolicy(
      ResolvePublishedRealmEntryPolicyRequest request, String peerUri) {
    PolicyObserver observer = new PolicyObserver();
    Runnable invocation = () -> service.resolvePublishedRealmEntryPolicy(request, observer);
    if (peerUri == null) {
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, null).run(invocation);
    } else {
      GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(invocation);
    }
    return observer;
  }

  private PolicySetObserver callPolicySet(
      ListPublishedRealmEntryPoliciesRequest request, String peerUri) {
    PolicySetObserver observer = new PolicySetObserver();
    Runnable invocation = () -> service.listPublishedRealmEntryPolicies(request, observer);
    if (peerUri == null) {
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, null).run(invocation);
    } else {
      GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(invocation);
    }
    return observer;
  }

  private static ResolvePublishedRealmEntryPolicyRequest policyRequest(
      String tenantId, long versionId, String worldSlug, String realmSlug) {
    return ResolvePublishedRealmEntryPolicyRequest.newBuilder()
        .setCanonicalTenantId(tenantId)
        .setVersionId(versionId)
        .setWorldSlug(worldSlug)
        .setRealmSlug(realmSlug)
        .build();
  }

  private static ListPublishedRealmEntryPoliciesRequest policySetRequest(
      String tenantId, long versionId) {
    return ListPublishedRealmEntryPoliciesRequest.newBuilder()
        .setCanonicalTenantId(tenantId)
        .setVersionId(versionId)
        .build();
  }

  private static Status.Code status(TestObserver observer) {
    assertThat(observer.grpcFailure).isTrue();
    assertThat(observer.failure).isNotNull();
    return observer.failure;
  }

  private static Status.Code status(PolicyObserver observer) {
    assertThat(observer.grpcFailure).isTrue();
    assertThat(observer.failure).isNotNull();
    return observer.failure;
  }

  private static Status.Code status(PolicySetObserver observer) {
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

  private static final class PolicyObserver
      implements StreamObserver<ResolvePublishedRealmEntryPolicyResponse> {
    private ResolvePublishedRealmEntryPolicyResponse value;
    private Status.Code failure;
    private boolean grpcFailure;
    private boolean completed;

    @Override
    public void onNext(ResolvePublishedRealmEntryPolicyResponse response) {
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

  private static final class PolicySetObserver
      implements StreamObserver<ListPublishedRealmEntryPoliciesResponse> {
    private ListPublishedRealmEntryPoliciesResponse value;
    private Status.Code failure;
    private boolean grpcFailure;
    private boolean completed;

    @Override
    public void onNext(ListPublishedRealmEntryPoliciesResponse response) {
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

  @Nested
  class FreshCreation {
    private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
    private static final String GAME_SESSION_URI =
        "spiffe://firemud/ns/test/sa/game-session-service";
    private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OPERATION_ID =
        UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID TENANT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String SOURCE_KEY = "fresh-owner-key-91";
    private static final String NAME = "Fresh Realm";
    private static final String REQUEST_DIGEST =
        GameTenantCreationDigest.requestDigest("test", REQUEST_ID, SOURCE_KEY, NAME, null);

    private final GameTenantCreationRepository repository =
        mock(GameTenantCreationRepository.class);
    private final TenantIdentityGrpcService service =
        new TenantIdentityGrpcService(
            repository,
            mock(GameRepository.class),
            mock(GameSessionTenantAssociationRepository.class),
            mock(PublishedReleaseBundleService.class),
            "test",
            new ObjectMapper(),
            mock(GameAuthoredWorldSourceRepository.class));

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
          new TenantIdentityGrpcService(
              repository,
              mock(GameRepository.class),
              mock(GameSessionTenantAssociationRepository.class),
              mock(PublishedReleaseBundleService.class),
              "",
              new ObjectMapper(),
              mock(GameAuthoredWorldSourceRepository.class));
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
      Observer namespaceMismatch =
          call(request(REQUEST_ID.toString(), REQUEST_DIGEST), ACCOUNT_URI);
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
  }
}
