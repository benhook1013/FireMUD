package net.firedevops.firemud.gamesession.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ArtifactState;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository.FixtureCanonicalInstanceLaunch;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository.FixtureCanonicalRealmCatalog;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository.FreshTenantAssociation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

class RunOwnedCanonicalLaunchPreparationOwnerTest {
  private static final String RUN_ID = "compose-smoke-fixture-20261014";
  private static final String PROJECT_NAME = "firemud-smoke-compose-fixture-20261014";
  private static final String CAPABILITY_PATH = "fixture/capability.json";
  private static final String SOURCE_RECEIPT_PATH = "fixture/source-receipt.json";
  private static final String CLAIM_DIGEST = "d".repeat(64);
  private static final String OPERATION_ID =
      RunOwnedInitialAdmissionFixtureWireCodec.fixtureOperationId(
              CLAIM_DIGEST, RUN_ID, PROJECT_NAME, "a".repeat(64), "b".repeat(64))
          .toString();
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("9db3ab6f-cc09-49d2-8f93-84484397a120");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("3f773701-e812-44f5-8ae0-7268d543beed");
  private static final UUID CREATION_REQUEST_ID =
      UUID.fromString("57341fb9-55ee-42f4-842e-6e6305c22411");
  private static final UUID CREATION_OPERATION_ID =
      UUID.fromString("d156e17a-d759-4db5-a456-c082b5d74419");
  private static final UUID ASSOCIATION_OPERATION_ID =
      UUID.fromString("a6fb2f17-747f-423e-a7cc-e8c577b6d44e");
  private static final UUID INTAKE_OPERATION_ID =
      UUID.fromString("a05f8aae-b4dd-4e11-8a92-0271c60ec6c0");
  private static final UUID INTAKE_REQUEST_ID =
      UUID.fromString("ce432a95-d19a-47bc-9266-874158e5990c");
  private static final UUID REALM_ID = UUID.fromString("23d39978-9d44-4e1a-8659-998ff9239b01");
  private static final UUID NAMESPACE_ID = UUID.fromString("ed1b4d88-81f8-4404-af7c-9a5dc91d3043");
  private static final UUID GAME_INSTANCE_UUID =
      UUID.fromString("7556e829-d531-4c61-b8d4-350c8514c4f1");

  @Test
  void commitsExactStartingOwnerAllocationThenPerformsSeparateIndependentReadback() {
    Fixture fixture = fixture(true);

    var result =
        fixture.coordinator.prepareVerifiedFixtureLaunch(fixture.capability, fixture.sourceReceipt);

    assertThat(result.launchEvidence().sourceEvidence())
        .isEqualTo(fixture.sourceReceipt.sourceEvidence());
    assertThat(result.launchEvidence().creationEvidence())
        .isEqualTo(fixture.sourceReceipt.creationEvidence());
    assertThat(result.launchEvidence().fixturePolicySeal())
        .isEqualTo(fixture.sourceReceipt.fixturePolicySeal());
    assertThat(result.launchEvidence().fixtureCapability().gameSessionLeafSha256())
        .isEqualTo(fixture.capability.gameSessionLeafSha256());
    assertThat(result.launchEvidence().fixtureCapability().worldManagementLeafSha256())
        .isEqualTo(fixture.capability.worldManagementLeafSha256());
    assertThat(result.launchEvidence().fixtureCapability().caCertificateSha256())
        .isEqualTo(fixture.capability.caCertificateSha256());
    assertThat(result.launch()).isEqualTo(fixture.allocation);
    assertThat(result.launch().status()).isEqualTo(CurrentGameInstanceStatus.STARTING);
    assertThat(result.launch().gameInstanceId()).isPositive();
    assertThat(result.launch().gameInstanceUuid()).isEqualTo(GAME_INSTANCE_UUID);
    assertThat(result.launch().catalog().snapshot().tenantId()).isEqualTo(CANONICAL_TENANT_ID);
    assertThat(result.launch().catalog().snapshot().realmId()).isEqualTo(REALM_ID);
    assertThat(result.launch().catalog().snapshot().playableStateNamespaceId())
        .isEqualTo(NAMESPACE_ID);
    assertThat(result.launch().catalog().association().gameSessionTenantId()).isEqualTo(71L);
    assertThat(result.launch().catalog().association().gameSessionTenantId()).isNotEqualTo(1L);
    assertThat(fixture.capability.tenantId()).isEqualTo(1L);
    assertThat(result.launchEvidence().sourceEvidence().sourceGameTenantKey()).isEqualTo("1");
    assertThat(result.launchEvidence().creationEvidence().sourceGameTenantKey()).isEqualTo("1");

    ArgumentCaptor<RunOwnedInitialAdmissionLaunchEvidence> evidenceCaptor =
        ArgumentCaptor.forClass(RunOwnedInitialAdmissionLaunchEvidence.class);
    InOrder order = inOrder(fixture.launchOwner);
    order.verify(fixture.launchOwner).createVerifiedFixtureStartingLaunch(evidenceCaptor.capture());
    RunOwnedInitialAdmissionLaunchEvidence evidence = evidenceCaptor.getValue();
    order.verify(fixture.launchOwner).readVerifiedFixtureLaunch(same(evidence));
  }

