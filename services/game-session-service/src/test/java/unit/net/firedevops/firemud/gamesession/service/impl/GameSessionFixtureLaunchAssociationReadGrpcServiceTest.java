package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.data.RunOwnedCanonicalLaunchPreparationOwner;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidence;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidenceTestFixture;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldIntakeDigest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository.FixtureCanonicalInstanceLaunch;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository.FixtureCanonicalRealmCatalog;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository.FreshTenantAssociation;
import net.firedevops.firemud.gamesession.v1.GetRunOwnedFixtureLaunchAssociationRequest;
import net.firedevops.firemud.gamesession.v1.GetRunOwnedFixtureLaunchAssociationResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class GameSessionFixtureLaunchAssociationReadGrpcServiceTest {
  private static final String TARGET_NAMESPACE = "dev";
  private static final String WORLD_URI = "spiffe://firemud/ns/dev/sa/world-management-service";
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("9db3ab6f-cc09-49d2-8f93-84484397a120");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("3f773701-e812-44f5-8ae0-7268d543beed");
  private static final UUID INTAKE_OPERATION_ID =
      UUID.fromString("a05f8aae-b4dd-4e11-8a92-0271c60ec6c0");
  private static final UUID INTAKE_REQUEST_ID =
      UUID.fromString("ce432a95-d19a-47bc-9266-874158e5990c");
  private static final UUID ASSOCIATION_OPERATION_ID =
      UUID.fromString("a6fb2f17-747f-423e-a7cc-e8c577b6d44e");
  private static final UUID REALM_ID = UUID.fromString("23d39978-9d44-4e1a-8659-998ff9239b01");
  private static final UUID PLAYABLE_NAMESPACE_ID =
      UUID.fromString("ed1b4d88-81f8-4404-af7c-9a5dc91d3043");
  private static final UUID GAME_INSTANCE_UUID =
      UUID.fromString("7556e829-d531-4c61-b8d4-350c8514c4f1");
  private static final String READ_REQUEST_ID = "90a5e6ef-12f4-49b4-8b07-00fa123d6f55";

  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void returnsTheExactStartingCatalogPrivateKeysCreationSourceAndSeal() {
    Fixture fixture = fixture(true, true);
    RecordingObserver<GetRunOwnedFixtureLaunchAssociationResponse> observer =
        invoke(fixture, request(fixture.evidence), exactWorldContext(fixture.evidence));

    assertNull(observer.failure);
    assertTrue(observer.completed);
    GetRunOwnedFixtureLaunchAssociationResponse response = observer.response;
    assertNotNull(response);
    var evidence = fixture.evidence;
    var capability = evidence.fixtureCapability();
    var source = evidence.sourceEvidence();
    var creation = evidence.creationEvidence();
    var seal = evidence.fixturePolicySeal();
    assertEquals(READ_REQUEST_ID, response.getReadRequestId());
    assertEquals(TARGET_NAMESPACE, response.getTargetNamespace());
    assertEquals(capability.operationId(), response.getFixtureOperationId());
    assertEquals(seal.evidenceDigest(), response.getExpectedFixturePolicyEvidenceDigest());
    assertEquals(71L, response.getGameSessionTenantId());
    assertEquals(808L, response.getGameInstanceId());
    assertEquals(CANONICAL_TENANT_ID.toString(), response.getCanonicalTenantId());
    assertEquals(GAME_INSTANCE_UUID.toString(), response.getGameInstanceUuid());
    assertEquals(
        evidence.publishedReleaseBundle().getCanonicalVersionId(),
        response.getCanonicalVersionId());
    assertEquals(REALM_ID.toString(), response.getCanonicalRealmId());
    assertEquals(PLAYABLE_NAMESPACE_ID.toString(), response.getPlayableStateNamespaceId());
    assertEquals(
        net.firedevops.firemud.entitymanagement.v1.PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        response.getPlayableStateScope());
    assertEquals(1L, response.getCatalogRevision());
    assertEquals("STARTING", response.getCurrentGameInstanceStatus());
    assertEquals(0L, response.getCurrentRowVersion());
    assertEquals(
        ByteString.copyFrom(evidence.fixturePolicyRequestBytes()),
        response.getFixturePolicyRequestBytes());
    assertEquals(seal.operationId().toString(), response.getFixturePolicySealOperationId());
    assertEquals(seal.requestDigest(), response.getFixturePolicySealRequestDigest());
    assertEquals(seal.evidenceDigest(), response.getFixturePolicySealEvidenceDigest());
    assertEquals("1", source.sourceGameTenantKey());
    assertEquals(source.sourceGameTenantKey(), creation.sourceGameTenantKey());

    InOrder order = inOrder(fixture.coordinator);
    order.verify(fixture.coordinator).loadFixedVerifiedEvidenceForWorldPeer();
    order.verify(fixture.coordinator).readFixedFixtureLaunch(same(evidence));
  }

  @Test
  void loadsFixedWorldEvidenceBeforeRejectingUnknownRequestFieldsWithoutReadingLaunchOwner() {
    Fixture fixture = fixture(true, true);
    var malformed =
        request(fixture.evidence).toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();

    RecordingObserver<GetRunOwnedFixtureLaunchAssociationResponse> observer =
        invoke(fixture, malformed, exactWorldContext(fixture.evidence));

    assertEquals(Status.Code.INVALID_ARGUMENT, status(observer.failure));
    assertNull(observer.response);
    verify(fixture.coordinator).loadFixedVerifiedEvidenceForWorldPeer();
    verify(fixture.coordinator, never()).readFixedFixtureLaunch(any());
  }

  @Test
  void rejectsNullRequestAfterAuthorizationAndDefaultOffChecksBeforeLoadingEvidence() {
    Fixture fixture = fixture(true, true);

    RecordingObserver<GetRunOwnedFixtureLaunchAssociationResponse> observer =
        invoke(fixture, null, exactWorldContext(fixture.evidence));

    assertEquals(Status.Code.INVALID_ARGUMENT, status(observer.failure));
    assertNull(observer.response);
    verifyNoInteractions(fixture.coordinator);
  }

  @Test
  void rejectsChangedSelectorAfterLoadingFixedEvidenceAndBeforeLaunchOwnerRead() {
    Fixture fixture = fixture(true, true);
    GetRunOwnedFixtureLaunchAssociationRequest changed =
        request(fixture.evidence).toBuilder()
            .setFixtureOperationId("8dc28d55-1321-4661-82c6-13436df05468")
            .build();

    RecordingObserver<GetRunOwnedFixtureLaunchAssociationResponse> observer =
        invoke(fixture, changed, exactWorldContext(fixture.evidence));

    assertEquals(Status.Code.INVALID_ARGUMENT, status(observer.failure));
    verify(fixture.coordinator).loadFixedVerifiedEvidenceForWorldPeer();
    verify(fixture.coordinator, never()).readFixedFixtureLaunch(any());
  }

  @Test
  void mapsRetainedFixtureOwnerStateFailureToFailedPrecondition() {
    Fixture fixture = fixture(true, true);
    org.mockito.Mockito.doThrow(
            new IllegalArgumentException("retained fixture evidence is invalid"))
        .when(fixture.coordinator)
        .loadFixedVerifiedEvidenceForWorldPeer();

    RecordingObserver<GetRunOwnedFixtureLaunchAssociationResponse> observer =
        invoke(fixture, request(fixture.evidence), exactWorldContext(fixture.evidence));

    assertEquals(Status.Code.FAILED_PRECONDITION, status(observer.failure));
    assertNull(observer.response);
    verify(fixture.coordinator, never()).readFixedFixtureLaunch(any());
  }

  @Test
  void rejectsWrongWorldIdentityOrRetainedLeafBeforeLaunchOwnerRead() {
    Fixture fixture = fixture(true, true);
    var request = request(fixture.evidence);
    var wrongNamespace =
        context(
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/other/sa/world-management-service")
                .orElseThrow(),
            fixture.evidence.fixtureCapability().worldManagementLeafSha256());
    RecordingObserver<GetRunOwnedFixtureLaunchAssociationResponse> namespaceObserver =
        invoke(fixture, request, wrongNamespace);
    assertEquals(Status.Code.PERMISSION_DENIED, status(namespaceObserver.failure));

    var wrongService =
        context(
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/dev/sa/game-session-service")
                .orElseThrow(),
            fixture.evidence.fixtureCapability().worldManagementLeafSha256());
    RecordingObserver<GetRunOwnedFixtureLaunchAssociationResponse> serviceObserver =
        invoke(fixture, request, wrongService);
    assertEquals(Status.Code.PERMISSION_DENIED, status(serviceObserver.failure));
    verifyNoInteractions(fixture.coordinator);

    var wrongLeaf = context(worldPeer(), "d".repeat(64));
    RecordingObserver<GetRunOwnedFixtureLaunchAssociationResponse> leafObserver =
        invoke(fixture, request, wrongLeaf);
    assertEquals(Status.Code.PERMISSION_DENIED, status(leafObserver.failure));
    verify(fixture.coordinator).loadFixedVerifiedEvidenceForWorldPeer();
    verify(fixture.coordinator, never()).readFixedFixtureLaunch(any());
  }

  @Test
  void defaultOffAndMissingCoordinatorFailWithoutFixedOrOwnerReads() {
    Fixture disabled = fixture(false, true);
    RecordingObserver<GetRunOwnedFixtureLaunchAssociationResponse> disabledObserver =
        invoke(disabled, request(disabled.evidence), exactWorldContext(disabled.evidence));
    assertEquals(Status.Code.FAILED_PRECONDITION, status(disabledObserver.failure));
    verifyNoInteractions(disabled.coordinator);

    Fixture missing = fixture(true, false);
    RecordingObserver<GetRunOwnedFixtureLaunchAssociationResponse> missingObserver =
        invoke(missing, request(missing.evidence), exactWorldContext(missing.evidence));
    assertEquals(Status.Code.FAILED_PRECONDITION, status(missingObserver.failure));
  }

  private static Fixture fixture(boolean enabled, boolean withCoordinator) {
    RunOwnedInitialAdmissionLaunchEvidence evidence = evidence();
    FixtureCanonicalInstanceLaunch launch = launch(evidence);
    RunOwnedCanonicalLaunchPreparationOwner coordinator =
        withCoordinator ? mock(RunOwnedCanonicalLaunchPreparationOwner.class) : null;
    if (coordinator != null) {
      when(coordinator.loadFixedVerifiedEvidenceForWorldPeer())
          .thenAnswer(
              invocation -> {
                GrpcPeerIdentity peer = GrpcPeerIdentity.current();
                GrpcPeerCertificateEvidence certificate = GrpcPeerCertificateEvidence.current();
                if (peer == null
                    || !TARGET_NAMESPACE.equals(peer.namespace())
                    || !"world-management-service".equals(peer.service())
                    || !WORLD_URI.equals(peer.uri())
                    || SessionContext.hasAuthenticatedCallerContext()
                    || certificate == null
                    || !evidence
                        .fixtureCapability()
                        .worldManagementLeafSha256()
                        .equals(certificate.leafSha256())) {
                  throw new AdminAuthorizationException(
                      "Fixed fixture read requires the exact World workload peer and leaf");
                }
                return evidence;
              });
      when(coordinator.readFixedFixtureLaunch(same(evidence))).thenReturn(launch);
    }

    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            mock(GameSessionCommandControlPlaneService.class),
            mock(GameSessionRemoteControlPlaneService.class),
            mock(GameSessionRuntimeControlPlaneReadService.class),
            mock(GameSessionAdmissionPointerControlPlaneService.class),
            mock(GameSessionOperatorControlPlaneService.class),
            mock(GameSessionVersionUpgradeControlPlaneService.class),
            new SimpleMeterRegistry());
    service.configureRunOwnedFixtureLaunchAssociationReadBoundary(
        coordinator, enabled, TARGET_NAMESPACE);
    return new Fixture(service, coordinator, evidence, launch);
  }

  private static RunOwnedInitialAdmissionLaunchEvidence evidence() {
    String operationId = RunOwnedInitialAdmissionLaunchEvidenceTestFixture.operationId();
    StartSessionRequest start = new StartSessionRequest(1L, 17L, operationId, 27L);
    UUID requestId = UUID.fromString(operationId);
    String tenantSlug = "fixture-tenant";
    String worldSlug = "demo";
    String worldDisplayName = "Demo World";
    long sourceGameRowId = 29L;
    String sourceGameTenantKey = "1";
    String provenanceKind = "NEW_GAME_ROW";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            TARGET_NAMESPACE,
            requestId,
            CANONICAL_TENANT_ID,
            tenantSlug,
            worldSlug,
            worldDisplayName);
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            TARGET_NAMESPACE,
            requestId,
            SOURCE_OPERATION_ID,
            requestDigest,
            CANONICAL_TENANT_ID,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            sourceGameRowId,
            sourceGameTenantKey,
            provenanceKind,
            AuthoredWorldSourceDigest.evidenceDigest(
                TARGET_NAMESPACE,
                requestId,
                SOURCE_OPERATION_ID,
                requestDigest,
                CANONICAL_TENANT_ID,
                tenantSlug,
                worldSlug,
                worldDisplayName,
                sourceGameRowId,
                sourceGameTenantKey,
                provenanceKind));
    return RunOwnedInitialAdmissionLaunchEvidenceTestFixture.create(start, source);
  }

  private static FixtureCanonicalInstanceLaunch launch(
      RunOwnedInitialAdmissionLaunchEvidence evidence) {
    var source = evidence.sourceEvidence();
    var creation = evidence.creationEvidence();
    String intakeRequestDigest =
        GameSessionAuthoredWorldIntakeDigest.requestDigest(INTAKE_REQUEST_ID, source);
    IntakeReceipt intakeReceipt =
        new IntakeReceipt(
            INTAKE_OPERATION_ID,
            INTAKE_REQUEST_ID,
            intakeRequestDigest,
            source,
            GameSessionAuthoredWorldIntakeDigest.receiptDigest(
                INTAKE_OPERATION_ID, intakeRequestDigest, source.evidenceDigest()));
    var capability = evidence.fixtureCapability();
    CanonicalRealmCatalogSnapshot snapshot =
        new CanonicalRealmCatalogSnapshot(
            TARGET_NAMESPACE,
            source.canonicalTenantId(),
            source.tenantSlug(),
            source.worldSlug(),
            REALM_ID,
            capability.realmSlug(),
            capability.realmDisplayName(),
            capability.visible(),
            capability.publicProductionRealm(),
            capability.stateScope(),
            PLAYABLE_NAMESPACE_ID,
            capability.characterCreationPolicy(),
            1L,
            creation.creationRequestId(),
            "sha256:" + "e".repeat(64),
            "sha256:" + "f".repeat(64),
            intakeReceipt);
    FreshTenantAssociation association =
        new FreshTenantAssociation(
            TARGET_NAMESPACE, source.canonicalTenantId(), 71L, ASSOCIATION_OPERATION_ID, creation);
    return new FixtureCanonicalInstanceLaunch(
        808L,
        GAME_INSTANCE_UUID,
        new FixtureCanonicalRealmCatalog(snapshot, association),
        CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus.STARTING,
        0L);
  }

  private static GetRunOwnedFixtureLaunchAssociationRequest request(
      RunOwnedInitialAdmissionLaunchEvidence evidence) {
    return GetRunOwnedFixtureLaunchAssociationRequest.newBuilder()
        .setReadRequestId(READ_REQUEST_ID)
        .setTargetNamespace(TARGET_NAMESPACE)
        .setFixtureOperationId(evidence.fixtureCapability().operationId())
        .setExpectedFixturePolicyEvidenceDigest(evidence.fixturePolicySeal().evidenceDigest())
        .build();
  }

  private static RecordingObserver<GetRunOwnedFixtureLaunchAssociationResponse> invoke(
      Fixture fixture, GetRunOwnedFixtureLaunchAssociationRequest request, Context callerContext) {
    RecordingObserver<GetRunOwnedFixtureLaunchAssociationResponse> observer =
        new RecordingObserver<>();
    SessionContext.clear();
    Context previous = callerContext.attach();
    try {
      fixture.service.getRunOwnedFixtureLaunchAssociation(request, observer);
    } finally {
      callerContext.detach(previous);
    }
    return observer;
  }

  private static Context exactWorldContext(RunOwnedInitialAdmissionLaunchEvidence evidence) {
    return context(worldPeer(), evidence.fixtureCapability().worldManagementLeafSha256());
  }

  private static GrpcPeerIdentity worldPeer() {
    return GrpcPeerIdentity.parseUri(WORLD_URI).orElseThrow();
  }

  private static Context context(GrpcPeerIdentity peer, String leafSha256) {
    GrpcPeerCertificateEvidence certificate = mock(GrpcPeerCertificateEvidence.class);
    when(certificate.leafSha256()).thenReturn(leafSha256);
    return Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .withValue(GrpcPeerCertificateEvidence.CONTEXT_KEY, certificate);
  }

  private static Status.Code status(Throwable failure) {
    assertNotNull(failure);
    return Status.fromThrowable(failure).getCode();
  }

  private static final class RecordingObserver<T> implements StreamObserver<T> {
    private T response;
    private Throwable failure;
    private boolean completed;

    @Override
    public void onNext(T value) {
      response = value;
    }

    @Override
    public void onError(Throwable throwable) {
      failure = Status.fromThrowable(throwable).asRuntimeException();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  private record Fixture(
      GameSessionControlPlaneGrpcService service,
      RunOwnedCanonicalLaunchPreparationOwner coordinator,
      RunOwnedInitialAdmissionLaunchEvidence evidence,
      FixtureCanonicalInstanceLaunch launch) {}
}
