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
import net.firedevops.firemud.gamesession.charactercreate.GameSessionCharacterCreatePublicSourceService;
import net.firedevops.firemud.gamesession.charactercreate.GameSessionCharacterCreatePublicSourceService.ResolvedPlayerCreatedRosterSource;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient;
import net.firedevops.firemud.gamesession.client.CanonicalPlayerCreatedRosterClient;
import net.firedevops.firemud.gamesession.client.CanonicalPlayerCreatedRosterClient.VerifiedPlayerCreatedCharacter;
import net.firedevops.firemud.gamesession.client.CanonicalPlayerCreatedRosterClient.VerifiedPlayerCreatedRoster;
import net.firedevops.firemud.gamesession.client.GameDesignPublishedRealmPolicyClient;
import net.firedevops.firemud.gamesession.command.text.CanonicalGameplayActorSelection;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalPublishedPlayerRoute;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayRosterReferenceRepository.SelectionOutcome;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterSelectionService;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService;
import net.firedevops.firemud.gamesession.testing.ControlledPlayerCreatedRosterValues;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceRequest;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import support.net.firedevops.firemud.gamesession.PublishedRealmPolicyEvidenceFixture;

class CanonicalGameplayRosterSelectionServiceTest {
  private static final UUID ACCOUNT = uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID FIRST_ACTOR = uuid("77777777-7777-4777-8777-777777777777");
  private static final UUID SECOND_ACTOR = uuid("99999999-9999-4999-8999-999999999999");
  private static final UUID SNAPSHOT = uuid("88888888-8888-4888-8888-888888888888");
  private static final UUID CHANGED_SNAPSHOT = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID ASSIGNMENT = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