  @Test
  void explicitPreparationLoadsFixedVerifiedReceiptBeforeOwnerWrite() {
    Fixture fixture = fixture(true);

    try (MockedStatic<RunOwnedInitialAdmissionFixtureCapability> loader =
        mockStatic(RunOwnedInitialAdmissionFixtureCapability.class)) {
      stubFixtureLoaders(loader, fixture, fixture.sourceReceipt);
      var result = fixture.coordinator.prepareFixedVerifiedFixtureLaunch();

      assertThat(result.launch()).isEqualTo(fixture.allocation);
      assertThat(result.launchEvidence().sourceEvidence())
          .isEqualTo(fixture.sourceReceipt.sourceEvidence());
      assertThat(result.launchEvidence().fixturePolicySeal())
          .isEqualTo(fixture.sourceReceipt.fixturePolicySeal());
    }

    InOrder order = inOrder(fixture.launchOwner);
    order.verify(fixture.launchOwner).createVerifiedFixtureStartingLaunch(any());
    order.verify(fixture.launchOwner).readVerifiedFixtureLaunch(any());
  }

  @Test
  void exactReplayUsesTheSameOperationAndReturnsTheSameStartingAllocation() {
    Fixture fixture = fixture(true);

    var first =
        fixture.coordinator.prepareVerifiedFixtureLaunch(fixture.capability, fixture.sourceReceipt);
    var replay =
        fixture.coordinator.prepareVerifiedFixtureLaunch(fixture.capability, fixture.sourceReceipt);

    assertThat(replay.launch()).isEqualTo(first.launch());
    assertThat(replay.launchEvidence().sourceEvidence())
        .isEqualTo(first.launchEvidence().sourceEvidence());
    assertThat(replay.launchEvidence().fixtureCapability().operationId()).isEqualTo(OPERATION_ID);
    verify(fixture.launchOwner, times(2)).createVerifiedFixtureStartingLaunch(any());
    verify(fixture.launchOwner, times(2)).readVerifiedFixtureLaunch(any());
  }

