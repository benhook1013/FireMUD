package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldIntakeDigest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import org.junit.jupiter.api.Test;

class CanonicalPlayerRouteReadServiceTest {
  private static final String NAMESPACE = "gameplay";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");
  private static final String REQUEST_ID = "first-open-operation-17";

  @Test
  void composesCanonicalCatalogCurrentOpenProofAndOwnerQualifiedRuntimeReadOnly() {
    var catalogRepository = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var admissionRepository = mock(CanonicalInitialAdmissionRepository.class);
    var launchRepository = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    CanonicalRealmCatalogSnapshot catalog = catalogSnapshot();
    CanonicalInitialAdmissionOwnerProof proof = committedProof(7L);
    CanonicalInitialAdmissionLaunchTarget launch = launchTarget();
    when(catalogRepository.readVisiblePublicProductionSnapshots(NAMESPACE))
        .thenReturn(List.of(catalog));
    when(catalogRepository.readUniqueVisiblePublicProduction(NAMESPACE, TENANT))
        .thenReturn(Optional.of(catalog));
    when(admissionRepository.readCurrentOpenSnapshotForRealm(NAMESPACE, TENANT, REALM))
        .thenReturn(currentOpenSnapshot(proof));
    when(launchRepository.readForInitialAdmission(NAMESPACE, TENANT, REALM, INSTANCE))
        .thenReturn(Optional.of(launch));
    var service =
        new CanonicalPlayerRouteReadService(
            NAMESPACE, catalogRepository, admissionRepository, launchRepository);

    CanonicalPlayableTarget target = service.readVisiblePublicProductionTargets().getFirst();

    assertThat(target.targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(target.canonicalTenantId()).isEqualTo(TENANT);
    assertThat(target.realmId()).isEqualTo(REALM);
    assertThat(target.playableStateNamespaceId()).isEqualTo(PLAYABLE_NAMESPACE);
    assertThat(target.canonicalGameInstanceId()).isEqualTo(INSTANCE);
    assertThat(target.canonicalVersionId()).isEqualTo(VERSION);
    assertThat(target.runtimeVersionId()).isEqualTo(303L);
    assertThat(target.catalogRevision()).isEqualTo(1L);
    assertThat(target.pointerVersion()).isEqualTo(7L);
    assertThat(target.admissionPointerSnapshotDigest()).isEqualTo("d".repeat(64));
    assertThat(target.activeWorldEpoch()).isEqualTo(82L);
    assertThat(target.initialAdmissionRequestId()).isEqualTo(REQUEST_ID);
    assertThat(target.initialAdmissionRequestDigest()).isEqualTo("a".repeat(64));
    assertThat(target.initialAdmissionOriginKind()).isEqualTo(OriginKind.NO_PRIOR_POINTER);
    assertThat(target.expectedPriorPointerVersion()).isNull();
    assertThat(target.holdId()).isEqualTo(uuid("77777777-7777-4777-8777-777777777777"));
    assertThat(target.holdFence()).isEqualTo(uuid("88888888-8888-4888-8888-888888888888"));
    assertThat(target.holdBindingDigest()).isEqualTo("sha256:" + "c".repeat(64));
    assertThat(target.auditEventId()).isEqualTo(27L);
    assertThat(target.ownerProofDigest()).isEqualTo("sha256:" + "f".repeat(64));
    assertThat(target.ownerProofOutcome())
        .isEqualTo(CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    assertThat(target.positiveDurableAbort()).isFalse();
    assertThat(target.ownerProofTerminalAt()).isEqualTo(Instant.parse("2026-10-07T00:00:00Z"));
    assertThat(target.gameSessionTenantId()).isEqualTo(101L);
    assertThat(target.gameInstanceId()).isEqualTo(202L);

    assertThat(service.matchesCurrentTarget(target)).isTrue();
    verify(catalogRepository).readVisiblePublicProductionSnapshots(NAMESPACE);
    verify(catalogRepository).readUniqueVisiblePublicProduction(NAMESPACE, TENANT);
    verify(admissionRepository, times(2)).readCurrentOpenSnapshotForRealm(NAMESPACE, TENANT, REALM);
    verify(launchRepository, times(2)).readForInitialAdmission(NAMESPACE, TENANT, REALM, INSTANCE);
    verifyNoMoreInteractions(catalogRepository, admissionRepository, launchRepository);
  }

  @Test
  void readsCurrentTargetByCanonicalTenantSelectorWithoutCallerCounters() {
    var catalogRepository = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var admissionRepository = mock(CanonicalInitialAdmissionRepository.class);
    var launchRepository = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    var catalog = catalogSnapshot();
    var proof = committedProof(7L);
    var launch = launchTarget();
    when(catalogRepository.readUniqueVisiblePublicProduction(NAMESPACE, TENANT))
        .thenReturn(Optional.of(catalog));
    when(admissionRepository.readCurrentOpenSnapshotForRealm(NAMESPACE, TENANT, REALM))
        .thenReturn(currentOpenSnapshot(proof));
    when(launchRepository.readForInitialAdmission(NAMESPACE, TENANT, REALM, INSTANCE))
        .thenReturn(Optional.of(launch));
    var service = routeReadService(catalogRepository, admissionRepository, launchRepository);

    CanonicalPlayableTarget current = service.readCurrentTarget(TENANT);

    assertThat(current.canonicalTenantId()).isEqualTo(TENANT);
    assertThat(current.canonicalGameInstanceId()).isEqualTo(INSTANCE);
    assertThat(current.canonicalVersionId()).isEqualTo(VERSION);
    assertThat(current.catalogRevision()).isEqualTo(1L);
    assertThat(current.pointerVersion()).isEqualTo(7L);
    verify(catalogRepository).readUniqueVisiblePublicProduction(NAMESPACE, TENANT);
    verify(admissionRepository).readCurrentOpenSnapshotForRealm(NAMESPACE, TENANT, REALM);
    verify(launchRepository).readForInitialAdmission(NAMESPACE, TENANT, REALM, INSTANCE);
    verifyNoMoreInteractions(catalogRepository, admissionRepository, launchRepository);
  }

  @Test
  void changedCurrentPointerFenceDoesNotMatchPreviouslyDiscoveredTarget() {
    var catalogRepository = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var admissionRepository = mock(CanonicalInitialAdmissionRepository.class);
    var launchRepository = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    CanonicalRealmCatalogSnapshot catalog = catalogSnapshot();
    when(catalogRepository.readVisiblePublicProductionSnapshots(NAMESPACE))
        .thenReturn(List.of(catalog));
    when(catalogRepository.readUniqueVisiblePublicProduction(NAMESPACE, TENANT))
        .thenReturn(Optional.of(catalog));
    when(admissionRepository.readCurrentOpenSnapshotForRealm(NAMESPACE, TENANT, REALM))
        .thenReturn(
            currentOpenSnapshot(committedProof(7L), "d".repeat(64)),
            currentOpenSnapshot(committedProof(8L), "e".repeat(64)));
    CanonicalInitialAdmissionLaunchTarget launch = launchTarget();
    when(launchRepository.readForInitialAdmission(NAMESPACE, TENANT, REALM, INSTANCE))
        .thenReturn(Optional.of(launch));
    var service =
        new CanonicalPlayerRouteReadService(
            NAMESPACE, catalogRepository, admissionRepository, launchRepository);
    CanonicalPlayableTarget discovered = service.readVisiblePublicProductionTargets().getFirst();

    assertThat(service.matchesCurrentTarget(discovered)).isFalse();
  }

  @Test
  void changedPointerSnapshotDigestDoesNotMatchPreviouslyDiscoveredTarget() {
    var catalogRepository = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var admissionRepository = mock(CanonicalInitialAdmissionRepository.class);
    var launchRepository = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    CanonicalRealmCatalogSnapshot catalog = catalogSnapshot();
    CanonicalInitialAdmissionOwnerProof proof = committedProof(7L);
    when(catalogRepository.readVisiblePublicProductionSnapshots(NAMESPACE))
        .thenReturn(List.of(catalog));
    when(catalogRepository.readUniqueVisiblePublicProduction(NAMESPACE, TENANT))
        .thenReturn(Optional.of(catalog));
    when(admissionRepository.readCurrentOpenSnapshotForRealm(NAMESPACE, TENANT, REALM))
        .thenReturn(
            currentOpenSnapshot(proof, "d".repeat(64)), currentOpenSnapshot(proof, "e".repeat(64)));
    CanonicalInitialAdmissionLaunchTarget launch = launchTarget();
    when(launchRepository.readForInitialAdmission(NAMESPACE, TENANT, REALM, INSTANCE))
        .thenReturn(Optional.of(launch));
    var service =
        new CanonicalPlayerRouteReadService(
            NAMESPACE, catalogRepository, admissionRepository, launchRepository);
    CanonicalPlayableTarget discovered = service.readVisiblePublicProductionTargets().getFirst();

    assertThat(service.matchesCurrentTarget(discovered)).isFalse();
  }

  @Test
  void missingCurrentOpenOwnerProofFailsWithoutReadingLaunchAssociation() {
    var catalogRepository = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var admissionRepository = mock(CanonicalInitialAdmissionRepository.class);
    var launchRepository = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    when(catalogRepository.readVisiblePublicProductionSnapshots(NAMESPACE))
        .thenReturn(List.of(catalogSnapshot()));
    when(admissionRepository.readCurrentOpenSnapshotForRealm(NAMESPACE, TENANT, REALM))
        .thenThrow(new IllegalStateException("No current OPEN owner proof"));
    var service = routeReadService(catalogRepository, admissionRepository, launchRepository);

    assertThatThrownBy(service::readVisiblePublicProductionTargets)
        .isInstanceOf(CanonicalPlayerRouteReadService.InvalidAuthorityException.class);
    verify(launchRepository, never()).readForInitialAdmission(NAMESPACE, TENANT, REALM, INSTANCE);
  }

  @Test
  void abortedCurrentOwnerProofFailsWithoutReadingLaunchAssociation() {
    var catalogRepository = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var admissionRepository = mock(CanonicalInitialAdmissionRepository.class);
    var launchRepository = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    when(catalogRepository.readVisiblePublicProductionSnapshots(NAMESPACE))
        .thenReturn(List.of(catalogSnapshot()));
    when(admissionRepository.readCurrentOpenSnapshotForRealm(NAMESPACE, TENANT, REALM))
        .thenThrow(new IllegalStateException("Current OPEN pointer has no committed proof"));
    var service = routeReadService(catalogRepository, admissionRepository, launchRepository);

    assertThatThrownBy(service::readVisiblePublicProductionTargets)
        .isInstanceOf(CanonicalPlayerRouteReadService.InvalidAuthorityException.class);
    verify(launchRepository, never()).readForInitialAdmission(NAMESPACE, TENANT, REALM, INSTANCE);
  }

  @Test
  void proofNamespaceMustMatchConfiguredOwnerNamespace() {
    var catalogRepository = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var admissionRepository = mock(CanonicalInitialAdmissionRepository.class);
    var launchRepository = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    when(catalogRepository.readVisiblePublicProductionSnapshots(NAMESPACE))
        .thenReturn(List.of(catalogSnapshot()));
    when(admissionRepository.readCurrentOpenSnapshotForRealm(NAMESPACE, TENANT, REALM))
        .thenReturn(currentOpenSnapshot(committedProof(7L, "another-owner", 1L)));
    var service = routeReadService(catalogRepository, admissionRepository, launchRepository);

    assertThatThrownBy(service::readVisiblePublicProductionTargets)
        .isInstanceOf(CanonicalPlayerRouteReadService.InvalidAuthorityException.class);
    verify(launchRepository, never()).readForInitialAdmission(NAMESPACE, TENANT, REALM, INSTANCE);
  }

  @Test
  void catalogNamespaceMustMatchConfiguredOwnerNamespace() {
    var catalogRepository = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var admissionRepository = mock(CanonicalInitialAdmissionRepository.class);
    var launchRepository = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    when(catalogRepository.readVisiblePublicProductionSnapshots(NAMESPACE))
        .thenReturn(List.of(catalogSnapshot("another-owner", 1L)));
    when(admissionRepository.readCurrentOpenSnapshotForRealm(NAMESPACE, TENANT, REALM))
        .thenReturn(currentOpenSnapshot(committedProof(7L)));
    var service = routeReadService(catalogRepository, admissionRepository, launchRepository);

    assertThatThrownBy(service::readVisiblePublicProductionTargets)
        .isInstanceOf(CanonicalPlayerRouteReadService.InvalidAuthorityException.class);
    verify(launchRepository, never()).readForInitialAdmission(NAMESPACE, TENANT, REALM, INSTANCE);
  }

  @Test
  void catalogRevisionMustMatchCommittedOwnerProof() {
    var catalogRepository = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var admissionRepository = mock(CanonicalInitialAdmissionRepository.class);
    var launchRepository = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    when(catalogRepository.readVisiblePublicProductionSnapshots(NAMESPACE))
        .thenReturn(List.of(catalogSnapshot()));
    when(admissionRepository.readCurrentOpenSnapshotForRealm(NAMESPACE, TENANT, REALM))
        .thenReturn(currentOpenSnapshot(committedProof(7L, NAMESPACE, 2L)));
    var service = routeReadService(catalogRepository, admissionRepository, launchRepository);

    assertThatThrownBy(service::readVisiblePublicProductionTargets)
        .isInstanceOf(CanonicalPlayerRouteReadService.InvalidAuthorityException.class);
    verify(launchRepository, never()).readForInitialAdmission(NAMESPACE, TENANT, REALM, INSTANCE);
  }

  @Test
  void launchAssociationMustMatchCanonicalTenantAndRealmTarget() {
    var catalogRepository = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var admissionRepository = mock(CanonicalInitialAdmissionRepository.class);
    var launchRepository = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    when(catalogRepository.readVisiblePublicProductionSnapshots(NAMESPACE))
        .thenReturn(List.of(catalogSnapshot()));
    when(admissionRepository.readCurrentOpenSnapshotForRealm(NAMESPACE, TENANT, REALM))
        .thenReturn(currentOpenSnapshot(committedProof(7L)));
    CanonicalInitialAdmissionLaunchTarget launch =
        launchTarget(NAMESPACE, uuid("99999999-9999-4999-8999-999999999999"), VERSION);
    when(launchRepository.readForInitialAdmission(NAMESPACE, TENANT, REALM, INSTANCE))
        .thenReturn(Optional.of(launch));
    var service = routeReadService(catalogRepository, admissionRepository, launchRepository);

    assertThatThrownBy(service::readVisiblePublicProductionTargets)
        .isInstanceOf(CanonicalPlayerRouteReadService.InvalidAuthorityException.class);
  }

  @Test
  void launchAssociationNamespaceMustMatchConfiguredOwnerNamespace() {
    var catalogRepository = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var admissionRepository = mock(CanonicalInitialAdmissionRepository.class);
    var launchRepository = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    when(catalogRepository.readVisiblePublicProductionSnapshots(NAMESPACE))
        .thenReturn(List.of(catalogSnapshot()));
    when(admissionRepository.readCurrentOpenSnapshotForRealm(NAMESPACE, TENANT, REALM))
        .thenReturn(currentOpenSnapshot(committedProof(7L)));
    CanonicalInitialAdmissionLaunchTarget launch = launchTarget("another-owner", TENANT, VERSION);
    when(launchRepository.readForInitialAdmission(NAMESPACE, TENANT, REALM, INSTANCE))
        .thenReturn(Optional.of(launch));
    var service = routeReadService(catalogRepository, admissionRepository, launchRepository);

    assertThatThrownBy(service::readVisiblePublicProductionTargets)
        .isInstanceOf(CanonicalPlayerRouteReadService.InvalidAuthorityException.class);
  }

  @Test
  void launchVersionMustMatchCommittedOwnerProof() {
    var catalogRepository = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var admissionRepository = mock(CanonicalInitialAdmissionRepository.class);
    var launchRepository = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    when(catalogRepository.readVisiblePublicProductionSnapshots(NAMESPACE))
        .thenReturn(List.of(catalogSnapshot()));
    when(admissionRepository.readCurrentOpenSnapshotForRealm(NAMESPACE, TENANT, REALM))
        .thenReturn(currentOpenSnapshot(committedProof(7L)));
    CanonicalInitialAdmissionLaunchTarget launch =
        launchTarget(NAMESPACE, TENANT, uuid("99999999-9999-4999-8999-999999999999"));
    when(launchRepository.readForInitialAdmission(NAMESPACE, TENANT, REALM, INSTANCE))
        .thenReturn(Optional.of(launch));
    var service = routeReadService(catalogRepository, admissionRepository, launchRepository);

    assertThatThrownBy(service::readVisiblePublicProductionTargets)
        .isInstanceOf(CanonicalPlayerRouteReadService.InvalidAuthorityException.class);
  }

  @Test
  void unavailableOwnerSourceFailsClosed() {
    assertThatThrownBy(
            CanonicalPlayerRouteReadService.unavailable()::readVisiblePublicProductionTargets)
        .isInstanceOf(CanonicalPlayerRouteReadService.ReadUnavailableException.class);
  }

  private static CanonicalRealmCatalogSnapshot catalogSnapshot() {
    return catalogSnapshot(NAMESPACE, 1L);
  }

  private static CanonicalRealmCatalogSnapshot catalogSnapshot(
      String targetNamespace, long catalogRevision) {
    IntakeReceipt receipt = sourceReceipt(targetNamespace);
    return new CanonicalRealmCatalogSnapshot(
        targetNamespace,
        TENANT,
        "demo-tenant",
        "demo-world",
        REALM,
        "production",
        "Production Realm",
        true,
        true,
        "SHARED",
        PLAYABLE_NAMESPACE,
        "ALLOW_NEW",
        catalogRevision,
        uuid("66666666-6666-4666-8666-666666666666"),
        "a".repeat(64),
        "sha256:" + "b".repeat(64),
        receipt);
  }

  private static CanonicalInitialAdmissionOwnerProof committedProof(long pointerVersion) {
    return committedProof(pointerVersion, NAMESPACE, 1L);
  }

  private static CanonicalInitialAdmissionOwnerProof committedProof(
      long pointerVersion, String targetNamespace, long catalogRevision) {
    return new CanonicalInitialAdmissionOwnerProof(
        CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
        REQUEST_ID,
        "a".repeat(64),
        targetNamespace,
        TENANT,
        "demo-world",
        REALM,
        PLAYABLE_NAMESPACE,
        "SHARED",
        INSTANCE,
        VERSION,
        82L,
        catalogRevision,
        OriginKind.NO_PRIOR_POINTER,
        null,
        uuid("77777777-7777-4777-8777-777777777777"),
        uuid("88888888-8888-4888-8888-888888888888"),
        "sha256:" + "c".repeat(64),
        pointerVersion,
        27L,
        "sha256:" + "f".repeat(64),
        false,
        Instant.parse("2026-10-07T00:00:00Z"));
  }

  private static CanonicalInitialAdmissionRepository.CurrentOpenSnapshot currentOpenSnapshot(
      CanonicalInitialAdmissionOwnerProof proof) {
    return currentOpenSnapshot(proof, "d".repeat(64));
  }

  private static CanonicalInitialAdmissionRepository.CurrentOpenSnapshot currentOpenSnapshot(
      CanonicalInitialAdmissionOwnerProof proof, String digest) {
    return new CanonicalInitialAdmissionRepository.CurrentOpenSnapshot(proof, digest);
  }

  private static CanonicalPlayerRouteReadService routeReadService(
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      CanonicalInitialAdmissionRepository admissionRepository,
      CanonicalGameInstanceLaunchAssociationRepository launchRepository) {
    return new CanonicalPlayerRouteReadService(
        NAMESPACE, catalogRepository, admissionRepository, launchRepository);
  }

  private static CanonicalInitialAdmissionLaunchTarget launchTarget() {
    return launchTarget(NAMESPACE, TENANT, VERSION);
  }

  private static CanonicalInitialAdmissionLaunchTarget launchTarget(
      String targetNamespace, UUID canonicalTenantId, UUID canonicalVersionId) {
    CanonicalGameInstanceLaunchAssociation association =
        mock(CanonicalGameInstanceLaunchAssociation.class);
    when(association.currentGameInstanceStatus())
        .thenReturn(CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.RUNNING);
    when(association.targetNamespace()).thenReturn(targetNamespace);
    when(association.gameSessionTenantId()).thenReturn(101L);
    when(association.canonicalTenantId()).thenReturn(canonicalTenantId);
    when(association.worldSlug()).thenReturn("demo-world");
    when(association.playableStateNamespaceId()).thenReturn(PLAYABLE_NAMESPACE);
    when(association.playableStateScope()).thenReturn(RealmEntryPolicy.StateScope.SHARED);
    when(association.publicProduction()).thenReturn(true);
    when(association.gameInstanceUuid()).thenReturn(INSTANCE);

    CanonicalInitialAdmissionLaunchTarget launch =
        mock(CanonicalInitialAdmissionLaunchTarget.class);
    when(launch.association()).thenReturn(association);
    when(launch.realmId()).thenReturn(REALM);
    when(launch.gameInstanceId()).thenReturn(202L);
    when(launch.runtimeVersionId()).thenReturn(303L);
    when(launch.canonicalVersionId()).thenReturn(canonicalVersionId);
    return launch;
  }

  private static IntakeReceipt sourceReceipt(String targetNamespace) {
    UUID intakeOperationId = uuid("99999999-9999-4999-8999-999999999999");
    UUID intakeRequestId = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    UUID sourceOperationId = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    String tenantSlug = "demo-tenant";
    String worldSlug = "demo-world";
    String worldDisplayName = "Demo World";
    String sourceTenantKey = "game-design-tenant-42";
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            targetNamespace, sourceOperationId, TENANT, tenantSlug, worldSlug, worldDisplayName);
    String sourceEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            targetNamespace,
            sourceOperationId,
            intakeOperationId,
            sourceRequestDigest,
            TENANT,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            42L,
            sourceTenantKey,
            "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            targetNamespace,
            sourceOperationId,
            intakeOperationId,
            sourceRequestDigest,
            TENANT,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            42L,
            sourceTenantKey,
            "NEW_GAME_ROW",
            sourceEvidenceDigest);
    String intakeRequestDigest =
        GameSessionAuthoredWorldIntakeDigest.requestDigest(intakeRequestId, source);
    String receiptDigest =
        GameSessionAuthoredWorldIntakeDigest.receiptDigest(
            intakeOperationId, intakeRequestDigest, source.evidenceDigest());
    return new IntakeReceipt(
        intakeOperationId, intakeRequestId, intakeRequestDigest, source, receiptDigest);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