  @Test
  void returnsMenuForMultipleActorsThenReadsOnlyTheSelectedOrdinalOwnerReference()
      throws Exception {
    var harness = harness(twoActors());

    var menu =
        harness
            .service()
            .select(context(harness.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null, null);

    assertThat(menu).isInstanceOf(CanonicalGameplayRosterSelectionService.SelectionRequired.class);
    var requiredMenu = (CanonicalGameplayRosterSelectionService.SelectionRequired) menu;
    assertThat(requiredMenu.choices())
        .isEqualTo(
            List.of(
                new CanonicalGameplayActorSelection.Choice(1, "Pilot One"),
                new CanonicalGameplayActorSelection.Choice(2, "Pilot Two")));
    assertThat(requiredMenu.menuIdentity().snapshotUuid()).isEqualTo(SNAPSHOT);
    assertThat(requiredMenu.menuIdentity().snapshotDigest()).matches("[0-9a-f]{64}");
    verify(harness.stub(), never()).readSelectedPreseededAssignment(any());

    PlayerExecutionContext input = context(harness.route(), "ffffffff-ffff-4fff-8fff-ffffffffffff");
    var selected = harness.service().select(input, "2", requiredMenu.menuIdentity());

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
    assertThat(selectedContext.getRequestId()).isEqualTo(input.getRequestId());
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
    assertThat(requestCaptor.getValue().getExpectedSnapshot().getSnapshotDigest())
        .isEqualTo(requiredMenu.menuIdentity().snapshotDigest());

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
    UUID foreignTenant = uuid("13131313-1313-4313-8313-131313131313");
    var mainTenantCatalog =
        harness
            .catalogRepository()
            .readUniqueVisiblePublicProduction(
                harness.route().targetNamespace(), harness.route().canonicalTenantId());
    // Deliberately return the valid main-tenant owner row for a foreign-tenant query.
    when(harness
            .catalogRepository()
            .readUniqueVisiblePublicProduction(harness.route().targetNamespace(), foreignTenant))
        .thenReturn(mainTenantCatalog);

    assertDenied(
        harness
            .service()
            .select(valid.toBuilder().setTenantId(foreignTenant.toString()).build(), null, null),
        CanonicalGameplayRosterSelectionService.Denial.ROUTE_MISMATCH);

    assertDenied(
        harness
            .service()
            .select(
                valid.toBuilder()
                    .setPlayableStateNamespaceId(
                        uuid("12121212-1212-4212-8212-121212121212").toString())
                    .build(),
                null,
                null),
        CanonicalGameplayRosterSelectionService.Denial.ROUTE_MISMATCH);
    assertDenied(
        harness
            .service()
            .select(
                valid.toBuilder()
                    .setGameInstanceId(uuid("12121212-1212-4212-8212-121212121212").toString())
                    .build(),
                null,
                null),
        CanonicalGameplayRosterSelectionService.Denial.ROUTE_MISMATCH);
    assertDenied(
        harness
            .service()
            .select(
                valid.toBuilder()
                    .setRealmId(uuid("12121212-1212-4212-8212-121212121212").toString())
                    .build(),
                null,
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
            .select(
                context(accountMismatch.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                null,
                null),
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
            .select(
                context(targetMismatch.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                null,
                null),
        CanonicalGameplayRosterSelectionService.Denial.INVALID_ROSTER_PROOF);
    verify(targetMismatch.stub(), never()).readSelectedPreseededAssignment(any());
  }

  @Test
  void returnsExplicitSelectionAndSelectorDenialsWithoutAssignmentReads() throws Exception {
    var multiple = harness(twoActors());
    var menu =
        (CanonicalGameplayRosterSelectionService.SelectionRequired)
            multiple
                .service()
                .select(
                    context(multiple.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null, null);
    assertDenied(
        multiple
            .service()
            .select(
                context(multiple.route(), "ffffffff-ffff-4fff-8fff-ffffffffffff"),
                "Pilot Two",
                menu.menuIdentity()),
        CanonicalGameplayRosterSelectionService.Denial.INVALID_SELECTOR);
    assertDenied(
        multiple
            .service()
            .select(
                context(multiple.route(), "abababab-abab-4bab-8bab-abababababab"),
                "3",
                menu.menuIdentity()),
        CanonicalGameplayRosterSelectionService.Denial.SELECTOR_OUT_OF_RANGE);
    verify(multiple.stub(), never()).readSelectedPreseededAssignment(any());

    var empty = harness(List.of());
    assertDenied(
        empty
            .service()
            .select(context(empty.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null, null),
        CanonicalGameplayRosterSelectionService.Denial.NO_PRESEEDED_ACTOR);
    verify(empty.stub(), never()).readSelectedPreseededAssignment(any());
  }

  @Test
  void rejectsOversizedSelectorsBeforeAnyRouteOrRosterOperationAndAllows128CharacterInput()
      throws Exception {
    var published = PublishedRealmPolicyEvidenceFixture.canonicalPublishedRoute();
    var routes = mock(CanonicalPlayerRouteReadService.class);
    var publications = mock(CanonicalPublishedPlayerRouteReadService.class);
    var client = mock(CanonicalGameplayRosterClient.class);
    var service = new CanonicalGameplayRosterSelectionService(routes, publications, client);

    assertDenied(
        service.select(
            context(published.route(), UUID.randomUUID().toString()), "x".repeat(129), null),
        CanonicalGameplayRosterSelectionService.Denial.INVALID_SELECTOR);
    verifyNoInteractions(routes, publications, client);

    var boundary = harness(twoActors());
    String boundarySelector = "x".repeat(128);
    assertDenied(
        boundary
            .service()
            .select(
                context(boundary.route(), UUID.randomUUID().toString()), boundarySelector, null),
        CanonicalGameplayRosterSelectionService.Denial.MISSING_MENU_IDENTITY);
    verify(boundary.stub()).listPreseededRoster(any());
    verify(boundary.stub(), never()).readSelectedPreseededAssignment(any());
  }

  @Test
  void requiresASelectionMenuIdentityForValidExplicitOrdinals() throws Exception {
    var harness = harness(twoActors());
    var menu =
        (CanonicalGameplayRosterSelectionService.SelectionRequired)
            harness
                .service()
                .select(
                    context(harness.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null, null);

    assertDenied(
        harness
            .service()
            .select(context(harness.route(), "ffffffff-ffff-4fff-8fff-ffffffffffff"), "2", null),
        CanonicalGameplayRosterSelectionService.Denial.MISSING_MENU_IDENTITY);
    assertDenied(
        harness
            .service()
            .select(
                context(harness.route(), "abababab-abab-4bab-8bab-abababababab"),
                "2",
                new CanonicalGameplayRosterSelectionService.SelectionMenuIdentity(
                    menu.menuIdentity().snapshotUuid(), "0".repeat(64))),
        CanonicalGameplayRosterSelectionService.Denial.STALE_MENU_IDENTITY);

    verify(harness.stub(), never()).readSelectedPreseededAssignment(any());
  }

  @Test
  void rejectsChangedMenuSnapshotUuidAndReorderedRosterBeforeAssignmentRead() throws Exception {
    var changedUuid = harness(twoActors());
    AtomicInteger changedUuidReads = new AtomicInteger();
    doAnswer(
            invocation -> {
              CanonicalGameplayRosterRequest request = invocation.getArgument(0);
              UUID snapshotUuid =
                  changedUuidReads.getAndIncrement() == 0 ? SNAPSHOT : CHANGED_SNAPSHOT;
              return rosterResponse(
                  request, ACCOUNT, request.getExpectedTarget(), twoActors(), snapshotUuid);
            })
        .when(changedUuid.stub())
        .listPreseededRoster(any());
    var uuidMenu =
        (CanonicalGameplayRosterSelectionService.SelectionRequired)
            changedUuid
                .service()
                .select(
                    context(changedUuid.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                    null,
                    null);
    assertDenied(
        changedUuid
            .service()
            .select(
                context(changedUuid.route(), "ffffffff-ffff-4fff-8fff-ffffffffffff"),
                "1",
                uuidMenu.menuIdentity()),
        CanonicalGameplayRosterSelectionService.Denial.STALE_MENU_IDENTITY);
    verify(changedUuid.stub(), never()).readSelectedPreseededAssignment(any());

    var reorderedRoster = harness(twoActors());
    AtomicInteger reorderedReads = new AtomicInteger();
    List<CanonicalGameplayRosterClient.RosterActor> reorderedActors =
        List.of(
            new CanonicalGameplayRosterClient.RosterActor(SECOND_ACTOR, "Pilot Two"),
            new CanonicalGameplayRosterClient.RosterActor(FIRST_ACTOR, "Pilot One"));
    doAnswer(
            invocation -> {
              CanonicalGameplayRosterRequest request = invocation.getArgument(0);
              List<CanonicalGameplayRosterClient.RosterActor> actors =
                  reorderedReads.getAndIncrement() == 0 ? twoActors() : reorderedActors;
              return rosterResponse(
                  request, ACCOUNT, request.getExpectedTarget(), actors, SNAPSHOT);
            })
        .when(reorderedRoster.stub())
        .listPreseededRoster(any());
    var reorderedMenu =
        (CanonicalGameplayRosterSelectionService.SelectionRequired)
            reorderedRoster
                .service()
                .select(
                    context(reorderedRoster.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                    null,
                    null);
    assertDenied(
        reorderedRoster
            .service()
            .select(
                context(reorderedRoster.route(), "ffffffff-ffff-4fff-8fff-ffffffffffff"),
                "1",
                reorderedMenu.menuIdentity()),
        CanonicalGameplayRosterSelectionService.Denial.STALE_MENU_IDENTITY);
    verify(reorderedRoster.stub(), never()).readSelectedPreseededAssignment(any());
  }

  @Test
  void staleMenuIdentityPrecedesOrdinalResolutionAgainstARefreshedSmallerRoster() throws Exception {
    var harness = harness(twoActors());
    AtomicInteger reads = new AtomicInteger();
    doAnswer(
            invocation -> {
              CanonicalGameplayRosterRequest request = invocation.getArgument(0);
              boolean firstRead = reads.getAndIncrement() == 0;
              return rosterResponse(
                  request,
                  ACCOUNT,
                  request.getExpectedTarget(),
                  firstRead
                      ? twoActors()
                      : List.of(
                          new CanonicalGameplayRosterClient.RosterActor(FIRST_ACTOR, "Pilot One")),
                  firstRead ? SNAPSHOT : CHANGED_SNAPSHOT);
            })
        .when(harness.stub())
        .listPreseededRoster(any());

    var menu =
        (CanonicalGameplayRosterSelectionService.SelectionRequired)
            harness
                .service()
                .select(
                    context(harness.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null, null);
    assertDenied(
        harness
            .service()
            .select(
                context(harness.route(), "ffffffff-ffff-4fff-8fff-ffffffffffff"),
                "2",
                menu.menuIdentity()),
        CanonicalGameplayRosterSelectionService.Denial.STALE_MENU_IDENTITY);
    verify(harness.stub(), never()).readSelectedPreseededAssignment(any());
  }

  @Test
  void rejectsMovedRouteAfterRosterAndAfterAssignmentNetworkEvidence() throws Exception {
    var changedAfterList = harness(oneActor());
    configureRouteMovement(changedAfterList, 3);
    assertDenied(
        changedAfterList
            .service()
            .select(
                context(changedAfterList.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                null,
                null),
        CanonicalGameplayRosterSelectionService.Denial.ROUTE_CHANGED);
    verify(changedAfterList.stub(), never()).readSelectedPreseededAssignment(any());

    var changedAfterAssignment = harness(oneActor());
    configureRouteMovement(changedAfterAssignment, 5);
    assertDenied(
        changedAfterAssignment
            .service()
            .select(
                context(changedAfterAssignment.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                null,
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
            .select(
                context(changedSnapshot.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                null,
                null),
        CanonicalGameplayRosterSelectionService.Denial.INVALID_ASSIGNMENT_PROOF);

    var unavailable = harness(oneActor());
    doThrow(Status.UNAVAILABLE.withDescription("Entity offline").asRuntimeException())
        .when(unavailable.stub())
        .listPreseededRoster(any());
    assertDenied(
        unavailable
            .service()
            .select(
                context(unavailable.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null, null),
        CanonicalGameplayRosterSelectionService.Denial.ROSTER_UNAVAILABLE);
    verify(unavailable.stub(), never()).readSelectedPreseededAssignment(any());
  }

  @Test
  void mapsEntityGrpcFailuresByStatusAndReadBoundary() throws Exception {
    for (Status.Code code :
        List.of(
            Status.Code.UNAVAILABLE,
            Status.Code.DEADLINE_EXCEEDED,
            Status.Code.RESOURCE_EXHAUSTED,
            Status.Code.ABORTED)) {
      var rosterUnavailable = harness(oneActor());
      doThrow(Status.fromCode(code).withDescription("Entity read failed").asRuntimeException())
          .when(rosterUnavailable.stub())
          .listPreseededRoster(any());
      assertDenied(
          rosterUnavailable
              .service()
              .select(
                  context(rosterUnavailable.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                  null,
                  null),
          CanonicalGameplayRosterSelectionService.Denial.ROSTER_UNAVAILABLE);

      var assignmentUnavailable = harness(oneActor());
      doThrow(Status.fromCode(code).withDescription("Entity read failed").asRuntimeException())
          .when(assignmentUnavailable.stub())
          .readSelectedPreseededAssignment(any());
      assertDenied(
          assignmentUnavailable
              .service()
              .select(
                  context(assignmentUnavailable.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                  null,
                  null),
          CanonicalGameplayRosterSelectionService.Denial.ASSIGNMENT_UNAVAILABLE);
    }

    var staleRouteOrPolicy = harness(oneActor());
    doThrow(
            Status.FAILED_PRECONDITION
                .withDescription("route changed or policy is no longer PRESEEDED_ONLY")
                .asRuntimeException())
        .when(staleRouteOrPolicy.stub())
        .listPreseededRoster(any());
    assertDenied(
        staleRouteOrPolicy
            .service()
            .select(
                context(staleRouteOrPolicy.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                null,
                null),
        CanonicalGameplayRosterSelectionService.Denial.ROUTE_CHANGED);

    var changedAssignment = harness(oneActor());
    doThrow(
            Status.FAILED_PRECONDITION
                .withDescription("snapshot changed or selected actor is absent")
                .asRuntimeException())
        .when(changedAssignment.stub())
        .readSelectedPreseededAssignment(any());
    assertDenied(
        changedAssignment
            .service()
            .select(
                context(changedAssignment.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                null,
                null),
        CanonicalGameplayRosterSelectionService.Denial.ROUTE_CHANGED);

    var changedAssignmentWithMenu = harness(twoActors());
    doThrow(
            Status.FAILED_PRECONDITION
                .withDescription("snapshot changed or selected actor is absent")
                .asRuntimeException())
        .when(changedAssignmentWithMenu.stub())
        .readSelectedPreseededAssignment(any());
    var menuResult =
        changedAssignmentWithMenu
            .service()
            .select(
                context(changedAssignmentWithMenu.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                null,
                null);
    assertThat(menuResult)
        .isInstanceOf(CanonicalGameplayRosterSelectionService.SelectionRequired.class);
    var menuIdentity =
        ((CanonicalGameplayRosterSelectionService.SelectionRequired) menuResult).menuIdentity();
    assertDenied(
        changedAssignmentWithMenu
            .service()
            .select(
                context(changedAssignmentWithMenu.route(), "ffffffff-ffff-4fff-8fff-ffffffffffff"),
                "1",
                menuIdentity),
        CanonicalGameplayRosterSelectionService.Denial.STALE_MENU_IDENTITY);

    for (Status.Code code : List.of(Status.Code.PERMISSION_DENIED, Status.Code.INVALID_ARGUMENT)) {
      var invalidRoster = harness(oneActor());
      doThrow(Status.fromCode(code).withDescription("roster rejected").asRuntimeException())
          .when(invalidRoster.stub())
          .listPreseededRoster(any());
      assertDenied(
          invalidRoster
              .service()
              .select(
                  context(invalidRoster.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                  null,
                  null),
          CanonicalGameplayRosterSelectionService.Denial.INVALID_ROSTER_PROOF);

      var invalidAssignment = harness(oneActor());
      doThrow(Status.fromCode(code).withDescription("assignment rejected").asRuntimeException())
          .when(invalidAssignment.stub())
          .readSelectedPreseededAssignment(any());
      assertDenied(
          invalidAssignment
              .service()
              .select(
                  context(invalidAssignment.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                  null,
                  null),
          CanonicalGameplayRosterSelectionService.Denial.INVALID_ASSIGNMENT_PROOF);
    }

    var staleRoute = harness(oneActor());
    doThrow(Status.FAILED_PRECONDITION.withDescription("route changed").asRuntimeException())
        .when(staleRoute.policyClient())
        .listPublishedRealmEntryPolicies(
            staleRoute.route().canonicalTenantId(), staleRoute.route().canonicalVersionId());
    assertDenied(
        staleRoute
            .service()
            .select(
                context(staleRoute.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null, null),
        CanonicalGameplayRosterSelectionService.Denial.ROUTE_CHANGED);

    var invalidRoute = harness(oneActor());
    doThrow(Status.PERMISSION_DENIED.withDescription("route rejected").asRuntimeException())
        .when(invalidRoute.policyClient())
        .listPublishedRealmEntryPolicies(
            invalidRoute.route().canonicalTenantId(), invalidRoute.route().canonicalVersionId());
    assertDenied(
        invalidRoute
            .service()
            .select(
                context(invalidRoute.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null, null),
        CanonicalGameplayRosterSelectionService.Denial.INVALID_ROUTE_PROOF);
  }

  @Test
  void deniesAmbientTransactionBeforeAnyRouteOrOwnerRead() throws Exception {
    var harness = harness(oneActor());
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertDenied(
          harness
              .service()
              .select(context(harness.route(), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), null, null),
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

  @Test
  void reserveAndListPreserveOwnerRouteFailureCategoriesAndDeniedCompletion() {
    var failures =
        List.of(
            java.util.Map.entry(
                new CanonicalPublishedPlayerRouteReadService.StaleAuthorityException(
                    "owner target moved"),
                CanonicalGameplayRosterSelectionService.Denial.ROUTE_CHANGED),
            java.util.Map.entry(
                new CanonicalPublishedPlayerRouteReadService.StorageUnavailableException(
                    "owner storage unavailable"),
                CanonicalGameplayRosterSelectionService.Denial.ROUTE_UNAVAILABLE),
            java.util.Map.entry(
                new CanonicalPublishedPlayerRouteReadService.GameDesignUnavailableException(
                    "policy owner unavailable", null),
                CanonicalGameplayRosterSelectionService.Denial.ROUTE_UNAVAILABLE),
            java.util.Map.entry(
                new CanonicalPlayerRouteReadService.ReadUnavailableException(
                    "route owner unavailable"),
                CanonicalGameplayRosterSelectionService.Denial.ROUTE_UNAVAILABLE),
            java.util.Map.entry(
                new IllegalArgumentException("invalid roster proof"),
                CanonicalGameplayRosterSelectionService.Denial.INVALID_ROSTER_PROOF));
    for (boolean duringReserve : List.of(true, false)) {
      for (var failure : failures) {
        var published = PublishedRealmPolicyEvidenceFixture.canonicalPublishedRoute();
        var routes = mock(CanonicalPlayerRouteReadService.class);
        var publications = mock(CanonicalPublishedPlayerRouteReadService.class);
        var client = mock(CanonicalGameplayRosterClient.class);
        when(routes.readCurrentTarget(published.route().canonicalTenantId()))
            .thenReturn(published.route());
        when(publications.readCurrent(published.route())).thenReturn(published);
        if (duringReserve) {
          doThrow(failure.getKey())
              .when(client)
              .reserveSelectionInput(any(), any(), any(), any(), any());
        } else {
          when(client.listPreseededRoster(any(), any())).thenThrow(failure.getKey());
        }
        var service = new CanonicalGameplayRosterSelectionService(routes, publications, client);
        assertDenied(
            service.select(context(published.route(), UUID.randomUUID().toString()), null, null),
            failure.getValue());
        if (failure.getValue() == CanonicalGameplayRosterSelectionService.Denial.ROUTE_CHANGED) {
          verify(client)
              .completeSelection(
                  any(),
                  any(),
                  org.mockito.ArgumentMatchers.eq(
                      net.firedevops.firemud.gamesession.repository
                          .CanonicalGameplayRosterReferenceRepository.SelectionOutcome.DENIED));
        } else {
          verify(client, never()).completeSelection(any(), any(), any());
        }
        if (duringReserve) verify(client, never()).listPreseededRoster(any(), any());
        verify(client, never()).readSelectedPreseededAssignment(any(), any());
      }
    }
  }

  @Test
  void failedTerminalWritePreservesDenialButRefusesSuccessfulSelectionAndMenu() throws Exception {
    var denied = harness(List.of(), true);
    assertDenied(
        denied.service().select(context(denied.route(), UUID.randomUUID().toString()), null, null),
        CanonicalGameplayRosterSelectionService.Denial.NO_PRESEEDED_ACTOR);
    var selected = harness(oneActor(), true);
    assertDenied(
        selected
            .service()
            .select(context(selected.route(), UUID.randomUUID().toString()), null, null),
        CanonicalGameplayRosterSelectionService.Denial.ROSTER_UNAVAILABLE);
    var menu = harness(twoActors(), true);
    assertDenied(
        menu.service().select(context(menu.route(), UUID.randomUUID().toString()), null, null),
        CanonicalGameplayRosterSelectionService.Denial.ROSTER_UNAVAILABLE);
  }

  @Test
  void createdZeroDeniesAndOneReturnsTheDistinctCompleteTerminalReference() throws Exception {
    var empty = createdHarness(0);
    assertDenied(
        empty.service().select(empty.context(), null, null),
        CanonicalGameplayRosterSelectionService.Denial.NO_PLAYER_CREATED_ACTOR);
    verify(empty.client())
        .completeSelection(empty.source(), empty.context(), SelectionOutcome.DENIED);
    verify(empty.client(), never()).readSelectedPlayerCreatedCharacter(any(), any());
    verifyNoInteractions(empty.preseeded(), empty.publications());

    var one = createdHarness(1);
    assertThat(one.service().select(one.context(), null, null))
        .isInstanceOfSatisfying(
            CanonicalGameplayRosterSelectionService.SelectedPlayerCreatedOwnerReference.class,
            selected -> {
              assertThat(selected.ownerReference()).isSameAs(one.reference());
              assertThat(selected.displayName()).isEqualTo("Created One");
              assertThat(selected.ordinal()).isEqualTo(1);
              assertThat(selected.ownerReference().terminal().toByteString())
                  .isEqualTo(one.reference().response().getTerminal().toByteString());
              assertThat(selected.ownerReference().creationOperationUuid().toString())
                  .isEqualTo(one.reference().response().getCreationOperationUuid());
            });
    verify(one.client()).completeSelection(one.source(), one.context(), SelectionOutcome.SELECTED);
    verify(one.sources(), org.mockito.Mockito.times(3)).resolvePlayerCreatedRosterSource(any());
    var request = org.mockito.ArgumentCaptor.forClass(ReadPlayerCreatedRosterSourceRequest.class);
    verify(one.sources(), org.mockito.Mockito.times(3))
        .resolvePlayerCreatedRosterSource(request.capture());
    var actual = request.getValue();
    assertThat(actual.getSchemaVersion()).isEqualTo(1);
    assertThat(actual.getReadRequestId()).isEqualTo(one.context().getRequestId());
    assertThat(actual.getTargetNamespace()).isEqualTo(one.route().targetNamespace());
    assertThat(actual.getCanonicalTenantId()).isEqualTo(one.route().canonicalTenantId().toString());
    assertThat(actual.getRealmId()).isEqualTo(one.route().realmId().toString());
    assertThat(actual.getWorldSlug()).isEqualTo(one.route().worldSlug());
    assertThat(actual.getRealmSlug()).isEqualTo(one.route().realmSlug());
    assertThat(actual.getPlayableStateNamespaceId())
        .isEqualTo(one.route().playableStateNamespaceId().toString());
    assertThat(actual.getPlayableStateScope()).isEqualTo("SHARED");
    assertThat(actual.getCanonicalGameInstanceId())
        .isEqualTo(one.route().canonicalGameInstanceId().toString());
    assertThat(actual.getCatalogRevision()).isEqualTo(one.route().catalogRevision());
    assertThat(actual.getPointerVersion()).isEqualTo(one.route().pointerVersion());
    assertThat(actual.getExpectedCanonicalVersionId())
        .isEqualTo(one.route().canonicalVersionId().toString());
    assertThat(actual.getActiveWorldEpoch()).isEqualTo(one.route().activeWorldEpoch());
    verifyNoInteractions(one.preseeded(), one.publications());
  }

  @Test
  void createdManyUsesExactMenuAndOnlyOneBasedOrdinals() throws Exception {
    var menuHarness = createdHarness(2);
    var menu =
        (CanonicalGameplayRosterSelectionService.SelectionRequired)
            menuHarness.service().select(menuHarness.context(), null, null);
    assertThat(menu.choices())
        .containsExactly(
            new CanonicalGameplayActorSelection.Choice(1, "Created One"),
            new CanonicalGameplayActorSelection.Choice(2, "Created Two"));
    verify(menuHarness.client())
        .completeSelection(menuHarness.source(), menuHarness.context(), SelectionOutcome.MENU);
    // A later explicit choice has its own original operation and retains the earlier menu identity.
    var choice = createdHarness(2);
    assertThat(choice.service().select(choice.context(), "2", menu.menuIdentity()))
        .isInstanceOfSatisfying(
            CanonicalGameplayRosterSelectionService.SelectedPlayerCreatedOwnerReference.class,
            selected ->
                assertThat(selected.ownerReference().selectedCharacterUuid())
                    .isEqualTo(SECOND_ACTOR));
    verify(choice.client())
        .reserveSelectionInput(
            choice.source(),
            choice.context(),
            "2",
            menu.menuIdentity().snapshotUuid(),
            menu.menuIdentity().snapshotDigest());
    for (String selector : List.of(FIRST_ACTOR.toString(), "Created One", "01", "0", "3")) {
      var invalid = createdHarness(2);
      assertDenied(
          invalid.service().select(invalid.context(), selector, menu.menuIdentity()),
          selector.equals("3")
              ? CanonicalGameplayRosterSelectionService.Denial.SELECTOR_OUT_OF_RANGE
              : CanonicalGameplayRosterSelectionService.Denial.INVALID_SELECTOR);
      verify(invalid.client())
          .completeSelection(invalid.source(), invalid.context(), SelectionOutcome.DENIED);
      verify(invalid.client(), never()).readSelectedPlayerCreatedCharacter(any(), any());
    }
    var missing = createdHarness(2);
    assertDenied(
        missing.service().select(missing.context(), "1", null),
        CanonicalGameplayRosterSelectionService.Denial.MISSING_MENU_IDENTITY);
    var stale = createdHarness(2);
    assertDenied(
        stale
            .service()
            .select(
                stale.context(),
                "1",
                new CanonicalGameplayRosterSelectionService.SelectionMenuIdentity(
                    CHANGED_SNAPSHOT, menu.menuIdentity().snapshotDigest())),
        CanonicalGameplayRosterSelectionService.Denial.STALE_MENU_IDENTITY);
  }

  @Test
  void createdDispatchNeverFallsBackAndUnsupportedPoliciesStayDenied() throws Exception {
    var h = createdHarness(1);
    for (String policy : List.of("AUTO_PROVISIONED", "UNKNOWN")) {
      when(h.routes().readCurrentTarget(h.route().canonicalTenantId()))
          .thenReturn(withPolicy(h.route(), policy));
      assertDenied(
          h.service().select(h.context(), null, null),
          CanonicalGameplayRosterSelectionService.Denial.UNSUPPORTED_ENTRY_POLICY);
    }
    verifyNoInteractions(h.sources(), h.client(), h.preseeded(), h.publications());
    when(h.routes().readCurrentTarget(h.route().canonicalTenantId())).thenReturn(h.route());
    for (var service :
        List.of(
            new CanonicalGameplayRosterSelectionService(
                h.routes(), h.publications(), h.preseeded()),
            new CanonicalGameplayRosterSelectionService(
                h.routes(), h.publications(), h.preseeded(), h.sources(), null),
            new CanonicalGameplayRosterSelectionService(
                h.routes(), h.publications(), h.preseeded(), null, h.client()))) {
      assertDenied(
          service.select(h.context(), null, null),
          CanonicalGameplayRosterSelectionService.Denial.ROSTER_UNAVAILABLE);
    }
    verifyNoInteractions(h.sources(), h.client(), h.preseeded(), h.publications());
    var old = PublishedRealmPolicyEvidenceFixture.canonicalPublishedRoute();
    var routes = mock(CanonicalPlayerRouteReadService.class);
    var publications = mock(CanonicalPublishedPlayerRouteReadService.class);
    var preseeded = mock(CanonicalGameplayRosterClient.class);
    var sources = mock(GameSessionCharacterCreatePublicSourceService.class);
    var created = mock(CanonicalPlayerCreatedRosterClient.class);
    when(routes.readCurrentTarget(old.route().canonicalTenantId())).thenReturn(old.route());
    when(publications.readCurrent(old.route())).thenReturn(old);
    when(preseeded.listPreseededRoster(any(), any()))
        .thenReturn(PublishedRealmPolicyEvidenceFixture.rosterSnapshot(old, ACCOUNT, List.of()));
    var fiveArgumentPreseeded =
        new CanonicalGameplayRosterSelectionService(
            routes, publications, preseeded, sources, created);
    assertDenied(
        fiveArgumentPreseeded.select(
            context(old.route(), UUID.randomUUID().toString()), null, null),
        CanonicalGameplayRosterSelectionService.Denial.NO_PRESEEDED_ACTOR);
    verifyNoInteractions(sources, created);
  }

  @Test
  void createdSourceTargetDescriptorAndPolicyRacesAfterEitherRpcRefuseSelection() throws Exception {
    for (boolean afterSelected : List.of(false, true)) {
      for (String changedPart : List.of("target", "source", "policy")) {
        var h = createdHarness(1);
        var changed = mock(ResolvedPlayerCreatedRosterSource.class);
        var changedRoute =
            changedPart.equals("policy") ? withPolicy(h.route(), "PRESEEDED_ONLY") : h.route();
        var changedTarget =
            changedPart.equals("target")
                ? h.source().target().toBuilder()
                    .setPointerVersion(h.route().pointerVersion() + 1)
                    .build()
                : h.source().target();
        var changedIdentity =
            changedPart.equals("source")
                ? h.source().sourceIdentity().toBuilder()
                    .setDescriptorRevisionUuid(UUID.randomUUID().toString())
                    .build()
                : h.source().sourceIdentity();
        when(changed.route()).thenReturn(changedRoute);
        when(changed.target()).thenReturn(changedTarget);
        when(changed.sourceIdentity()).thenReturn(changedIdentity);
        if (afterSelected)
          when(h.sources().resolvePlayerCreatedRosterSource(any()))
              .thenReturn(h.source(), h.source(), changed);
        else
          when(h.sources().resolvePlayerCreatedRosterSource(any())).thenReturn(h.source(), changed);
        assertDenied(
            h.service().select(h.context(), null, null),
            CanonicalGameplayRosterSelectionService.Denial.ROUTE_CHANGED);
        verify(h.client()).completeSelection(h.source(), h.context(), SelectionOutcome.DENIED);
        if (!afterSelected)
          verify(h.client(), never()).readSelectedPlayerCreatedCharacter(any(), any());
      }
    }
  }

  @Test
  void createdSourceRereadIgnoresOnlyCorrelationFieldsAndRequiresInitialFullRoute()
      throws Exception {
    var h = createdHarness(1);
    var same = mock(ResolvedPlayerCreatedRosterSource.class);
    var sameRoute = h.route();
    var sameTarget = h.source().target();
    var sameIdentity = h.source().sourceIdentity();
    when(same.route()).thenReturn(sameRoute);
    when(same.target()).thenReturn(sameTarget);
    when(same.sourceIdentity()).thenReturn(sameIdentity);
    when(same.response())
        .thenReturn(
            net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceResponse.newBuilder()
                .setRequest(
                    ReadPlayerCreatedRosterSourceRequest.newBuilder()
                        .setReadRequestId(UUID.randomUUID().toString()))
                .build());
    when(h.sources().resolvePlayerCreatedRosterSource(any())).thenReturn(h.source(), same, same);
    assertThat(h.service().select(h.context(), null, null))
        .isInstanceOf(
            CanonicalGameplayRosterSelectionService.SelectedPlayerCreatedOwnerReference.class);
    var changed = createdHarness(1);
    when(changed.source().route()).thenReturn(withPolicy(changed.route(), "AUTO_PROVISIONED"));
    assertDenied(
        changed.service().select(changed.context(), null, null),
        CanonicalGameplayRosterSelectionService.Denial.ROUTE_CHANGED);
    verifyNoInteractions(changed.client());
  }

  @Test
  void createdTransientAndUntrustedPeerFailuresNeverPrematurelyFinalize() throws Exception {
    for (boolean selectedRead : List.of(false, true)) {
      var unavailable = createdHarness(1);
      if (selectedRead)
        doThrow(Status.UNAVAILABLE.asRuntimeException())
            .when(unavailable.client())
            .readSelectedPlayerCreatedCharacter(any(), any());
      else
        doThrow(Status.UNAVAILABLE.asRuntimeException())
            .when(unavailable.client())
            .listPlayerCreatedRoster(any(), any());
      assertDenied(
          unavailable.service().select(unavailable.context(), null, null),
          selectedRead
              ? CanonicalGameplayRosterSelectionService.Denial.ASSIGNMENT_UNAVAILABLE
              : CanonicalGameplayRosterSelectionService.Denial.ROSTER_UNAVAILABLE);
      verify(unavailable.client(), never()).completeSelection(any(), any(), any());
      var wrongPeer = createdHarness(1);
      if (selectedRead)
        doThrow(Status.UNAUTHENTICATED.asRuntimeException())
            .when(wrongPeer.client())
            .readSelectedPlayerCreatedCharacter(any(), any());
      else
        doThrow(Status.UNAUTHENTICATED.asRuntimeException())
            .when(wrongPeer.client())
            .listPlayerCreatedRoster(any(), any());
      assertDenied(
          wrongPeer.service().select(wrongPeer.context(), null, null),
          selectedRead
              ? CanonicalGameplayRosterSelectionService.Denial.INVALID_ASSIGNMENT_PROOF
              : CanonicalGameplayRosterSelectionService.Denial.INVALID_ROSTER_PROOF);
      verify(wrongPeer.client(), never()).completeSelection(any(), any(), any());
      var storage = createdHarness(1);
      var failure =
          new CanonicalPublishedPlayerRouteReadService.StorageUnavailableException(
              "owner unavailable");
      if (selectedRead)
        doThrow(failure).when(storage.client()).readSelectedPlayerCreatedCharacter(any(), any());
      else doThrow(failure).when(storage.client()).listPlayerCreatedRoster(any(), any());
      assertDenied(
          storage.service().select(storage.context(), null, null),
          CanonicalGameplayRosterSelectionService.Denial.ROUTE_UNAVAILABLE);
      verify(storage.client(), never()).completeSelection(any(), any(), any());
    }
    // Controlled exception double checks dispatch, not upstream source custody.
    for (RuntimeException failure :
        List.of(
            Status.UNAVAILABLE.asRuntimeException(),
            mock(
                GameSessionCharacterCreatePublicSourceService.SourceDependencyUnavailableException
                    .class))) {
      for (int phase = 0; phase < 3; phase++) {
        var h = createdHarness(1);
        var calls = new AtomicInteger();
        int failedPhase = phase;
        when(h.sources().resolvePlayerCreatedRosterSource(any()))
            .thenAnswer(
                call -> {
                  assertNoAmbientSql();
                  if (calls.getAndIncrement() == failedPhase) throw failure;
                  return h.source();
                });
        assertDenied(
            h.service().select(h.context(), null, null),
            CanonicalGameplayRosterSelectionService.Denial.ROUTE_UNAVAILABLE);
        verify(h.client(), never()).completeSelection(any(), any(), any());
      }
    }
  }

  @Test
  void createdTypedStaleOwnerFailuresRetainRouteChangedAndDefinitiveDeniedCompletion()
      throws Exception {
    for (int phase = 0; phase < 3; phase++) {
      var h = createdHarness(1);
      var stale =
          new CanonicalPublishedPlayerRouteReadService.StaleAuthorityException(
              "owner source changed");
      if (phase == 0)
        doThrow(stale).when(h.client()).reserveSelectionInput(any(), any(), any(), any(), any());
      else if (phase == 1) doThrow(stale).when(h.client()).listPlayerCreatedRoster(any(), any());
      else doThrow(stale).when(h.client()).readSelectedPlayerCreatedCharacter(any(), any());
      assertDenied(
          h.service().select(h.context(), null, null),
          CanonicalGameplayRosterSelectionService.Denial.ROUTE_CHANGED);
      verify(h.client()).completeSelection(h.source(), h.context(), SelectionOutcome.DENIED);
    }
  }

  @Test
  void createdAccountOriginalAndSelectedCapabilitySubstitutionFailClosed() throws Exception {
    var account = createdHarness(1);
    when(account.roster().canonicalAccountUuid()).thenReturn(UUID.randomUUID());
    assertDenied(
        account.service().select(account.context(), null, null),
        CanonicalGameplayRosterSelectionService.Denial.INVALID_ROSTER_PROOF);
    var original = createdHarness(1);
    var substitutedOriginal =
        original.roster().originalSelection().toBuilder()
            .setSessionUuid(UUID.randomUUID().toString())
            .build();
    when(original.roster().originalSelection()).thenReturn(substitutedOriginal);
    assertDenied(
        original.service().select(original.context(), null, null),
        CanonicalGameplayRosterSelectionService.Denial.INVALID_ROSTER_PROOF);
    var selected = createdHarness(1);
    var substitutedRoster = mock(VerifiedPlayerCreatedRoster.class);
    when(selected.reference().roster()).thenReturn(substitutedRoster);
    assertDenied(
        selected.service().select(selected.context(), null, null),
        CanonicalGameplayRosterSelectionService.Denial.INVALID_ASSIGNMENT_PROOF);
    verify(selected.client(), never()).completeSelection(any(), any(), any());
  }

  @Test
  void createdContextAndAmbientSqlRefuseBeforeSourceAndTerminalWriteFailureStaysClosed()
      throws Exception {
    var h = createdHarness(1);
    assertDenied(
        h.service()
            .select(
                h.context().toBuilder().setCharacterId(FIRST_ACTOR.toString()).build(), null, null),
        CanonicalGameplayRosterSelectionService.Denial.INVALID_CONTEXT);
    assertDenied(
        h.service()
            .select(
                h.context().toBuilder().setRealmId(UUID.randomUUID().toString()).build(),
                null,
                null),
        CanonicalGameplayRosterSelectionService.Denial.ROUTE_MISMATCH);
    try {
      TransactionSynchronizationManager.initSynchronization();
      assertDenied(
          h.service().select(h.context(), null, null),
          CanonicalGameplayRosterSelectionService.Denial.AMBIENT_TRANSACTION);
    } finally {
      TransactionSynchronizationManager.clear();
    }
    verifyNoInteractions(h.sources(), h.client());
    for (int count : List.of(0, 1, 2)) {
      var terminalFailure = createdHarness(count);
      doThrow(new IllegalStateException("controlled missing terminal readback"))
          .when(terminalFailure.client())
          .completeSelection(any(), any(), any());
      assertDenied(
          terminalFailure.service().select(terminalFailure.context(), null, null),
          count == 0
              ? CanonicalGameplayRosterSelectionService.Denial.NO_PLAYER_CREATED_ACTOR
              : CanonicalGameplayRosterSelectionService.Denial.ROSTER_UNAVAILABLE);
    }
  }

  // These private-view/client-capability doubles establish orchestration, not authentication,
  // genuine source issuance or SQL. The complete terminal values use the existing controlled
  // CHARACTER_CREATE vector; actual Common/client grammar and physical owner proofs are separate.
  private static CreatedHarness createdHarness(int actorCount) throws Exception {
    var values =
        ControlledPlayerCreatedRosterValues.forSelection(
            actorCount, SNAPSHOT, FIRST_ACTOR, SECOND_ACTOR);
    var target = values.target();
    var identity = values.source();
    var actors = values.actors();
    String digest = values.roster().getRoster().getSnapshotDigest();
    var r = PublishedRealmPolicyEvidenceFixture.canonicalPublishedRoute().route();
    var route =
        new CanonicalPlayableTarget(
            values.namespace(),
            r.tenantSlug(),
            UUID.fromString(target.getTenantUuid()),
            target.getWorldSlug(),
            r.worldDisplayName(),
            UUID.fromString(target.getRealmUuid()),
            target.getRealmSlug(),
            r.realmDisplayName(),
            r.gameSessionTenantId(),
            r.gameInstanceId(),
            UUID.fromString(target.getPlayableStateNamespaceUuid()),
            "SHARED",
            UUID.fromString(target.getGameInstanceUuid()),
            UUID.fromString(target.getCanonicalVersionUuid()),
            r.runtimeVersionId(),
            target.getCatalogRevision(),
            target.getPointerVersion(),
            target.getAdmissionPointerSnapshotDigest(),
            target.getActiveWorldEpoch(),
            r.initialAdmissionRequestId(),
            r.initialAdmissionRequestDigest(),
            r.initialAdmissionOriginKind(),
            r.expectedPriorPointerVersion(),
            r.holdId(),
            r.holdFence(),
            r.holdBindingDigest(),
            r.auditEventId(),
            "sha256:" + target.getPublishedOwnerProofDigest(),
            r.ownerProofOutcome(),
            false,
            r.ownerProofTerminalAt(),
            "PLAYER_CREATED");
    var context =
        context(route, values.context().getRequestId()).toBuilder()
            .setAccountId(values.context().getAccountId())
            .setSessionId(values.context().getSessionId())
            .build();
    var source = mock(ResolvedPlayerCreatedRosterSource.class);
    when(source.route()).thenReturn(route);
    when(source.target()).thenReturn(target);
    when(source.sourceIdentity()).thenReturn(identity);
    var roster = mock(VerifiedPlayerCreatedRoster.class);
    when(roster.response()).thenReturn(values.roster());
    when(roster.resolvedSource()).thenReturn(source);
    when(roster.originalSelection()).thenReturn(values.original());
    when(roster.canonicalAccountUuid()).thenReturn(UUID.fromString(context.getAccountId()));
    when(roster.target()).thenReturn(target);
    when(roster.sourceIdentity()).thenReturn(identity);
    when(roster.snapshotUuid())
        .thenReturn(UUID.fromString(values.roster().getRoster().getSnapshotUuid()));
    when(roster.snapshotDigest()).thenReturn(digest);
    when(roster.actors()).thenReturn(actors);
    var references = new java.util.HashMap<UUID, VerifiedPlayerCreatedCharacter>();
    for (var actor : actors) {
      UUID actorUuid = UUID.fromString(actor.getCharacterUuid());
      var response = values.selected().get(actorUuid);
      var verified = mock(VerifiedPlayerCreatedCharacter.class);
      when(verified.roster()).thenReturn(roster);
      when(verified.response()).thenReturn(response);
      when(verified.selectedCharacterUuid()).thenReturn(actorUuid);
      when(verified.creationOperationUuid())
          .thenReturn(UUID.fromString(response.getCreationOperationUuid()));
      when(verified.mutationDigest()).thenReturn(response.getMutationDigest());
      when(verified.terminal()).thenReturn(response.getTerminal());
      references.put(actorUuid, verified);
    }
    var routes = mock(CanonicalPlayerRouteReadService.class);
    var publications = mock(CanonicalPublishedPlayerRouteReadService.class);
    var preseeded = mock(CanonicalGameplayRosterClient.class);
    var sources = mock(GameSessionCharacterCreatePublicSourceService.class);
    var client = mock(CanonicalPlayerCreatedRosterClient.class);
    when(routes.readCurrentTarget(route.canonicalTenantId()))
        .thenAnswer(
            call -> {
              assertNoAmbientSql();
              return route;
            });
    when(sources.resolvePlayerCreatedRosterSource(any()))
        .thenAnswer(
            call -> {
              assertNoAmbientSql();
              return source;
            });
    when(client.listPlayerCreatedRoster(source, context))
        .thenAnswer(
            call -> {
              assertNoAmbientSql();
              return roster;
            });
    when(client.readSelectedPlayerCreatedCharacter(org.mockito.ArgumentMatchers.eq(roster), any()))
        .thenAnswer(
            call -> {
              assertNoAmbientSql();
              PlayerExecutionContext selectedContext = call.getArgument(1);
              return references.get(UUID.fromString(selectedContext.getCharacterId()));
            });
    return new CreatedHarness(
        new CanonicalGameplayRosterSelectionService(
            routes, publications, preseeded, sources, client),
        route,
        context,
        routes,
        publications,
        preseeded,
        sources,
        source,
        client,
        roster,
        references.get(FIRST_ACTOR));
  }

  private static CanonicalPlayableTarget withPolicy(CanonicalPlayableTarget r, String policy) {
    return new CanonicalPlayableTarget(
        r.targetNamespace(),
        r.tenantSlug(),
        r.canonicalTenantId(),
        r.worldSlug(),
        r.worldDisplayName(),
        r.realmId(),
        r.realmSlug(),
        r.realmDisplayName(),
        r.gameSessionTenantId(),
        r.gameInstanceId(),
        r.playableStateNamespaceId(),
        r.playableStateScope(),
        r.canonicalGameInstanceId(),
        r.canonicalVersionId(),
        r.runtimeVersionId(),
        r.catalogRevision(),
        r.pointerVersion(),
        r.admissionPointerSnapshotDigest(),
        r.activeWorldEpoch(),
        r.initialAdmissionRequestId(),
        r.initialAdmissionRequestDigest(),
        r.initialAdmissionOriginKind(),
        r.expectedPriorPointerVersion(),
        r.holdId(),
        r.holdFence(),
        r.holdBindingDigest(),
        r.auditEventId(),
        r.ownerProofDigest(),
        r.ownerProofOutcome(),
        r.positiveDurableAbort(),
        r.ownerProofTerminalAt(),
        policy);
  }

  private static void assertNoAmbientSql() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }

  private record CreatedHarness(
      CanonicalGameplayRosterSelectionService service,
      CanonicalPlayableTarget route,
      PlayerExecutionContext context,
      CanonicalPlayerRouteReadService routes,
      CanonicalPublishedPlayerRouteReadService publications,
      CanonicalGameplayRosterClient preseeded,
      GameSessionCharacterCreatePublicSourceService sources,
      ResolvedPlayerCreatedRosterSource source,
      CanonicalPlayerCreatedRosterClient client,
      VerifiedPlayerCreatedRoster roster,
      VerifiedPlayerCreatedCharacter reference) {}

  private static TestHarness harness(List<CanonicalGameplayRosterClient.RosterActor> actors)
      throws Exception {
    return harness(actors, false);
  }

  private static TestHarness harness(
      List<CanonicalGameplayRosterClient.RosterActor> actors, boolean terminalWriteFails)
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
    CanonicalGameplayRosterClient rosterClient = rosterClient(stub, terminalWriteFails);
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
    return rosterResponse(request, account, request.getExpectedTarget(), actors, SNAPSHOT);
  }

  private static CanonicalGameplayRosterResponse rosterResponse(
      CanonicalGameplayRosterRequest request,
      UUID account,
      CanonicalGameplayRosterTarget target,
      List<CanonicalGameplayRosterClient.RosterActor> actors)
      throws Exception {
    return rosterResponse(request, account, target, actors, SNAPSHOT);
  }

  private static CanonicalGameplayRosterResponse rosterResponse(
      CanonicalGameplayRosterRequest request,
      UUID account,
      CanonicalGameplayRosterTarget target,
      List<CanonicalGameplayRosterClient.RosterActor> actors,
      UUID snapshotUuid)
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
            .setSnapshotUuid(snapshotUuid.toString())
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
      CanonicalGameplayRosterServiceGrpc.CanonicalGameplayRosterServiceBlockingStub stub,
      boolean terminalWriteFails)
      throws Exception {
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain("game-session-client.crt");
    tls.setPrivateKey("game-session-client.key");
    tls.setCaCert("entity-management-ca.crt");
    var owner =
        mock(
            net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner.class);
    if (terminalWriteFails) {
      doThrow(new IllegalStateException("controlled missing terminal receipt"))
          .when(owner)
          .completeSelection(any(), any());
    }
    java.util.Map<String, CanonicalGameplayRosterResponse> receipts = new java.util.HashMap<>();
    java.util.Map<
            String, net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput>
        selectionInputs = new java.util.HashMap<>();
    doAnswer(
            call -> {
              net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest
                  request = call.getArgument(0);
              selectionInputs.put(
                  request.getOperationUuid(),
                  net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence
                      .requireSelectionInput(call.getArgument(1)));
              return null;
            })
        .when(owner)
        .reserveSelectionInput(any(), any());
    when(owner.begin(any()))
        .thenAnswer(
            call ->
                net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence
                    .response(
                        call.getArgument(0),
                        1L,
                        java.util.UUID.fromString("12121212-1212-4212-8212-121212121212"),
                        null));
    when(owner.record(any(), any()))
        .thenAnswer(
            call -> {
              net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest
                  request = call.getArgument(0);
              CanonicalGameplayRosterResponse receipt = call.getArgument(1);
              receipts.put(request.getOperationUuid(), receipt);
              return net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence
                  .response(
                      request,
                      1L,
                      java.util.UUID.fromString("12121212-1212-4212-8212-121212121212"),
                      receipt);
            });
    when(owner.read(any()))
        .thenAnswer(
            call -> {
              net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest
                  request = call.getArgument(0);
              return net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence
                  .response(
                      request,
                      1L,
                      java.util.UUID.fromString("12121212-1212-4212-8212-121212121212"),
                      receipts.get(request.getOperationUuid()));
            });
    when(owner.selectOriginal(any(), any()))
        .thenAnswer(
            call -> {
              net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest
                  request = call.getArgument(0);
              UUID selectedCharacter = call.getArgument(1);
              var original = owner.read(request);
              return net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence
                  .requireResponse(
                      request,
                      original.toBuilder()
                          .setSelectionInput(selectionInputs.get(request.getOperationUuid()))
                          .setSelectedCharacterUuid(selectedCharacter.toString())
                          .build());
            });
    var ownerBeans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    ownerBeans.addBean("controlledReferenceOwner", owner);
    var client =
        new CanonicalGameplayRosterClient(
            new ServiceEndpointsProperties(),
            tls,
            mock(GrpcChannelFactory.class),
            "test",
            ownerBeans.getBeanProvider(
                net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner
                    .class));
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