  @Test
  void rejectsChangedPrivateAllocationAndChangedLifecycleStatus() {
    Fixture changedIdentity = fixture(true);
    FixtureCanonicalInstanceLaunch differentIdentity =
        new FixtureCanonicalInstanceLaunch(
            changedIdentity.allocation.gameInstanceId(),
            UUID.fromString("bb76a459-f249-4a9d-9045-7c748d2e2b1f"),
            changedIdentity.allocation.catalog(),
            changedIdentity.allocation.status(),
            changedIdentity.allocation.rowVersion());
    when(changedIdentity.launchOwner.readVerifiedFixtureLaunch(any()))
        .thenReturn(differentIdentity);

    assertThatThrownBy(
            () ->
                changedIdentity.coordinator.prepareVerifiedFixtureLaunch(
                    changedIdentity.capability, changedIdentity.sourceReceipt))
        .isInstanceOf(IllegalStateException.class);

    Fixture changedStatus = fixture(true);
    FixtureCanonicalInstanceLaunch running =
        new FixtureCanonicalInstanceLaunch(
            changedStatus.allocation.gameInstanceId(),
            changedStatus.allocation.gameInstanceUuid(),
            changedStatus.allocation.catalog(),
            CurrentGameInstanceStatus.RUNNING,
            changedStatus.allocation.rowVersion());
    when(changedStatus.launchOwner.readVerifiedFixtureLaunch(any())).thenReturn(running);

    assertThatThrownBy(
            () ->
                changedStatus.coordinator.prepareVerifiedFixtureLaunch(
                    changedStatus.capability, changedStatus.sourceReceipt))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsReceiptWithChangedFixturePolicySealBeforeOwnerWrite() {
    Fixture fixture = fixture(true);
    var originalSeal = fixture.sourceReceipt.fixturePolicySeal();
    var changedSeal =
        new RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal(
            originalSeal.schema(),
            originalSeal.ownerService(),
            originalSeal.operationId(),
            "sha256:" + "e".repeat(64),
            "sha256:" + "f".repeat(64));
    var changedReceipt =
        new RunOwnedInitialAdmissionFixtureCapability.SourceReceipt(
            fixture.sourceReceipt.schema(),
            fixture.sourceReceipt.runId(),
            fixture.sourceReceipt.composeProjectName(),
            fixture.sourceReceipt.fixtureOperationId(),
            fixture.sourceReceipt.ownerService(),
            fixture.sourceReceipt.gameSessionUriSan(),
            fixture.sourceReceipt.gameSessionLeafSha256(),
            fixture.sourceReceipt.caCertificateSha256(),
            fixture.sourceReceipt.worldManagementUriSan(),
            fixture.sourceReceipt.worldManagementLeafSha256(),
            fixture.sourceReceipt.sourceEvidence(),
            fixture.sourceReceipt.launchReadEvidence(),
            fixture.sourceReceipt.creationEvidence(),
            changedSeal);

    assertThatThrownBy(
            () ->
                fixture.coordinator.prepareVerifiedFixtureLaunch(
                    fixture.capability, changedReceipt))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(fixture.launchOwner);
  }

  @Test
  void fixedEvidenceReadRejectsChangedReceiptIdentityAcrossReads() {
    Fixture fixture = fixture(true);
    var changedReceipt = sourceReceipt(fixture.capability, 30L);
    try (MockedStatic<RunOwnedInitialAdmissionFixtureCapability> loader =
        mockStatic(RunOwnedInitialAdmissionFixtureCapability.class)) {
      stubFixtureLoaders(loader, fixture, fixture.sourceReceipt, changedReceipt);
      assertThat(fixture.coordinator.loadFixedVerifiedEvidence().sourceEvidence())
          .isEqualTo(fixture.sourceReceipt.sourceEvidence());
      assertThatThrownBy(fixture.coordinator::loadFixedVerifiedEvidence)
          .isInstanceOf(IllegalStateException.class);
    }
    verifyNoInteractions(fixture.launchOwner);
  }

  @Test
  void disabledOwnerDoesNotReadFixturePathsOrWriteLaunchOwner() {
    Fixture fixture = fixture(false);

    try (MockedStatic<RunOwnedInitialAdmissionFixtureCapability> loader =
        mockStatic(RunOwnedInitialAdmissionFixtureCapability.class)) {
      assertThatThrownBy(fixture.coordinator::loadFixedVerifiedEvidence)
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(fixture.coordinator::prepareFixedVerifiedFixtureLaunch)
          .isInstanceOf(IllegalStateException.class);

      loader.verify(
          () -> RunOwnedInitialAdmissionFixtureCapability.load(any(), any(), any(), any()),
          never());
      loader.verify(
          () ->
              RunOwnedInitialAdmissionFixtureCapability.loadSourceReceipt(
                  any(), any(), any(), any(), any()),
          never());
    }

    verifyNoInteractions(fixture.launchOwner);
  }

  @Test
  void fixedEvidenceReadRequiresTheExactWorldPeerAndRetainedWorldLeafBeforeDecodingSelectors() {
    SessionContext.clear();
    Fixture fixture = fixture(true);
    GrpcPeerCertificateEvidence certificate = mock(GrpcPeerCertificateEvidence.class);
    when(certificate.leafSha256()).thenReturn(fixture.capability.worldManagementLeafSha256());
    GrpcPeerIdentity worldPeer =
        GrpcPeerIdentity.parseUri(
                RunOwnedInitialAdmissionFixtureCapability.WORLD_MANAGEMENT_URI_SAN)
            .orElseThrow();
    Context worldContext =
        Context.current()
            .withValue(GrpcPeerIdentity.CONTEXT_KEY, worldPeer)
            .withValue(GrpcPeerCertificateEvidence.CONTEXT_KEY, certificate);

    try (MockedStatic<RunOwnedInitialAdmissionFixtureCapability> loader =
        mockStatic(RunOwnedInitialAdmissionFixtureCapability.class)) {
      stubFixtureLoaders(loader, fixture, fixture.sourceReceipt);
      Context previous = worldContext.attach();
      try {
        var evidence = fixture.coordinator.loadFixedVerifiedEvidenceForWorldPeer();
        assertThat(evidence.fixtureCapability().operationId()).isEqualTo(OPERATION_ID);

        when(certificate.leafSha256()).thenReturn("d".repeat(64));
        assertThatThrownBy(fixture.coordinator::loadFixedVerifiedEvidenceForWorldPeer)
            .isInstanceOf(AdminAuthorizationException.class);

        when(certificate.leafSha256()).thenReturn(fixture.capability.worldManagementLeafSha256());
        Context wrongWorldContext =
            Context.current()
                .withValue(
                    GrpcPeerIdentity.CONTEXT_KEY,
                    GrpcPeerIdentity.parseUri(
                            "spiffe://firemud/ns/other/sa/world-management-service")
                        .orElseThrow())
                .withValue(GrpcPeerCertificateEvidence.CONTEXT_KEY, certificate);
        Context worldPrevious = wrongWorldContext.attach();
        try {
          assertThatThrownBy(fixture.coordinator::loadFixedVerifiedEvidenceForWorldPeer)
              .isInstanceOf(AdminAuthorizationException.class);
        } finally {
          wrongWorldContext.detach(worldPrevious);
        }
      } finally {
        worldContext.detach(previous);
        SessionContext.clear();
      }
    }
  }

  private static Fixture fixture(boolean enabled) {
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    RunOwnedInitialAdmissionFixtureCapability capability = capability();
    RunOwnedInitialAdmissionFixtureCapability.SourceReceipt receipt =
        sourceReceipt(capability, 29L);
    FixtureCanonicalInstanceLaunch allocation = allocation(capability, receipt);
    GameSessionCanonicalInstanceLaunchRepository launchOwner =
        mock(GameSessionCanonicalInstanceLaunchRepository.class);
    when(launchOwner.createVerifiedFixtureStartingLaunch(any())).thenReturn(allocation);
    when(launchOwner.readVerifiedFixtureLaunch(any())).thenReturn(allocation);
    RunOwnedCanonicalLaunchPreparationOwner coordinator =
        new RunOwnedCanonicalLaunchPreparationOwner(
            enabled,
            CAPABILITY_PATH,
            SOURCE_RECEIPT_PATH,
            RUN_ID,
            PROJECT_NAME,
            grpc,
            launchOwner,
            RunOwnedInitialAdmissionFixtureCapability.SOURCE_TARGET_NAMESPACE);
    return new Fixture(coordinator, capability, receipt, grpc, launchOwner, allocation);
  }

  private static void stubFixtureLoaders(
      MockedStatic<RunOwnedInitialAdmissionFixtureCapability> loader,
      Fixture fixture,
      RunOwnedInitialAdmissionFixtureCapability.SourceReceipt... receipts) {
    loader
        .when(
            () ->
                RunOwnedInitialAdmissionFixtureCapability.load(
                    any(Path.class), eq(RUN_ID), eq(PROJECT_NAME), same(fixture.grpcProperties)))
        .thenReturn(fixture.capability);
    AtomicInteger index = new AtomicInteger();
    loader
        .when(
            () ->
                RunOwnedInitialAdmissionFixtureCapability.loadSourceReceipt(
                    any(Path.class),
                    eq(RUN_ID),
                    eq(PROJECT_NAME),
                    same(fixture.capability),
                    same(fixture.grpcProperties)))
        .thenAnswer(invocation -> receipts[Math.min(index.getAndIncrement(), receipts.length - 1)]);
    loader
        .when(
            () ->
                RunOwnedInitialAdmissionFixtureCapability.toWireCapability(
                    any(RunOwnedInitialAdmissionFixtureCapability.class)))
        .thenCallRealMethod();
  }

  private static RunOwnedInitialAdmissionFixtureCapability capability() {
    return new RunOwnedInitialAdmissionFixtureCapability(
        RUN_ID,
        PROJECT_NAME,
        OPERATION_ID,
        1L,
        17L,
        27L,
        RunOwnedInitialAdmissionFixtureCapability.WORLD_SLUG,
        RunOwnedInitialAdmissionFixtureCapability.WORLD_DISPLAY_NAME,
        RunOwnedInitialAdmissionFixtureCapability.REALM_SLUG,
        RunOwnedInitialAdmissionFixtureCapability.REALM_DISPLAY_NAME,
        true,
        true,
        false,
        "SHARED",
        "ALLOW_NEW",
        "a".repeat(64),
        RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN,
        "b".repeat(64),
        RunOwnedInitialAdmissionFixtureCapability.WORLD_MANAGEMENT_URI_SAN,
        "c".repeat(64),
        CLAIM_DIGEST);
  }

  private static RunOwnedInitialAdmissionFixtureCapability.SourceReceipt sourceReceipt(
      RunOwnedInitialAdmissionFixtureCapability capability, long sourceGameRowId) {
    String targetNamespace = RunOwnedInitialAdmissionFixtureCapability.SOURCE_TARGET_NAMESPACE;
    String tenantSlug = "fixture-tenant";
    String sourceGameTenantKey = "1";
    String provenanceKind = "NEW_GAME_ROW";
    UUID registrationRequestId = UUID.fromString(capability.operationId());
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            targetNamespace,
            registrationRequestId,
            CANONICAL_TENANT_ID,
            tenantSlug,
            capability.worldSlug(),
            capability.worldDisplayName());
    AuthoredWorldSourceEvidence sourceEvidence =
        new AuthoredWorldSourceEvidence(
            1,
            targetNamespace,
            registrationRequestId,
            SOURCE_OPERATION_ID,
            sourceRequestDigest,
            CANONICAL_TENANT_ID,
            tenantSlug,
            capability.worldSlug(),
            capability.worldDisplayName(),
            sourceGameRowId,
            sourceGameTenantKey,
            provenanceKind,
            AuthoredWorldSourceDigest.evidenceDigest(
                targetNamespace,
                registrationRequestId,
                SOURCE_OPERATION_ID,
                sourceRequestDigest,
                CANONICAL_TENANT_ID,
                tenantSlug,
                capability.worldSlug(),
                capability.worldDisplayName(),
                sourceGameRowId,
                sourceGameTenantKey,
                provenanceKind));
    String creationRequestDigest =
        GameTenantCreationDigest.requestDigest(
            targetNamespace,
            CREATION_REQUEST_ID,
            sourceGameTenantKey,
            capability.worldDisplayName(),
            null);
    FreshTenantCreationEvidence creationEvidence =
        new FreshTenantCreationEvidence(
            1,
            targetNamespace,
            CREATION_REQUEST_ID,
            CREATION_OPERATION_ID,
            creationRequestDigest,
            CANONICAL_TENANT_ID,
            sourceGameRowId,
            sourceGameTenantKey,
            provenanceKind,
            GameTenantCreationDigest.evidenceDigest(
                targetNamespace,
                CREATION_REQUEST_ID,
                CREATION_OPERATION_ID,
                creationRequestDigest,
                CANONICAL_TENANT_ID,
                sourceGameRowId,
                sourceGameTenantKey,
                provenanceKind));
    var launchRead = launchReadEvidence(capability, sourceEvidence);
    var wireLaunchRead =
        new RunOwnedInitialAdmissionFixtureWireCodec.LaunchReadEvidence(
            launchRead.schema(),
            launchRead.gameTemplateId(),
            launchRead.publishedReleaseBundle(),
            launchRead.versionAssetArtifactState(),
            launchRead.versionState(),
            launchRead.launchDescriptor());
    String policyRequestDigest =
        RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestDigest(
            RunOwnedInitialAdmissionFixtureCapability.toWireCapability(capability),
            creationEvidence,
            sourceEvidence,
            wireLaunchRead);
    var seal =
        new RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal(
            RunOwnedInitialAdmissionFixtureWireCodec.FIXTURE_POLICY_SEAL_SCHEMA,
            RunOwnedInitialAdmissionFixtureCapability.SOURCE_RECEIPT_OWNER_SERVICE,
            registrationRequestId,
            policyRequestDigest,
            RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyEvidenceDigest(
                registrationRequestId, policyRequestDigest));
    return new RunOwnedInitialAdmissionFixtureCapability.SourceReceipt(
        RunOwnedInitialAdmissionFixtureCapability.SOURCE_RECEIPT_SCHEMA,
        capability.runId(),
        capability.composeProjectName(),
        capability.operationId(),
        RunOwnedInitialAdmissionFixtureCapability.SOURCE_RECEIPT_OWNER_SERVICE,
        capability.gameSessionUriSan(),
        capability.gameSessionLeafSha256(),
        capability.caCertificateSha256(),
        capability.worldManagementUriSan(),
        capability.worldManagementLeafSha256(),
        sourceEvidence,
        launchRead,
        creationEvidence,
        seal);
  }

