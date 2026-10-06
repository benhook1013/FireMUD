package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.service.PublishedRealmAdmissionOwnerReadProof;
import net.firedevops.firemud.gamesession.service.PublishedRealmAdmissionOwnerReadRequest;
import net.firedevops.firemud.gamesession.service.PublishedRealmAdmissionOwnerReadService;
import net.firedevops.firemud.gamesession.service.impl.GameSessionControlPlaneGrpcService;
import net.firedevops.firemud.gamesession.v1.GetPublishedRealmAdmissionOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetPublishedRealmAdmissionOwnerReadResponse;
import net.firedevops.firemud.gamesession.v1.PublishedRealmAdmissionAttemptStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PublishedRealmAdmissionOwnerReadGrpcServiceTest {
  private static final String NAMESPACE = "gameplay";
  private static final String ENTITY_SERVICE = "entity-management-service";
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID REALM_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID PLAYABLE_STATE_NAMESPACE_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID ATTEMPT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final String REQUEST_ID = "88888888-8888-4888-8888-888888888888";
  private static final String REQUEST_DIGEST = "a".repeat(64);
  private static final long GAME_SESSION_TENANT_ID = 70123L;
  private static final long PUBLISHED_VERSION_ID = 44L;
  private static final long CATALOG_REVISION = 8L;
  private static final long POINTER_VERSION = 1L;
  private static final long POINTER_AUDIT_ID = 601L;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final PublishedRealmAdmissionOwnerReadService ownerReadService =
      mock(PublishedRealmAdmissionOwnerReadService.class);
  private GameSessionControlPlaneGrpcService grpcService;

  @BeforeEach
  void setUp() {
    SessionContext.clear();
    grpcService =
        new GameSessionControlPlaneGrpcService(
            null, null, null, null, null, null, new SimpleMeterRegistry());
    grpcService.configurePublishedRealmAdmissionOwnerReadBoundary(ownerReadService, NAMESPACE);
  }

  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void returnsTheCompleteTypedOwnerCompositionForTheExactRequest() {
    PublishedRealmAdmissionOwnerReadProof proof = proof(POINTER_VERSION);
    when(ownerReadService.read(any(PublishedRealmAdmissionOwnerReadRequest.class)))
        .thenReturn(proof);
    CapturingObserver<GetPublishedRealmAdmissionOwnerReadResponse> response =
        new CapturingObserver<>();

    runAsEntityManagementPeer(
        () -> grpcService.getPublishedRealmAdmissionOwnerRead(request(), capture(response)));

    GetPublishedRealmAdmissionOwnerReadResponse result = response.value;
    assertThat(response.failureCode).isNull();
    assertThat(result).isNotNull();
    assertThat(result.getProof().getTargetNamespace()).isEqualTo(NAMESPACE);
    assertThat(result.getProof().getCanonicalTenantId()).isEqualTo(CANONICAL_TENANT_ID.toString());
    assertThat(result.getProof().getGameSessionTenantId())
        .isEqualTo(Long.toString(GAME_SESSION_TENANT_ID));
    assertThat(result.getProof().getCatalogRevision()).isEqualTo(CATALOG_REVISION);
    assertThat(result.getProof().getExpectedPointerVersion()).isEqualTo(POINTER_VERSION);
    assertThat(result.getProof().getRealmId()).isEqualTo(REALM_ID.toString());
    assertThat(result.getProof().getPlayableStateNamespaceId())
        .isEqualTo(PLAYABLE_STATE_NAMESPACE_ID.toString());
    assertThat(result.getProof().getPublishedPolicySet().getPolicyCount()).isEqualTo(1);
    assertThat(result.getProof().getPublishedPolicySet().getPolicies(0).getSourceGameRowId())
        .isEqualTo(91L);
    assertThat(result.getProof().getPublishedPolicySet().getPolicies(0).getPolicyDigest())
        .isEqualTo(proof.selectedPolicyEvidence().policyDigest());
    assertThat(result.getProof().getSelectedPolicyEvidence().getWorldSlug()).isEqualTo("earth");
    assertThat(result.getProof().getGameInstanceId()).isEqualTo("93");
    assertThat(result.getProof().getPublishedVersionId())
        .isEqualTo(Long.toString(PUBLISHED_VERSION_ID));
    assertThat(result.getProof().getActiveLifecycleEpoch()).isEqualTo(12L);
    assertThat(result.getProof().getGameTemplateId()).isEqualTo("55");
    assertThat(result.getProof().getLaunchDescriptorId()).isEqualTo("launch-earth-v44");
    assertThat(result.getProof().getReleaseBundleId()).isEqualTo("810");
    assertThat(result.getProof().getPublishedReleaseBundleRef()).isEqualTo("world-release-ref");
    assertThat(result.getProof().getVersionStateEpoch()).isEqualTo(6L);
    assertThat(result.getProof().getInitialAdmissionRequestId()).isEqualTo(REQUEST_ID);
    assertThat(result.getProof().getRequestDigest()).isEqualTo(REQUEST_DIGEST);
    assertThat(result.getProof().getInitialAdmissionAttemptId()).isEqualTo(ATTEMPT_ID.toString());
    assertThat(result.getProof().getPointerAuditId()).isEqualTo(Long.toString(POINTER_AUDIT_ID));
    assertThat(result.getProof().getInitialAdmissionAttemptStatus())
        .isEqualTo(
            PublishedRealmAdmissionAttemptStatus
                .PUBLISHED_REALM_ADMISSION_ATTEMPT_STATUS_COMMITTED);
    assertThat(result.getProof().getPointerAuditRequestId()).isEqualTo(REQUEST_ID);
    assertThat(result.getProof().getPointerAuditRequestDigest()).isEqualTo(REQUEST_DIGEST);
    verify(ownerReadService)
        .read(
            new PublishedRealmAdmissionOwnerReadRequest(
                NAMESPACE,
                CANONICAL_TENANT_ID,
                GAME_SESSION_TENANT_ID,
                "earth",
                "main",
                CATALOG_REVISION,
                POINTER_VERSION));
  }

  @Test
  void rejectsMalformedOrNonCanonicalTargetWithoutCallingOwnerAuthority() {
    CapturingObserver<GetPublishedRealmAdmissionOwnerReadResponse> response =
        new CapturingObserver<>();
    GetPublishedRealmAdmissionOwnerReadRequest malformed =
        request().toBuilder().setGameSessionTenantId("070123").build();

    runAsEntityManagementPeer(
        () -> grpcService.getPublishedRealmAdmissionOwnerRead(malformed, capture(response)));

    assertThat(response.value).isNull();
    assertThat(response.failureCode).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(ownerReadService);
  }

  @Test
  void rejectsMismatchedOrUnavailableOwnerEvidenceWithoutReturningPartialProof() {
    when(ownerReadService.read(any(PublishedRealmAdmissionOwnerReadRequest.class)))
        .thenReturn(proof(2L));
    CapturingObserver<GetPublishedRealmAdmissionOwnerReadResponse> response =
        new CapturingObserver<>();

    runAsEntityManagementPeer(
        () -> grpcService.getPublishedRealmAdmissionOwnerRead(request(), capture(response)));

    assertThat(response.value).isNull();
    assertThat(response.failureCode).isEqualTo(Status.Code.UNAVAILABLE);
  }

  @Test
  void rejectsWrongPeerAndMissingOwnerDependencyWithoutReturningProof() {
    CapturingObserver<GetPublishedRealmAdmissionOwnerReadResponse> denied =
        new CapturingObserver<>();
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/world-management-service",
                NAMESPACE,
                "world-management-service"))
        .run(() -> grpcService.getPublishedRealmAdmissionOwnerRead(request(), capture(denied)));
    assertThat(denied.value).isNull();
    assertThat(denied.failureCode).isEqualTo(Status.Code.PERMISSION_DENIED);

    CapturingObserver<GetPublishedRealmAdmissionOwnerReadResponse> wrongTarget =
        new CapturingObserver<>();
    runAsEntityManagementPeer(
        () ->
            grpcService.getPublishedRealmAdmissionOwnerRead(
                request().toBuilder().setTargetNamespace("other-namespace").build(),
                capture(wrongTarget)));
    assertThat(wrongTarget.value).isNull();
    assertThat(wrongTarget.failureCode).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(ownerReadService);

    grpcService.configurePublishedRealmAdmissionOwnerReadBoundary(null, NAMESPACE);
    CapturingObserver<GetPublishedRealmAdmissionOwnerReadResponse> unavailable =
        new CapturingObserver<>();
    runAsEntityManagementPeer(
        () -> grpcService.getPublishedRealmAdmissionOwnerRead(request(), capture(unavailable)));
    assertThat(unavailable.value).isNull();
    assertThat(unavailable.failureCode).isEqualTo(Status.Code.UNAVAILABLE);
  }

  private static StreamObserver<GetPublishedRealmAdmissionOwnerReadResponse> capture(
      CapturingObserver<GetPublishedRealmAdmissionOwnerReadResponse> response) {
    return response;
  }

  private static final class CapturingObserver<T> implements StreamObserver<T> {
    private T value;
    private Status.Code failureCode;

    @Override
    public void onNext(T next) {
      value = next;
    }

    @Override
    public void onError(Throwable throwable) {
      failureCode = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {}
  }

  private static GetPublishedRealmAdmissionOwnerReadRequest request() {
    return GetPublishedRealmAdmissionOwnerReadRequest.newBuilder()
        .setTargetNamespace(NAMESPACE)
        .setCanonicalTenantId(CANONICAL_TENANT_ID.toString())
        .setGameSessionTenantId(Long.toString(GAME_SESSION_TENANT_ID))
        .setWorldSlug("earth")
        .setRealmSlug("main")
        .setExpectedCatalogRevision(CATALOG_REVISION)
        .setExpectedPointerVersion(POINTER_VERSION)
        .build();
  }

  private static PublishedRealmAdmissionOwnerReadProof proof(long pointerVersion) {
    String workflow = "publish:published-admission-owner-read-test";
    String manifest = "manifest-published-admission-owner-read-test";
    String releaseIdentity =
        PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
            CANONICAL_TENANT_ID, PUBLISHED_VERSION_ID, workflow, manifest, JSON);
    String policyJson =
        "{\"schemaVersion\":1,\"worldSlug\":\"earth\",\"worldDisplayName\":\"Earth\","
            + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main\",\"visible\":true,"
            + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
            + "\"entryPolicy\":\"PRESEEDED_ONLY\"}";
    PublishedRealmEntryPolicyEvidence selected =
        PublishedRealmEntryPolicyEvidence.create(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            CANONICAL_TENANT_ID,
            "RETAINED_GAME_V29",
            91L,
            "gd-tenant-91",
            PUBLISHED_VERSION_ID,
            3,
            301L,
            releaseIdentity,
            workflow,
            manifest,
            RealmEntryPolicy.parse(policyJson, JSON),
            JSON);
    PublishedRealmEntryPolicySetEvidence policySet =
        PublishedRealmEntryPolicySetEvidence.create(
            CANONICAL_TENANT_ID,
            PUBLISHED_VERSION_ID,
            3,
            releaseIdentity,
            workflow,
            manifest,
            List.of(selected),
            JSON);
    return new PublishedRealmAdmissionOwnerReadProof(
        GAME_SESSION_TENANT_ID,
        CANONICAL_TENANT_ID,
        91L,
        "gd-tenant-91",
        "RETAINED_GAME_V29",
        policySet,
        selected,
        CATALOG_REVISION,
        UUID.fromString("33333333-3333-4333-8333-333333333333"),
        PLAYABLE_STATE_NAMESPACE_ID,
        RealmEntryPolicy.StateScope.SHARED,
        pointerVersion,
        93L,
        PUBLISHED_VERSION_ID,
        12L,
        55L,
        "launch-earth-v44",
        810L,
        "world-release-ref",
        6L,
        REQUEST_ID,
        REQUEST_DIGEST,
        ATTEMPT_ID,
        POINTER_AUDIT_ID);
  }

  private static void runAsEntityManagementPeer(Runnable action) {
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + ENTITY_SERVICE,
                NAMESPACE,
                ENTITY_SERVICE))
        .run(action);
  }
}
