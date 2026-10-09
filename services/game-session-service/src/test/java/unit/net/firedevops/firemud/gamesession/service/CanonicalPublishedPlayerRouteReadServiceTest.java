package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.PreseededActorAssignmentOwnerReadEvidence;
import net.firedevops.firemud.gamesession.client.GameDesignPublishedRealmPolicyClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService;
import org.junit.jupiter.api.Test;

class CanonicalPublishedPlayerRouteReadServiceTest {
  private static final String NAMESPACE = "gameplay";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID SOURCE_OPERATION = uuid("66666666-6666-4666-8666-666666666666");
  private static final String SOURCE_DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void tenantSelectorOwnerReadRejectsChangedCanonicalSelectorOrCounterBeforeGameDesign() {
    var f = fixture();
    when(f.routeReader().readCurrentTarget(TENANT)).thenReturn(f.route());
    var request = exactRequest(999L, 0L, 0L);

    assertThatThrownBy(() -> f.service().readCurrent(request))
        .isInstanceOf(CanonicalPublishedPlayerRouteReadService.StaleAuthorityException.class);
    verify(f.policyClient(), never()).listPublishedRealmEntryPolicies(TENANT, VERSION);
  }

  @Test
  void tenantSelectorOwnerReadRejectsChangedTargetNamespaceBeforeOwnerRead() {
    var f = fixture();
    var request = exactRequest(0L, 0L, 0L);
    var changed =
        new CanonicalGameplayRosterOwnerReadEvidence.Request(
            request.requestUuid(),
            request.canonicalAccountUuid(),
            "another-owner",
            request.canonicalTenantUuid(),
            request.worldSlug(),
            request.realmUuid(),
            request.realmSlug(),
            request.playableStateNamespaceUuid(),
            request.playableStateScope(),
            request.canonicalGameInstanceUuid(),
            request.canonicalVersionUuid(),
            request.expectedCatalogRevision(),
            request.expectedPointerVersion(),
            request.expectedActiveWorldEpoch());

    assertThatThrownBy(() -> f.service().readCurrent(changed))
        .isInstanceOf(CanonicalPublishedPlayerRouteReadService.InvalidAuthorityException.class);
    verify(f.routeReader(), never()).readCurrentTarget(any(UUID.class));
    verify(f.policyClient(), never()).listPublishedRealmEntryPolicies(TENANT, VERSION);
  }

  @Test
  void assignmentSelectorReadDerivesRouteByTenantAndRejectsChangedSelectorBeforeGameDesign() {
    var f = fixture();
    when(f.routeReader().readCurrentTarget(TENANT)).thenReturn(f.route());
    var request = assignmentRequest(uuid("99999999-9999-4999-8999-999999999999"));

    assertThatThrownBy(() -> f.service().readCurrentForAssignment(request))
        .isInstanceOf(CanonicalPublishedPlayerRouteReadService.StaleAuthorityException.class);

    verify(f.routeReader()).readCurrentTarget(TENANT);
    verify(f.policyClient(), never()).listPublishedRealmEntryPolicies(TENANT, VERSION);
  }

  @Test
  void staleRouteReturnsNoPolicyAndDoesNotContactGameDesign() {
    var f = fixture();
    when(f.routeReader().readCurrentTarget(f.route()))
        .thenReturn(mock(CanonicalPlayableTarget.class));
    var service = f.service();

    assertThatThrownBy(() -> service.readCurrent(f.route()))
        .isInstanceOf(CanonicalPublishedPlayerRouteReadService.StaleAuthorityException.class);
    verify(f.policyClient(), never()).listPublishedRealmEntryPolicies(TENANT, VERSION);
  }

  @Test
  void storageAndGameDesignTransportFailuresRemainDistinctAndReturnNoRoute() {
    var storage = fixture();
    when(storage.routeReader().readCurrentTarget(storage.route()))
        .thenThrow(
            new CanonicalPlayerRouteReadService.ReadUnavailableException("database offline"));
    assertThatThrownBy(() -> storage.service().readCurrent(storage.route()))
        .isInstanceOf(CanonicalPublishedPlayerRouteReadService.StorageUnavailableException.class);

    var transport = fixture();
    when(transport.policyClient().listPublishedRealmEntryPolicies(TENANT, VERSION))
        .thenThrow(
            io.grpc.Status.UNAVAILABLE.withDescription("owner offline").asRuntimeException());
    assertThatThrownBy(() -> transport.service().readCurrent(transport.route()))
        .isInstanceOf(
            CanonicalPublishedPlayerRouteReadService.GameDesignUnavailableException.class);
  }

