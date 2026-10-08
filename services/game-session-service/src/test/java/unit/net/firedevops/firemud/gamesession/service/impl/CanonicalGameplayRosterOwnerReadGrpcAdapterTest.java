package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameplayRosterOwnerReadGrpcAdapter;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import support.net.firedevops.firemud.gamesession.PublishedRealmPolicyEvidenceFixture;

class CanonicalGameplayRosterOwnerReadGrpcAdapterTest {
  private static final String NAMESPACE = "gameplay";
  private static final String ENTITY_SERVICE = "entity-management-service";
  private static final UUID ACCOUNT = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");

  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void exactEntityPeerReceivesExactProofAndEchoedAccountBinding() {
    var provider = provider();
    var source = mock(CanonicalPublishedPlayerRouteReadService.class);
    var request = request();
    var typedRequest =
        new CanonicalGameplayRosterOwnerReadEvidence.Request(
            uuid(request.getRequestUuid()),
            ACCOUNT,
            NAMESPACE,
            TENANT,
            "earth",
            REALM,
            "main",
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            VERSION,
            71L,
            73L,
            82L);
    var proof = ownerProof();
    byte[] proofBytes = GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(proof);
    var policySet =
        PublishedRealmPolicyEvidenceFixture.fixture(
                TENANT,
                VERSION,
                73L,
                42L,
                "source-game-key-42",
                "RETAINED_GAME_V29",
                3,
                PublishedRealmPolicyEvidenceFixture.policy("main", true, true, "SHARED"))
            .set();
    byte[] policyBytes = policySet.canonicalBytes();
    var evidence =
        new CanonicalGameplayRosterOwnerReadEvidence(
            typedRequest, "a".repeat(64), proof, policySet);
    when(provider.getIfAvailable()).thenReturn(source);
    when(source.readCurrent(typedRequest)).thenReturn(evidence);

    var observer = responseObserver();
    var adapter = new CanonicalGameplayRosterOwnerReadGrpcAdapter(provider, NAMESPACE);
    runAsPeer(
        ENTITY_SERVICE,
        NAMESPACE,
        () -> adapter.getCanonicalGameplayRosterOwnerRead(request, observer));

    var responseCaptor =
        org.mockito.ArgumentCaptor.forClass(GetCanonicalGameplayRosterOwnerReadResponse.class);
    verify(observer).onNext(responseCaptor.capture());
    verify(observer).onCompleted();
    verify(source).readCurrent(typedRequest);
    assertThat(responseCaptor.getValue().getRequest()).isEqualTo(request);
    assertThat(responseCaptor.getValue().getRequest().getCanonicalAccountUuid())
        .isEqualTo(ACCOUNT.toString());
    assertThat(responseCaptor.getValue().getGameSessionOwnerProof().toByteArray())
        .isEqualTo(proofBytes);
    assertThat(responseCaptor.getValue().getPublishedPolicySetEvidence().toByteArray())
        .isEqualTo(policyBytes);
  }

  @Test
  void rejectsWrongPeerNamespaceCallerContextAndTargetNamespaceBeforeSourceRead() {
    var provider = provider();
    var adapter = new CanonicalGameplayRosterOwnerReadGrpcAdapter(provider, NAMESPACE);
    var observer = responseObserver();

    runAsPeer(
        "world-management-service",
        NAMESPACE,
        () -> adapter.getCanonicalGameplayRosterOwnerRead(request(), observer));
    assertStatus(observer, Status.Code.PERMISSION_DENIED);
    verify(provider, never()).getIfAvailable();

    var wrongNamespaceObserver = responseObserver();
    runAsPeer(
        ENTITY_SERVICE,
        "other",
        () -> adapter.getCanonicalGameplayRosterOwnerRead(request(), wrongNamespaceObserver));
    assertStatus(wrongNamespaceObserver, Status.Code.PERMISSION_DENIED);
    verify(provider, never()).getIfAvailable();

    SessionContext.setContext(ACCOUNT.toString(), List.of("player"), Map.of());
    var callerContextObserver = responseObserver();
    runAsPeer(
        ENTITY_SERVICE,
        NAMESPACE,
        () -> adapter.getCanonicalGameplayRosterOwnerRead(request(), callerContextObserver));
    assertStatus(callerContextObserver, Status.Code.PERMISSION_DENIED);
    SessionContext.clear();

    var targetObserver = responseObserver();
    runAsPeer(
        ENTITY_SERVICE,
        NAMESPACE,
        () ->
            adapter.getCanonicalGameplayRosterOwnerRead(
                request().toBuilder().setTargetNamespace("other").build(), targetObserver));
    assertStatus(targetObserver, Status.Code.PERMISSION_DENIED);
    verify(provider, never()).getIfAvailable();
  }

