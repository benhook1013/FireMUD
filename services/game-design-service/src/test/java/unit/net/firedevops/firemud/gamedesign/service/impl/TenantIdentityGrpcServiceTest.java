package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesRequest;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesResponse;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyRequest;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityResponse;
import org.jooq.exception.TooManyRowsException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

class TenantIdentityGrpcServiceTest {
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
          repository,
          associationRepository,
          publishedReleaseBundleService,
          "test",
          new ObjectMapper());

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
            repository,
            associationRepository,
            publishedReleaseBundleService,
            "",
            new ObjectMapper());
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
}