  @Test
  void missingCompleteGameDesignPolicySetFailsClosed() {
    var f = fixture();

    assertThatThrownBy(() -> f.service().readCurrent(f.route()))
        .isInstanceOf(CanonicalPublishedPlayerRouteReadService.InvalidAuthorityException.class);
    verify(f.policyClient()).listPublishedRealmEntryPolicies(TENANT, VERSION);
  }

  @Test
  void changedCurrentSourceBindingFailsBeforePolicyRead() {
    var f = fixture();
    when(f.source().operationId()).thenReturn(uuid("77777777-7777-4777-8777-777777777777"));

    assertThatThrownBy(() -> f.service().readCurrent(f.route()))
        .isInstanceOf(CanonicalPublishedPlayerRouteReadService.InvalidAuthorityException.class);
    verify(f.policyClient(), never()).listPublishedRealmEntryPolicies(TENANT, VERSION);
  }

  @Test
  void changedCanonicalVersionFailsBeforePolicyRead() {
    var f = fixture();
    when(f.release().canonicalVersionId()).thenReturn(uuid("99999999-9999-4999-8999-999999999999"));

    assertThatThrownBy(() -> f.service().readCurrent(f.route()))
        .isInstanceOf(CanonicalPublishedPlayerRouteReadService.InvalidAuthorityException.class);
    verify(f.policyClient(), never()).listPublishedRealmEntryPolicies(TENANT, VERSION);
  }

  private static Fixture fixture() {
    var routeReader = mock(CanonicalPlayerRouteReadService.class);
    var catalogRepository = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var launchRepository = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    var policyClient = mock(GameDesignPublishedRealmPolicyClient.class);
    var route = mock(CanonicalPlayableTarget.class);
    var catalog = mock(CanonicalRealmCatalogSnapshot.class);
    var receipt = mock(IntakeReceipt.class);
    var source = mock(AuthoredWorldSourceEvidence.class);
    var launch = mock(CanonicalInitialAdmissionLaunchTarget.class);
    var association = mock(CanonicalGameInstanceLaunchAssociation.class);
    var completeBinding = mock(CompleteLaunchBindingEvidence.class);
    var descriptor = mock(AuthoredWorldLaunchDescriptorEvidence.class);
    var release = mock(AuthoredWorldReleaseAttestationEvidence.class);

    when(route.targetNamespace()).thenReturn(NAMESPACE);
    when(route.canonicalTenantId()).thenReturn(TENANT);
    when(route.worldSlug()).thenReturn("earth");
    when(route.realmId()).thenReturn(REALM);
    when(route.realmSlug()).thenReturn("main");
    when(route.playableStateNamespaceId()).thenReturn(PLAYABLE_NAMESPACE);
    when(route.playableStateScope()).thenReturn("SHARED");
    when(route.canonicalGameInstanceId()).thenReturn(INSTANCE);
    when(route.gameInstanceId()).thenReturn(202L);
    when(route.runtimeVersionId()).thenReturn(303L);
    when(route.canonicalVersionId()).thenReturn(VERSION);

    when(catalog.targetNamespace()).thenReturn(NAMESPACE);
    when(catalog.tenantId()).thenReturn(TENANT);
    when(catalog.worldSlug()).thenReturn("earth");
    when(catalog.realmId()).thenReturn(REALM);
    when(catalog.realmSlug()).thenReturn("main");
    when(catalog.playableStateNamespaceId()).thenReturn(PLAYABLE_NAMESPACE);
    when(catalog.visible()).thenReturn(true);
    when(catalog.publicProduction()).thenReturn(true);
    when(catalog.stateScope()).thenReturn("SHARED");
    when(catalog.sourceIntakeReceipt()).thenReturn(receipt);
    when(receipt.source()).thenReturn(source);
    when(source.targetNamespace()).thenReturn(NAMESPACE);
    when(source.canonicalTenantId()).thenReturn(TENANT);
    when(source.sourceGameRowId()).thenReturn(42L);
    when(source.sourceGameTenantKey()).thenReturn("game-design-tenant-42");
    when(source.provenanceKind()).thenReturn("NEW_GAME_ROW");
    when(source.operationId()).thenReturn(SOURCE_OPERATION);
    when(source.evidenceDigest()).thenReturn(SOURCE_DIGEST);

    when(launch.association()).thenReturn(association);
    when(launch.realmId()).thenReturn(REALM);
    when(launch.gameInstanceId()).thenReturn(202L);
    when(launch.runtimeVersionId()).thenReturn(303L);
    when(launch.canonicalVersionId()).thenReturn(VERSION);
    when(association.targetNamespace()).thenReturn(NAMESPACE);
    when(association.canonicalTenantId()).thenReturn(TENANT);
    when(association.gameInstanceUuid()).thenReturn(INSTANCE);
    when(association.worldSlug()).thenReturn("earth");
    when(association.playableStateNamespaceId()).thenReturn(PLAYABLE_NAMESPACE);
    when(association.playableStateScope()).thenReturn(RealmEntryPolicy.StateScope.SHARED);
    when(association.publicProduction()).thenReturn(true);
    when(association.currentGameInstanceStatus())
        .thenReturn(CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.RUNNING);
    when(association.controlPlaneRequestId()).thenReturn("launch-request");
    when(association.launchDescriptorId()).thenReturn("launch-descriptor");
    when(association.launchBindingEvidence()).thenReturn(completeBinding);
    when(completeBinding.descriptor()).thenReturn(descriptor);
    when(completeBinding.releaseAttestation()).thenReturn(release);
    when(descriptor.targetNamespace()).thenReturn(NAMESPACE);
    when(descriptor.canonicalTenantId()).thenReturn(TENANT);
    when(descriptor.worldSlug()).thenReturn("earth");
    when(descriptor.authoredWorldSourceOperationId()).thenReturn(SOURCE_OPERATION);
    when(descriptor.authoredWorldSourceEvidenceDigest()).thenReturn(SOURCE_DIGEST);
    when(descriptor.versionId()).thenReturn(303L);
    when(descriptor.versionStateEpoch()).thenReturn(7L);
    when(descriptor.publishedReleaseBundleRef()).thenReturn("bundle:exact");
    when(descriptor.controlPlaneRequestId()).thenReturn("launch-request");
    when(descriptor.launchDescriptorId()).thenReturn("launch-descriptor");
    when(release.targetNamespace()).thenReturn(NAMESPACE);
    when(release.canonicalTenantId()).thenReturn(TENANT);
    when(release.canonicalVersionId()).thenReturn(VERSION);
    when(release.worldSlug()).thenReturn("earth");
    when(release.authoredWorldSourceOperationId()).thenReturn(SOURCE_OPERATION);
    when(release.authoredWorldSourceEvidenceDigest()).thenReturn(SOURCE_DIGEST);
    when(release.versionStateEpoch()).thenReturn(7L);
    when(release.publishedReleaseBundleRef()).thenReturn("bundle:exact");

    when(routeReader.readCurrentTarget(route)).thenReturn(route);
    when(catalogRepository.readUniqueVisiblePublicProduction(NAMESPACE, TENANT))
        .thenReturn(Optional.of(catalog));
    when(launchRepository.readForInitialAdmission(NAMESPACE, TENANT, REALM, INSTANCE))
        .thenReturn(Optional.of(launch));
    var service =
        new CanonicalPublishedPlayerRouteReadService(
            NAMESPACE, routeReader, catalogRepository, launchRepository, policyClient);
    return new Fixture(
        routeReader,
        catalogRepository,
        launchRepository,
        policyClient,
        route,
        source,
        launch,
        release,
        service);
  }