  private static RunOwnedInitialAdmissionFixtureCapability.LaunchReadEvidence launchReadEvidence(
      RunOwnedInitialAdmissionFixtureCapability capability,
      AuthoredWorldSourceEvidence sourceEvidence) {
    String manifestHash = "sha256:" + "a".repeat(64);
    String artifactDigest = "sha256:" + "b".repeat(64);
    PublishedReleaseBundle bundle =
        PublishedReleaseBundle.newBuilder()
            .setId(77L)
            .setVersionId(11L)
            .setVersionNumber(1)
            .setAttestationSchemaVersion("v1")
            .setPublishWorkflowId("publish-11")
            .setManifestHash(manifestHash)
            .addRequiredManifestAssetKeys("manifest.json")
            .setGenerationConfigRevision("genrev-11")
            .setPublishedAt("2026-04-15T10:00:00")
            .setPublishedReleaseBundleRef("prb:7:11:77")
            .setCanonicalTenantId(sourceEvidence.canonicalTenantId().toString())
            .setCanonicalVersionId("10000000-0000-4000-8000-000000000011")
            .setManifestSchemaVersion(1)
            .addArtifactDigests(
                net.firedevops.firemud.gamedesign.v1.PublishedArtifactDigest.newBuilder()
                    .setUsageKey("manifest.json")
                    .setArtifactKind("manifest")
                    .setImmutableObjectKey("artifacts/sha256/" + artifactDigest.substring(7))
                    .setContentDigest(artifactDigest)
                    .setContentType("application/json")
                    .setArtifactSchemaVersion(1)
                    .build())
            .build();
    VersionAssetArtifactState artifactState =
        VersionAssetArtifactState.newBuilder()
            .setTenantId(Long.toString(capability.tenantId()))
            .setVersionId(11L)
            .setArtifactState(ArtifactState.ARTIFACT_STATE_PUBLISHED)
            .setStateEpoch(2L)
            .setManifestHash(manifestHash)
            .setUpdatedAt("2026-04-15T10:00:00")
            .addExportedManifestAssetKeys("manifest.json")
            .setExportedVersionNumber(1)
            .build();
    VersionStateSnapshot versionState =
        VersionStateSnapshot.newBuilder()
            .setTenantId(Long.toString(capability.tenantId()))
            .setVersionId(11L)
            .setVersionState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
            .setVersionStateEpoch(77L)
            .setUpdatedAt("2026-04-15T10:00:00")
            .build();
    AuthoredWorldLaunchDescriptorEvidence.Request request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            RunOwnedInitialAdmissionFixtureCapability.SOURCE_TARGET_NAMESPACE,
            capability.operationId(),
            sourceEvidence.canonicalTenantId(),
            sourceEvidence.worldSlug(),
            sourceEvidence.operationId(),
            sourceEvidence.evidenceDigest(),
            capability.gameTemplateId(),
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    var descriptorEvidence =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            "launch-descriptor-fixture-11",
            bundle.getVersionId(),
            false,
            null,
            "{}",
            bundle.getGenerationConfigRevision(),
            versionState.getVersionStateEpoch(),
            bundle.getId(),
            bundle.getPublishedReleaseBundleRef(),
            false,
            null);
    LaunchDescriptor descriptor =
        AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(descriptorEvidence);
    return new RunOwnedInitialAdmissionFixtureCapability.LaunchReadEvidence(
        RunOwnedInitialAdmissionFixtureCapability.LAUNCH_READ_EVIDENCE_SCHEMA,
        capability.gameTemplateId(),
        bundle,
        artifactState,
        versionState,
        descriptor);
  }

  private static FixtureCanonicalInstanceLaunch allocation(
      RunOwnedInitialAdmissionFixtureCapability capability,
      RunOwnedInitialAdmissionFixtureCapability.SourceReceipt receipt) {
    AuthoredWorldSourceEvidence source = receipt.sourceEvidence();
    FreshTenantCreationEvidence creation = receipt.creationEvidence();
    String intakeRequestDigest =
        net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldIntakeDigest
            .requestDigest(INTAKE_REQUEST_ID, source);
    IntakeReceipt intakeReceipt =
        new IntakeReceipt(
            INTAKE_OPERATION_ID,
            INTAKE_REQUEST_ID,
            intakeRequestDigest,
            source,
            net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldIntakeDigest
                .receiptDigest(INTAKE_OPERATION_ID, intakeRequestDigest, source.evidenceDigest()));
    CanonicalRealmCatalogSnapshot snapshot =
        new CanonicalRealmCatalogSnapshot(
            RunOwnedInitialAdmissionFixtureCapability.SOURCE_TARGET_NAMESPACE,
            source.canonicalTenantId(),
            source.tenantSlug(),
            source.worldSlug(),
            REALM_ID,
            capability.realmSlug(),
            capability.realmDisplayName(),
            capability.visible(),
            capability.publicProductionRealm(),
            capability.stateScope(),
            NAMESPACE_ID,
            capability.characterCreationPolicy(),
            1L,
            creation.creationRequestId(),
            "sha256:" + "e".repeat(64),
            "sha256:" + "f".repeat(64),
            intakeReceipt);
    FreshTenantAssociation association =
        new FreshTenantAssociation(
            RunOwnedInitialAdmissionFixtureCapability.SOURCE_TARGET_NAMESPACE,
            source.canonicalTenantId(),
            71L,
            ASSOCIATION_OPERATION_ID,
            creation);
    return new FixtureCanonicalInstanceLaunch(
        42L,
        GAME_INSTANCE_UUID,
        new FixtureCanonicalRealmCatalog(snapshot, association),
        CurrentGameInstanceStatus.STARTING,
        0L);
  }

  private record Fixture(
      RunOwnedCanonicalLaunchPreparationOwner coordinator,
      RunOwnedInitialAdmissionFixtureCapability capability,
      RunOwnedInitialAdmissionFixtureCapability.SourceReceipt sourceReceipt,
      CommonGrpcClientProperties grpcProperties,
      GameSessionCanonicalInstanceLaunchRepository launchOwner,
      FixtureCanonicalInstanceLaunch allocation) {}
}
