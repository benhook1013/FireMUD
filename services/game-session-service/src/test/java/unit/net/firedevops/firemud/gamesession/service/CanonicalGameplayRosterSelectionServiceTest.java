package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient;
import net.firedevops.firemud.gamesession.client.GameDesignPublishedRealmPolicyClient;
import net.firedevops.firemud.gamesession.command.text.CanonicalGameplayActorSelection;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalPublishedPlayerRoute;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterSelectionService;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import support.net.firedevops.firemud.gamesession.PublishedRealmPolicyEvidenceFixture;

class CanonicalGameplayRosterSelectionServiceTest {
  private static final UUID ACCOUNT = uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID FIRST_ACTOR = uuid("77777777-7777-4777-8777-777777777777");
  private static final UUID SECOND_ACTOR = uuid("99999999-9999-4999-8999-999999999999");
  private static final UUID SNAPSHOT = uuid("88888888-8888-4888-8888-888888888888");
  private static final UUID ASSIGNMENT = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

  @Test
  void returnsMenuForMultipleActorsThenReadsOnlyTheSelectedOrdinalOwnerReference()
      throws Exception {
    var harness = harness(twoActors());

    var menu =
        harness
            .service()
            .select(context(harness.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null);

    assertThat(menu)
        .isEqualTo(
            new CanonicalGameplayRosterSelectionService.SelectionRequired(
                List.of(
                    new CanonicalGameplayActorSelection.Choice(1, "Pilot One"),
                    new CanonicalGameplayActorSelection.Choice(2, "Pilot Two"))));
    verify(harness.stub(), never()).readSelectedPreseededAssignment(any());

    PlayerExecutionContext input = context(harness.route(), "ffffffff-ffff-4fff-8fff-ffffffffffff");
    var selected = harness.service().select(input, "2");

    assertThat(selected)
        .isInstanceOfSatisfying(
            CanonicalGameplayRosterSelectionService.SelectedOwnerReference.class,
            result -> {
              assertThat(result.displayName()).isEqualTo("Pilot Two");
              assertThat(result.ordinal()).isEqualTo(2);
              assertThat(result.ownerReference().canonicalAccountUuid()).isEqualTo(ACCOUNT);
              assertThat(result.ownerReference().selectedCharacterUuid()).isEqualTo(SECOND_ACTOR);
              assertThat(result.ownerReference().assignmentOperationId()).isEqualTo(ASSIGNMENT);
              assertThat(result.ownerReference().rosterSnapshotUuid()).isEqualTo(SNAPSHOT);
            });

    var requestCaptor =
        org.mockito.ArgumentCaptor.forClass(CanonicalGameplayRosterSelectedAssignmentRequest.class);
    verify(harness.stub()).readSelectedPreseededAssignment(requestCaptor.capture());
    PlayerExecutionContext selectedContext = requestCaptor.getValue().getPlayerExecutionContext();
    assertThat(selectedContext.getRequestId()).isNotEqualTo(input.getRequestId());
    assertThat(selectedContext.getAccountId()).isEqualTo(input.getAccountId());
    assertThat(selectedContext.getSessionId()).isEqualTo(input.getSessionId());
    assertThat(selectedContext.getTenantId()).isEqualTo(input.getTenantId());
    assertThat(selectedContext.getRealmId()).isEqualTo(input.getRealmId());
    assertThat(selectedContext.getPlayableStateNamespaceId())
        .isEqualTo(input.getPlayableStateNamespaceId());
    assertThat(selectedContext.getPlayableStateScope()).isEqualTo(input.getPlayableStateScope());
    assertThat(selectedContext.getGameInstanceId()).isEqualTo(input.getGameInstanceId());
    assertThat(selectedContext.getCharacterId()).isEqualTo(SECOND_ACTOR.toString());
    assertThat(requestCaptor.getValue().getSelectedCharacterUuid())
        .isEqualTo(SECOND_ACTOR.toString());

    verify(harness.catalogRepository(), atLeastOnce())
        .readUniqueVisiblePublicProduction("test", harness.route().canonicalTenantId());
    verify(harness.admissionRepository(), atLeastOnce())
        .readCurrentOpenSnapshotForRealm(
            "test", harness.route().canonicalTenantId(), harness.route().realmId());
    verify(harness.launchRepository(), atLeastOnce())
        .readForInitialAdmission(
            "test",
            harness.route().canonicalTenantId(),
            harness.route().realmId(),
            harness.route().canonicalGameInstanceId());
    verify(harness.policyClient(), atLeastOnce())
        .listPublishedRealmEntryPolicies(
            harness.route().canonicalTenantId(), harness.route().canonicalVersionId());
    verify(harness.stub(), atLeastOnce()).listPreseededRoster(any());
    verify(harness.stub(), atLeastOnce()).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verifyNoMoreInteractions(
        harness.catalogRepository(),
        harness.admissionRepository(),
        harness.launchRepository(),
        harness.policyClient(),
        harness.stub());
  }

  @Test
  void deniesTenantNamespaceRealmAndInstanceContextMismatchesBeforeEntityRead() throws Exception {
    var harness = harness(oneActor());
    PlayerExecutionContext valid = context(harness.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");

    assertDenied(
        harness
            .service()
            .select(
                valid.toBuilder()
                    .setPlayableStateNamespaceId(
                        uuid("12121212-1212-4212-8212-121212121212").toString())
                    .build(),
                null),
        CanonicalGameplayRosterSelectionService.Denial.ROUTE_MISMATCH);
    assertDenied(
        harness
            .service()
            .select(
                valid.toBuilder()
                    .setGameInstanceId(uuid("12121212-1212-4212-8212-121212121212").toString())
                    .build(),
                null),
        CanonicalGameplayRosterSelectionService.Denial.ROUTE_MISMATCH);
    assertDenied(
        harness
            .service()
            .select(
                valid.toBuilder()
                    .setRealmId(uuid("12121212-1212-4212-8212-121212121212").toString())
                    .build(),
                null),
        CanonicalGameplayRosterSelectionService.Denial.ROUTE_MISMATCH);

    verify(harness.stub(), never()).listPreseededRoster(any());
    verify(harness.stub(), never()).readSelectedPreseededAssignment(any());
  }

  @Test
  void rejectsAnEntitySnapshotWithAnotherAccountOrTargetBeforeSelectedRead() throws Exception {
    var accountMismatch = harness(oneActor());
    doAnswer(
            invocation ->
                rosterResponse(
                    invocation.getArgument(0),
                    uuid("12121212-1212-4212-8212-121212121212"),
                    oneActor()))
        .when(accountMismatch.stub())
        .listPreseededRoster(any());

    assertDenied(
        accountMismatch
            .service()
            .select(context(accountMismatch.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null),
        CanonicalGameplayRosterSelectionService.Denial.INVALID_ROSTER_PROOF);
    verify(accountMismatch.stub(), never()).readSelectedPreseededAssignment(any());

    var targetMismatch = harness(oneActor());
    doAnswer(
            invocation -> {
              CanonicalGameplayRosterRequest request = invocation.getArgument(0);
              CanonicalGameplayRosterTarget changedTarget =
                  request.getExpectedTarget().toBuilder()
                      .setGameInstanceUuid(uuid("12121212-1212-4212-8212-121212121212").toString())
                      .build();
              return rosterResponse(request, ACCOUNT, changedTarget, oneActor());
            })
        .when(targetMismatch.stub())
        .listPreseededRoster(any());

    assertDenied(
        targetMismatch
            .service()
            .select(context(targetMismatch.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null),
        CanonicalGameplayRosterSelectionService.Denial.INVALID_ROSTER_PROOF);
    verify(targetMismatch.stub(), never()).readSelectedPreseededAssignment(any());
  }

  @Test
  void returnsExplicitSelectionAndSelectorDenialsWithoutAssignmentReads() throws Exception {
    var multiple = harness(twoActors());
    assertThat(
            multiple
                .service()
                .select(context(multiple.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null))
        .isInstanceOf(CanonicalGameplayRosterSelectionService.SelectionRequired.class);
    assertDenied(
        multiple
            .service()
            .select(context(multiple.route(), "ffffffff-ffff-4fff-8fff-ffffffffffff"), "Pilot Two"),
        CanonicalGameplayRosterSelectionService.Denial.INVALID_SELECTOR);
    verify(multiple.stub(), never()).readSelectedPreseededAssignment(any());

    var empty = harness(List.of());
    assertDenied(
        empty
            .service()
            .select(context(empty.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null),
        CanonicalGameplayRosterSelectionService.Denial.NO_PRESEEDED_ACTOR);
    verify(empty.stub(), never()).readSelectedPreseededAssignment(any());
  }

  @Test
  void rejectsMovedRouteAfterRosterAndAfterAssignmentNetworkEvidence() throws Exception {
    var changedAfterList = harness(oneActor());
    configureRouteMovement(changedAfterList, 3);
    assertDenied(
        changedAfterList
            .service()
            .select(
                context(changedAfterList.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null),
        CanonicalGameplayRosterSelectionService.Denial.ROUTE_CHANGED);
    verify(changedAfterList.stub(), never()).readSelectedPreseededAssignment(any());

    var changedAfterAssignment = harness(oneActor());
    configureRouteMovement(changedAfterAssignment, 5);
    assertDenied(
        changedAfterAssignment
            .service()
            .select(
                context(changedAfterAssignment.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                null),
        CanonicalGameplayRosterSelectionService.Denial.ROUTE_CHANGED);
    verify(changedAfterAssignment.stub()).readSelectedPreseededAssignment(any());
  }

  @Test
  void rejectsChangedAssignmentSnapshotAndUnavailableEntityBackend() throws Exception {
    var changedSnapshot = harness(oneActor());
    doAnswer(
            invocation -> {
              CanonicalGameplayRosterSelectedAssignmentRequest request = invocation.getArgument(0);
              return assignmentResponse(request, true);
            })
        .when(changedSnapshot.stub())
        .readSelectedPreseededAssignment(any());
    assertDenied(
        changedSnapshot
            .service()
            .select(context(changedSnapshot.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null),
        CanonicalGameplayRosterSelectionService.Denial.INVALID_ASSIGNMENT_PROOF);

    var unavailable = harness(oneActor());
    doThrow(Status.UNAVAILABLE.withDescription("Entity offline").asRuntimeException())
        .when(unavailable.stub())
        .listPreseededRoster(any());
    assertDenied(
        unavailable
            .service()
            .select(context(unavailable.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null),
        CanonicalGameplayRosterSelectionService.Denial.ROSTER_UNAVAILABLE);
    verify(unavailable.stub(), never()).readSelectedPreseededAssignment(any());
  }

  @Test
  void deniesAmbientTransactionBeforeAnyRouteOrOwnerRead() throws Exception {
    var harness = harness(oneActor());
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertDenied(
          harness
              .service()
              .select(context(harness.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null),
          CanonicalGameplayRosterSelectionService.Denial.AMBIENT_TRANSACTION);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    verifyNoInteractions(
        harness.catalogRepository(),
        harness.admissionRepository(),
        harness.launchRepository(),
        harness.policyClient(),
        harness.stub());
  }

  private static TestHarness harness(List<CanonicalGameplayRosterClient.RosterActor> actors)
      throws Exception {
    CanonicalPublishedPlayerRoute published =
        PublishedRealmPolicyEvidenceFixture.canonicalPublishedRoute();
    CanonicalPlayableTarget route = published.route();
    PublishedRealmEntryPolicySetEvidence policySet = published.policySetEvidence();
    TargetProof policyTarget = policySet.target();

    UUID sourceRegistrationRequest = uuid("abababab-abab-4bab-8bab-abababababab");
    UUID sourceOperation = uuid("acacacac-acac-4cac-8cac-acacacacacac");
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            route.targetNamespace(),
            sourceRegistrationRequest,
            route.canonicalTenantId(),
            "tenant-key",
            route.worldSlug(),
            route.worldDisplayName());
    String sourceEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            route.targetNamespace(),
            sourceRegistrationRequest,
            sourceOperation,
            sourceRequestDigest,
            route.canonicalTenantId(),
            "tenant-key",
            route.worldSlug(),
            route.worldDisplayName(),
            policyTarget.sourceGameRowId(),
            policyTarget.sourceGameTenantKey(),
            policyTarget.sourceProvenanceKind());
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            route.targetNamespace(),
            sourceRegistrationRequest,
            sourceOperation,
            sourceRequestDigest,
            route.canonicalTenantId(),
            "tenant-key",
            route.worldSlug(),
            route.worldDisplayName(),
            policyTarget.sourceGameRowId(),
            policyTarget.sourceGameTenantKey(),
            policyTarget.sourceProvenanceKind(),
            sourceEvidenceDigest);
    IntakeReceipt receipt = mock(IntakeReceipt.class);
    when(receipt.source()).thenReturn(source);
    CanonicalRealmCatalogSnapshot catalog =
        new CanonicalRealmCatalogSnapshot(
            route.targetNamespace(),
            route.canonicalTenantId(),
            "tenant-key",
            route.worldSlug(),
            route.realmId(),
            route.realmSlug(),
            route.realmDisplayName(),
            true,
            true,
            route.playableStateScope(),
            route.playableStateNamespaceId(),
            route.characterCreationPolicy(),
            route.catalogRevision(),
            uuid("adadadad-adad-4dad-8dad-adadadadadad"),
            "a".repeat(64),
            "sha256:" + "b".repeat(64),
            receipt);

    var catalogRepository = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var admissionRepository = mock(CanonicalInitialAdmissionRepository.class);
    var launchRepository = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    when(catalogRepository.readUniqueVisiblePublicProduction(
            route.targetNamespace(), route.canonicalTenantId()))
        .thenReturn(Optional.of(catalog));
    when(admissionRepository.readCurrentOpenSnapshotForRealm(
            route.targetNamespace(), route.canonicalTenantId(), route.realmId()))
        .thenReturn(currentOpenSnapshot(route));

    var descriptor = mock(AuthoredWorldLaunchDescriptorEvidence.class);
    var release = mock(AuthoredWorldReleaseAttestationEvidence.class);
    var binding = mock(CompleteLaunchBindingEvidence.class);
    when(binding.descriptor()).thenReturn(descriptor);
    when(binding.releaseAttestation()).thenReturn(release);
    when(descriptor.targetNamespace()).thenReturn(route.targetNamespace());
    when(descriptor.canonicalTenantId()).thenReturn(route.canonicalTenantId());
    when(descriptor.worldSlug()).thenReturn(route.worldSlug());
    when(descriptor.authoredWorldSourceOperationId()).thenReturn(sourceOperation);
    when(descriptor.authoredWorldSourceEvidenceDigest()).thenReturn(sourceEvidenceDigest);
    when(descriptor.versionId()).thenReturn(route.runtimeVersionId());
    when(descriptor.versionStateEpoch()).thenReturn(policySet.publicationVersionStateEpoch());
    when(descriptor.publishedReleaseBundleRef()).thenReturn(policySet.publishedReleaseBundleRef());
    when(descriptor.controlPlaneRequestId()).thenReturn("launch-request");
    when(descriptor.launchDescriptorId()).thenReturn("launch-descriptor");
    when(release.targetNamespace()).thenReturn(route.targetNamespace());
    when(release.canonicalTenantId()).thenReturn(route.canonicalTenantId());
    when(release.canonicalVersionId()).thenReturn(route.canonicalVersionId());
    when(release.worldSlug()).thenReturn(route.worldSlug());
    when(release.authoredWorldSourceOperationId()).thenReturn(sourceOperation);
    when(release.authoredWorldSourceEvidenceDigest()).thenReturn(sourceEvidenceDigest);
    when(release.publishedReleaseBundleRef()).thenReturn(policySet.publishedReleaseBundleRef());
    when(release.versionStateEpoch()).thenReturn(policySet.publicationVersionStateEpoch());
    when(release.commitId()).thenReturn(policySet.sourceCommitId().toString());
    when(release.publishWorkflowId()).thenReturn(policySet.publishWorkflowId());
    when(release.manifestHash()).thenReturn(policySet.manifestHash());

    CanonicalGameInstanceLaunchAssociation association =
        mock(CanonicalGameInstanceLaunchAssociation.class);
    when(association.targetNamespace()).thenReturn(route.targetNamespace());
    when(association.gameSessionTenantId()).thenReturn(route.gameSessionTenantId());
    when(association.tenantAssociationOperationId())
        .thenReturn(uuid("aeaeaeae-aeae-4eae-8eae-aeaeaeaeaeae"));
    when(association.canonicalTenantId()).thenReturn(route.canonicalTenantId());
    when(association.gameInstanceUuid()).thenReturn(route.canonicalGameInstanceId());
    when(association.worldSlug()).thenReturn(route.worldSlug());
    when(association.playableStateNamespaceId()).thenReturn(route.playableStateNamespaceId());
    when(association.playableStateScope()).thenReturn(RealmEntryPolicy.StateScope.SHARED);
    when(association.publicProduction()).thenReturn(true);
    when(association.controlPlaneRequestId()).thenReturn("launch-request");
    when(association.launchDescriptorId()).thenReturn("launch-descriptor");
    when(association.capturedStartingRowVersion()).thenReturn(1L);
    when(association.currentRowVersion()).thenReturn(2L);
    when(association.launchBindingEvidence()).thenReturn(binding);
    when(association.currentGameInstanceStatus())
        .thenReturn(CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.RUNNING);
    CanonicalInitialAdmissionLaunchTarget launch =
        mock(CanonicalInitialAdmissionLaunchTarget.class);
    when(launch.association()).thenReturn(association);
    when(launch.realmId()).thenReturn(route.realmId());
    when(launch.gameInstanceId()).thenReturn(route.gameInstanceId());
    when(launch.runtimeVersionId()).thenReturn(route.runtimeVersionId());
    when(launch.canonicalVersionId()).thenReturn(route.canonicalVersionId());
    when(launchRepository.readForInitialAdmission(
            route.targetNamespace(),
            route.canonicalTenantId(),
            route.realmId(),
            route.canonicalGameInstanceId()))
        .thenReturn(Optional.of(launch));

    var routeReader =
        new CanonicalPlayerRouteReadService(
            route.targetNamespace(), catalogRepository, admissionRepository, launchRepository);
    var policyClient = mock(GameDesignPublishedRealmPolicyClient.class);
    when(policyClient.listPublishedRealmEntryPolicies(
            route.canonicalTenantId(), route.canonicalVersionId()))
        .thenReturn(policySet);
    var publishedRouteReader =
        new CanonicalPublishedPlayerRouteReadService(
            route.targetNamespace(),
            routeReader,
            catalogRepository,
            launchRepository,
            policyClient);

    var stub =
        mock(CanonicalGameplayRosterServiceGrpc.CanonicalGameplayRosterServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.listPreseededRoster(any()))
        .thenAnswer(invocation -> rosterResponse(invocation.getArgument(0), ACCOUNT, actors));
    when(stub.readSelectedPreseededAssignment(any()))
        .thenAnswer(invocation -> assignmentResponse(invocation.getArgument(0), false));
    CanonicalGameplayRosterClient rosterClient = rosterClient(stub);
    var service =
        new CanonicalGameplayRosterSelectionService(
            routeReader, publishedRouteReader, rosterClient);
    return new TestHarness(
        service,
        route,
        catalogRepository,
        admissionRepository,
        launchRepository,
        policyClient,
        stub);
  }

  private static void configureRouteMovement(TestHarness harness, int initialReadCount) {
    CanonicalInitialAdmissionOwnerProof initialProof = proof(harness.route(), 7L);
    CanonicalInitialAdmissionOwnerProof changedProof = proof(harness.route(), 8L);
    CanonicalInitialAdmissionRepository.CurrentOpenSnapshot initial =
        new CanonicalInitialAdmissionRepository.CurrentOpenSnapshot(
            initialProof, harness.route().admissionPointerSnapshotDigest());
    CanonicalInitialAdmissionRepository.CurrentOpenSnapshot changed =
        new CanonicalInitialAdmissionRepository.CurrentOpenSnapshot(changedProof, "e".repeat(64));
    AtomicInteger reads = new AtomicInteger();
    when(harness
            .admissionRepository()
            .readCurrentOpenSnapshotForRealm(
                harness.route().targetNamespace(),
                harness.route().canonicalTenantId(),
                harness.route().realmId()))
        .thenAnswer(invocation -> reads.incrementAndGet() <= initialReadCount ? initial : changed);
  }

  private static CanonicalInitialAdmissionRepository.CurrentOpenSnapshot currentOpenSnapshot(
      CanonicalPlayableTarget route) {
    return new CanonicalInitialAdmissionRepository.CurrentOpenSnapshot(
        proof(route, route.pointerVersion()), route.admissionPointerSnapshotDigest());
  }

  private static CanonicalInitialAdmissionOwnerProof proof(
      CanonicalPlayableTarget route, long pointerVersion) {
    return new CanonicalInitialAdmissionOwnerProof(
        CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
        route.initialAdmissionRequestId(),
        route.initialAdmissionRequestDigest(),
        route.targetNamespace(),
        route.canonicalTenantId(),
        route.worldSlug(),
        route.realmId(),
        route.playableStateNamespaceId(),
        route.playableStateScope(),
        route.canonicalGameInstanceId(),
        route.canonicalVersionId(),
        route.activeWorldEpoch(),
        route.catalogRevision(),
        route.initialAdmissionOriginKind(),
        route.expectedPriorPointerVersion(),
        route.holdId(),
        route.holdFence(),
        route.holdBindingDigest(),
        pointerVersion,
        route.auditEventId(),
        route.ownerProofDigest(),
        false,
        route.ownerProofTerminalAt());
  }

  private static CanonicalGameplayRosterResponse rosterResponse(
      CanonicalGameplayRosterRequest request,
      UUID account,
      List<CanonicalGameplayRosterClient.RosterActor> actors)
      throws Exception {
    return rosterResponse(request, account, request.getExpectedTarget(), actors);
  }

  private static CanonicalGameplayRosterResponse rosterResponse(
      CanonicalGameplayRosterRequest request,
      UUID account,
      CanonicalGameplayRosterTarget target,
      List<CanonicalGameplayRosterClient.RosterActor> actors)
      throws Exception {
    Method calculateDigest =
        CanonicalGameplayRosterClient.class.getDeclaredMethod(
            "calculateSnapshotDigest", UUID.class, CanonicalGameplayRosterTarget.class, List.class);
    calculateDigest.setAccessible(true);
    String digest = (String) calculateDigest.invoke(null, account, target, actors);
    var builder =
        CanonicalGameplayRosterResponse.newBuilder()
            .setCanonicalAccountUuid(account.toString())
            .setTarget(target)
            .setSnapshotUuid(SNAPSHOT.toString())
            .setSnapshotDigest(digest);
    for (CanonicalGameplayRosterClient.RosterActor actor : actors) {
      builder.addActors(
          CanonicalGameplayRosterActor.newBuilder()
              .setCharacterUuid(actor.characterUuid().toString())
              .setDisplayName(actor.displayName())
              .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER));
    }
    return builder.build();
  }

  private static CanonicalGameplayRosterSelectedAssignmentResponse assignmentResponse(
      CanonicalGameplayRosterSelectedAssignmentRequest request, boolean changedSnapshot) {
    CanonicalGameplayRosterSelectedAssignmentResponse.Builder response =
        CanonicalGameplayRosterSelectedAssignmentResponse.newBuilder()
            .setRequestUuid(request.getRequestUuid())
            .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
            .setSelectedCharacterUuid(request.getSelectedCharacterUuid())
            .setTarget(request.getExpectedTarget())
            .setSnapshot(request.getExpectedSnapshot())
            .setAssignmentUuid(ASSIGNMENT.toString())
            .setIntentDigest("c".repeat(64));
    if (changedSnapshot) {
      response.setSnapshot(
          request.getExpectedSnapshot().toBuilder()
              .setSnapshotUuid(uuid("12121212-1212-4212-8212-121212121212").toString()));
    }
    return response.build();
  }

  private static CanonicalGameplayRosterClient rosterClient(
      CanonicalGameplayRosterServiceGrpc.CanonicalGameplayRosterServiceBlockingStub stub)
      throws Exception {
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain("game-session-client.crt");
    tls.setPrivateKey("game-session-client.key");
    tls.setCaCert("entity-management-ca.crt");
    var client =
        new CanonicalGameplayRosterClient(
            new ServiceEndpointsProperties(), tls, mock(GrpcChannelFactory.class), "test");
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, stub);
    return client;
  }

  private static PlayerExecutionContext context(CanonicalPlayableTarget route, String requestId) {
    return PlayerExecutionContext.newBuilder()
        .setAccountId(ACCOUNT.toString())
        .setTenantId(route.canonicalTenantId().toString())
        .setPlayableStateNamespaceId(route.playableStateNamespaceId().toString())
        .setGameInstanceId(route.canonicalGameInstanceId().toString())
        .setCharacterId("")
        .setSessionId("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
        .setRealmId(route.realmId().toString())
        .setRequestId(requestId)
        .setPlayableStateScope(route.playableStateScope())
        .build();
  }

  private static List<CanonicalGameplayRosterClient.RosterActor> oneActor() {
    return List.of(new CanonicalGameplayRosterClient.RosterActor(FIRST_ACTOR, "Pilot One"));
  }

  private static List<CanonicalGameplayRosterClient.RosterActor> twoActors() {
    return List.of(
        new CanonicalGameplayRosterClient.RosterActor(FIRST_ACTOR, "Pilot One"),
        new CanonicalGameplayRosterClient.RosterActor(SECOND_ACTOR, "Pilot Two"));
  }

  private static void assertDenied(
      CanonicalGameplayRosterSelectionService.Result result,
      CanonicalGameplayRosterSelectionService.Denial reason) {
    assertThat(result).isEqualTo(new CanonicalGameplayRosterSelectionService.Denied(reason));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record TestHarness(
      CanonicalGameplayRosterSelectionService service,
      CanonicalPlayableTarget route,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      CanonicalInitialAdmissionRepository admissionRepository,
      CanonicalGameInstanceLaunchAssociationRepository launchRepository,
      GameDesignPublishedRealmPolicyClient policyClient,
      CanonicalGameplayRosterServiceGrpc.CanonicalGameplayRosterServiceBlockingStub stub) {}
}