  private static CanonicalGameplayRosterOwnerReadEvidence.Request exactRequest(
      long pointerDelta, long catalogDelta, long epochDelta) {
    return new CanonicalGameplayRosterOwnerReadEvidence.Request(
        uuid("77777777-7777-4777-8777-777777777777"),
        uuid("88888888-8888-4888-8888-888888888888"),
        NAMESPACE,
        TENANT,
        "earth",
        REALM,
        "main",
        PLAYABLE_NAMESPACE,
        "SHARED",
        INSTANCE,
        VERSION,
        1L + catalogDelta,
        1L + pointerDelta,
        1L + epochDelta);
  }

  private static PreseededActorAssignmentOwnerReadEvidence.Request assignmentRequest(
      UUID realmUuid) {
    return new PreseededActorAssignmentOwnerReadEvidence.Request(
        uuid("77777777-7777-4777-8777-777777777777"),
        uuid("88888888-8888-4888-8888-888888888888"),
        NAMESPACE,
        TENANT,
        "earth",
        realmUuid,
        "main",
        PLAYABLE_NAMESPACE,
        "SHARED",
        INSTANCE,
        VERSION,
        1L,
        "b".repeat(64),
        "bundle:exact");
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      CanonicalPlayerRouteReadService routeReader,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      CanonicalGameInstanceLaunchAssociationRepository launchRepository,
      GameDesignPublishedRealmPolicyClient policyClient,
      CanonicalPlayableTarget route,
      AuthoredWorldSourceEvidence source,
      CanonicalInitialAdmissionLaunchTarget launch,
      AuthoredWorldReleaseAttestationEvidence release,
      CanonicalPublishedPlayerRouteReadService service) {}
}