  @Test
  void rejectsUnknownAndPartialRequestsAndUnavailableOwnerTransport() {
    var provider = provider();
    var adapter = new CanonicalGameplayRosterOwnerReadGrpcAdapter(provider, NAMESPACE);
    var unknownObserver = responseObserver();
    var unknown =
        request().toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build();
    runAsPeer(
        ENTITY_SERVICE,
        NAMESPACE,
        () -> adapter.getCanonicalGameplayRosterOwnerRead(unknown, unknownObserver));
    assertStatus(unknownObserver, Status.Code.INVALID_ARGUMENT);
    verify(provider, never()).getIfAvailable();

    when(provider.getIfAvailable()).thenReturn(null);
    var unavailableObserver = responseObserver();
    runAsPeer(
        ENTITY_SERVICE,
        NAMESPACE,
        () -> adapter.getCanonicalGameplayRosterOwnerRead(request(), unavailableObserver));
    assertStatus(unavailableObserver, Status.Code.UNAVAILABLE);

    var source = mock(CanonicalPublishedPlayerRouteReadService.class);
    when(provider.getIfAvailable()).thenReturn(source);
    when(source.readCurrent(any(CanonicalGameplayRosterOwnerReadEvidence.Request.class)))
        .thenThrow(
            new CanonicalPublishedPlayerRouteReadService.GameDesignUnavailableException(
                "private backend detail", new IllegalStateException("transport")));
    var transportObserver = responseObserver();
    runAsPeer(
        ENTITY_SERVICE,
        NAMESPACE,
        () -> adapter.getCanonicalGameplayRosterOwnerRead(request(), transportObserver));
    assertStatus(transportObserver, Status.Code.UNAVAILABLE);
  }

  @Test
  void staleOrInvalidCurrentSelectorsFailClosed() {
    var provider = provider();
    var source = mock(CanonicalPublishedPlayerRouteReadService.class);
    when(provider.getIfAvailable()).thenReturn(source);
    when(source.readCurrent(any(CanonicalGameplayRosterOwnerReadEvidence.Request.class)))
        .thenThrow(new CanonicalPublishedPlayerRouteReadService.StaleAuthorityException("stale"));
    var adapter = new CanonicalGameplayRosterOwnerReadGrpcAdapter(provider, NAMESPACE);
    var staleObserver = responseObserver();
    runAsPeer(
        ENTITY_SERVICE,
        NAMESPACE,
        () -> adapter.getCanonicalGameplayRosterOwnerRead(request(), staleObserver));
    assertStatus(staleObserver, Status.Code.FAILED_PRECONDITION);

    when(source.readCurrent(any(CanonicalGameplayRosterOwnerReadEvidence.Request.class)))
        .thenThrow(
            new CanonicalPublishedPlayerRouteReadService.InvalidAuthorityException(
                "changed policy or source"));
    var invalidObserver = responseObserver();
    runAsPeer(
        ENTITY_SERVICE,
        NAMESPACE,
        () -> adapter.getCanonicalGameplayRosterOwnerRead(request(), invalidObserver));
    assertStatus(invalidObserver, Status.Code.FAILED_PRECONDITION);
  }

  private static void assertStatus(StreamObserver<?> observer, Status.Code expected) {
    var errorCaptor = org.mockito.ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(errorCaptor.capture());
    assertThat(Status.fromThrowable(errorCaptor.getValue()).getCode()).isEqualTo(expected);
    assertThat(errorCaptor.getValue()).isInstanceOf(StatusRuntimeException.class);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static StreamObserver<GetCanonicalGameplayRosterOwnerReadResponse> responseObserver() {
    return mock(StreamObserver.class);
  }

  private static GetCanonicalGameplayRosterOwnerReadRequest request() {
    return GetCanonicalGameplayRosterOwnerReadRequest.newBuilder()
        .setSchemaVersion(1)
        .setRequestUuid("66666666-6666-4666-8666-666666666666")
        .setCanonicalAccountUuid(ACCOUNT.toString())
        .setTargetNamespace(NAMESPACE)
        .setCanonicalTenantUuid(TENANT.toString())
        .setWorldSlug("earth")
        .setRealmUuid(REALM.toString())
        .setRealmSlug("main")
        .setPlayableStateNamespaceUuid(PLAYABLE_NAMESPACE.toString())
        .setPlayableStateScope("SHARED")
        .setCanonicalGameInstanceUuid(INSTANCE.toString())
        .setCanonicalVersionUuid(VERSION.toString())
        .setExpectedCatalogRevision(71L)
        .setExpectedPointerVersion(73L)
        .setExpectedActiveWorldEpoch(82L)
        .build();
  }

  private static ObjectProvider<CanonicalPublishedPlayerRouteReadService> provider() {
    @SuppressWarnings("unchecked")
    ObjectProvider<CanonicalPublishedPlayerRouteReadService> provider = mock(ObjectProvider.class);
    return provider;
  }

  private static GameSessionCanonicalInitialAdmissionOwnerProof ownerProof() {
    var request =
        new WorldCanonicalInitialAdmissionHold.Request(
            NAMESPACE,
            TENANT,
            "earth",
            REALM,
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            VERSION,
            82L,
            "initial-admission-owner-request",
            "a".repeat(64),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            71L,
            null);
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            request,
            uuid("77777777-7777-4777-8777-777777777777"),
            uuid("88888888-8888-4888-8888-888888888888"));
    return new GameSessionCanonicalInitialAdmissionOwnerProof(
        hold,
        GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
        73L,
        91L,
        "sha256:" + "b".repeat(64),
        false,
        Instant.parse("2026-10-08T03:04:05Z"));
  }

  private static void runAsPeer(String service, String namespace, Runnable action) {
    String uri = "spiffe://firemud/ns/" + namespace + "/sa/" + service;
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, new GrpcPeerIdentity(uri, namespace, service))
        .run(action);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
