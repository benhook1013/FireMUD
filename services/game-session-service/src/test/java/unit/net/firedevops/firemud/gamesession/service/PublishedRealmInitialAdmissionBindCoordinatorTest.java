package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt.Status;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogEntry;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogEntry.NamespaceResolution;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindHoldBinding;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerProof;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerProof.Outcome;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerService;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindRequest;
import net.firedevops.firemud.gamesession.service.PublishedRealmCatalogOwnerService;
import net.firedevops.firemud.gamesession.service.PublishedRealmInitialAdmissionBindCommand;
import net.firedevops.firemud.gamesession.service.PublishedRealmInitialAdmissionBindCoordinator;
import net.firedevops.firemud.gamesession.service.RunOwnedInitialLaunchResult;
import net.firedevops.firemud.worldmanagement.v1.AcquireInitialAdmissionBindHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleResponse;
import net.firedevops.firemud.worldmanagement.v1.InitialAdmissionBindHold;
import net.firedevops.firemud.worldmanagement.v1.InitialAdmissionBindHoldStatus;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class PublishedRealmInitialAdmissionBindCoordinatorTest {
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID NAMESPACE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final long LOCAL_GAME_SESSION_TENANT_ID = 41L;
  private static final long SOURCE_GAME_ROW_ID = 731L;
  private static final long PUBLISHED_VERSION_ID = 902L;
  private static final long GAME_TEMPLATE_ID = 12L;
  private static final long OWNER_ACCOUNT_ID = 88L;
  private static final long GAME_INSTANCE_ID = 73L;
  private static final long ACTIVE_LIFECYCLE_EPOCH = 4L;
  private static final long CATALOG_REVISION = 5L;
  private static final String TARGET_NAMESPACE = "gameplay-test";
  private static final String INITIAL_ADMISSION_REQUEST_ID = "66666666-6666-4666-8666-666666666666";
  private static final UUID HOLD_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final UUID HOLD_FENCE = UUID.fromString("88888888-8888-4888-8888-888888888888");
  private static final UUID ATTEMPT_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");
  private static final UUID WRONG_ATTEMPT_ID =
      UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID WRONG_REALM_ID =
      UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final String LAUNCH_DESCRIPTOR_ID = "launch-descriptor-1";
  private static final String PUBLISHED_RELEASE_BUNDLE_REF = "release-bundle:903";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void distinctSourceAndLocalTenantIdsFailBeforeAuthoredLaunchOrWorldHold() {
    PublishedRealmCatalogOwnerService catalogOwner = mock(PublishedRealmCatalogOwnerService.class);
    GameInstanceService gameInstanceService = mock(GameInstanceService.class);
    InitialAdmissionBindOwnerService ownerService = mock(InitialAdmissionBindOwnerService.class);
    WorldManagementClient worldManagementClient = mock(WorldManagementClient.class);
    when(catalogOwner.materializePublishedSnapshot(CANONICAL_TENANT_ID, PUBLISHED_VERSION_ID))
        .thenReturn(snapshotWithDistinctSourceAndLocalIds());
    PublishedRealmInitialAdmissionBindCoordinator coordinator =
        new PublishedRealmInitialAdmissionBindCoordinator(
            catalogOwner, gameInstanceService, ownerService, worldManagementClient);

    assertThatThrownBy(
            () ->
                coordinator.coordinate(
                    new PublishedRealmInitialAdmissionBindCommand(
                        CANONICAL_TENANT_ID,
                        PUBLISHED_VERSION_ID,
                        12L,
                        88L,
                        "earth",
                        "main",
                        "44444444-4444-4444-8444-444444444444")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("INITIAL_ADMISSION_LAUNCH_TENANT_IDENTITY_UNAVAILABLE")
        .hasMessageContaining("source/local identity adapter is required");

    verifyNoInteractions(gameInstanceService, ownerService, worldManagementClient);
  }

  @Test
  void matchingRetryableHoldStatusesAttachThenCommitTheExactOwnerBinding() {
    for (InitialAdmissionBindHoldStatus status :
        List.of(
            InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING,
            InitialAdmissionBindHoldStatus
                .INITIAL_ADMISSION_BIND_HOLD_STATUS_RECONCILIATION_REQUIRED)) {
      Fixture fixture = fixture(status);

      var result = fixture.coordinator().coordinate(fixture.command());

      InitialAdmissionBindRequest request = verifyLaunchAndIntent(fixture);
      InitialAdmissionBindHoldBinding expectedBinding = expectedBinding(request.requestDigest());
      assertThat(result)
          .isEqualTo(
              new PublishedRealmInitialAdmissionBindCoordinator.Result(
                  PublishedRealmInitialAdmissionBindCoordinator.TerminalOutcome.COMMITTED,
                  terminalProof(expectedBinding, Outcome.COMMITTED)));
      verify(fixture.ownerService()).attachHold(expectedBinding);
      verify(fixture.ownerService()).commit(expectedBinding);
      verify(fixture.ownerService(), never()).read(any());
      verify(fixture.ownerService(), never()).abort(any());
      verifyNoMoreInteractions(fixture.ownerService());
      verifyNoUnexpectedHoldInputs(fixture, request);
    }
  }

  @Test
  void matchingCommittedAndAbortedHoldsAcceptOnlyTheirExactTerminalOwnerProofs() {
    for (var terminal :
        List.of(
            new TerminalCase(
                InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_COMMITTED,
                Outcome.COMMITTED,
                PublishedRealmInitialAdmissionBindCoordinator.TerminalOutcome.COMMITTED),
            new TerminalCase(
                InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_ABORTED,
                Outcome.ABORTED,
                PublishedRealmInitialAdmissionBindCoordinator.TerminalOutcome.ABORTED))) {
      Fixture fixture = fixture(terminal.holdStatus());
      when(fixture.ownerService().read(any()))
          .thenAnswer(
              invocation ->
                  terminalProof(
                      invocation.getArgument(0),
                      terminal.ownerOutcome(),
                      ATTEMPT_ID.toString(),
                      terminal.ownerOutcome() == Outcome.COMMITTED ? "93" : null,
                      terminal.ownerOutcome() == Outcome.COMMITTED ? 1L : 0L,
                      terminal.ownerOutcome() == Outcome.COMMITTED
                          ? invocation
                              .getArgument(0, InitialAdmissionBindHoldBinding.class)
                              .requestDigest()
                          : null,
                      terminal.ownerOutcome() == Outcome.ABORTED));

      var result = fixture.coordinator().coordinate(fixture.command());

      InitialAdmissionBindRequest request = verifyLaunchAndIntent(fixture);
      InitialAdmissionBindHoldBinding expectedBinding = expectedBinding(request.requestDigest());
      assertThat(result)
          .isEqualTo(
              new PublishedRealmInitialAdmissionBindCoordinator.Result(
                  terminal.resultOutcome(),
                  terminalProof(expectedBinding, terminal.ownerOutcome())));
      verify(fixture.ownerService()).read(expectedBinding);
      verify(fixture.ownerService(), never()).attachHold(any());
      verify(fixture.ownerService(), never()).commit(any());
      verify(fixture.ownerService(), never()).abort(any());
      verifyNoMoreInteractions(fixture.ownerService());
      verifyNoUnexpectedHoldInputs(fixture, request);
    }
  }

  @Test
  void committedAndAbortedHoldProofsWithInvalidTerminalEvidenceAreRejectedWithoutCommit() {
    for (var rejected :
        List.of(
            new TerminalCase(
                InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_COMMITTED,
                Outcome.COMMITTED,
                PublishedRealmInitialAdmissionBindCoordinator.TerminalOutcome.COMMITTED),
            new TerminalCase(
                InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_ABORTED,
                Outcome.ABORTED,
                PublishedRealmInitialAdmissionBindCoordinator.TerminalOutcome.ABORTED))) {
      Fixture fixture = fixture(rejected.holdStatus());
      when(fixture.ownerService().read(any()))
          .thenAnswer(
              invocation ->
                  terminalProof(
                      invocation.getArgument(0),
                      rejected.ownerOutcome(),
                      rejected.ownerOutcome() == Outcome.COMMITTED
                          ? WRONG_ATTEMPT_ID.toString()
                          : ATTEMPT_ID.toString(),
                      rejected.ownerOutcome() == Outcome.COMMITTED ? "93" : null,
                      rejected.ownerOutcome() == Outcome.COMMITTED ? 1L : 0L,
                      rejected.ownerOutcome() == Outcome.COMMITTED
                          ? invocation
                              .getArgument(0, InitialAdmissionBindHoldBinding.class)
                              .requestDigest()
                          : null,
                      false));

      assertThatThrownBy(() -> fixture.coordinator().coordinate(fixture.command()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("INITIAL_ADMISSION_OWNER_PROOF_UNRESOLVED");

      InitialAdmissionBindRequest request = verifyLaunchAndIntent(fixture);
      verify(fixture.ownerService()).read(expectedBinding(request.requestDigest()));
      verify(fixture.ownerService(), never()).attachHold(any());
      verify(fixture.ownerService(), never()).commit(any());
      verify(fixture.ownerService(), never()).abort(any());
      verifyNoMoreInteractions(fixture.ownerService());
      verifyNoUnexpectedHoldInputs(fixture, request);
    }
  }

  @Test
  void mismatchedHoldTupleOrLifecycleEpochCannotReachOwnerAttachmentOrCommit() {
    for (boolean mismatchLifecycleEpoch : List.of(false, true)) {
      Fixture fixture =
          fixture(InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING);
      when(fixture
              .worldManagementClient()
              .acquireInitialAdmissionBindHold(
                  anyLong(),
                  anyLong(),
                  anyLong(),
                  anyLong(),
                  anyString(),
                  anyString(),
                  any(UUID.class),
                  any(UUID.class),
                  any(PlayableStateScope.class),
                  anyLong()))
          .thenAnswer(
              invocation -> {
                String requestDigest = invocation.getArgument(5);
                InitialAdmissionBindHold.Builder hold =
                    exactHold(
                        InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING,
                        requestDigest);
                if (mismatchLifecycleEpoch) {
                  hold.setActiveLifecycleEpoch(ACTIVE_LIFECYCLE_EPOCH + 1L);
                } else {
                  hold.setRealmUuid(WRONG_REALM_ID.toString());
                }
                return AcquireInitialAdmissionBindHoldResponse.newBuilder().setHold(hold).build();
              });

      assertThatThrownBy(() -> fixture.coordinator().coordinate(fixture.command()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("INITIAL_ADMISSION_WORLD_HOLD_MISMATCH");

      InitialAdmissionBindRequest request = verifyLaunchAndIntent(fixture);
      verifyNoOwnerMutationAfterIntent(fixture);
      verifyNoUnexpectedHoldInputs(fixture, request);
    }
  }

  @Test
  void launchAndWorldLifecycleEpochMismatchStopsBeforeIntentOrHoldMutation() {
    Fixture fixture =
        fixture(InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING);
    when(fixture
            .worldManagementClient()
            .getWorldInstanceLifecycle(LOCAL_GAME_SESSION_TENANT_ID, GAME_INSTANCE_ID))
        .thenReturn(activeLifecycle(ACTIVE_LIFECYCLE_EPOCH + 1L));

    assertThatThrownBy(() -> fixture.coordinator().coordinate(fixture.command()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("INITIAL_ADMISSION_WORLD_LIFECYCLE_MISMATCH");

    verify(fixture.catalogOwner())
        .materializePublishedSnapshot(CANONICAL_TENANT_ID, PUBLISHED_VERSION_ID);
    verify(fixture.gameInstanceService())
        .startRunOwnedInitialLaunch(
            new StartSessionRequest(
                LOCAL_GAME_SESSION_TENANT_ID,
                GAME_TEMPLATE_ID,
                INITIAL_ADMISSION_REQUEST_ID,
                OWNER_ACCOUNT_ID));
    verify(fixture.worldManagementClient())
        .getWorldInstanceLifecycle(LOCAL_GAME_SESSION_TENANT_ID, GAME_INSTANCE_ID);
    verify(fixture.worldManagementClient(), never())
        .acquireInitialAdmissionBindHold(
            anyLong(),
            anyLong(),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            any(UUID.class),
            any(UUID.class),
            any(PlayableStateScope.class),
            anyLong());
    verifyNoMoreInteractions(
        fixture.catalogOwner(), fixture.gameInstanceService(), fixture.worldManagementClient());
    verifyNoInteractions(fixture.ownerService());
  }

  @Test
  void unspecifiedWorldHoldStatusFailsClosedBeforeOwnerAttachmentOrCommit() {
    Fixture fixture =
        fixture(InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_UNSPECIFIED);

    assertThatThrownBy(() -> fixture.coordinator().coordinate(fixture.command()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("INITIAL_ADMISSION_WORLD_HOLD_MISMATCH");

    InitialAdmissionBindRequest request = verifyLaunchAndIntent(fixture);
    verifyNoOwnerMutationAfterIntent(fixture);
    verifyNoUnexpectedHoldInputs(fixture, request);
  }

  private static Fixture fixture(InitialAdmissionBindHoldStatus holdStatus) {
    PublishedRealmCatalogOwnerService catalogOwner = mock(PublishedRealmCatalogOwnerService.class);
    GameInstanceService gameInstanceService = mock(GameInstanceService.class);
    InitialAdmissionBindOwnerService ownerService = mock(InitialAdmissionBindOwnerService.class);
    WorldManagementClient worldManagementClient = mock(WorldManagementClient.class);
    AtomicReference<InitialAdmissionBindRequest> request = new AtomicReference<>();
    PublishedRealmInitialAdmissionBindCommand command = command();

    when(catalogOwner.materializePublishedSnapshot(CANONICAL_TENANT_ID, PUBLISHED_VERSION_ID))
        .thenReturn(matchingSnapshot());
    when(gameInstanceService.startRunOwnedInitialLaunch(any()))
        .thenReturn(new RunOwnedInitialLaunchResult(activeGameInstance(), ACTIVE_LIFECYCLE_EPOCH));
    when(worldManagementClient.getWorldInstanceLifecycle(
            LOCAL_GAME_SESSION_TENANT_ID, GAME_INSTANCE_ID))
        .thenReturn(activeLifecycle(ACTIVE_LIFECYCLE_EPOCH));
    when(ownerService.beginIntent(any()))
        .thenAnswer(
            invocation -> {
              InitialAdmissionBindRequest received = invocation.getArgument(0);
              request.set(received);
              return attempt(received, null, null, Status.PENDING);
            });
    when(worldManagementClient.acquireInitialAdmissionBindHold(
            anyLong(),
            anyLong(),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            any(UUID.class),
            any(UUID.class),
            any(PlayableStateScope.class),
            anyLong()))
        .thenAnswer(
            invocation -> {
              InitialAdmissionBindHold hold =
                  exactHold(holdStatus, invocation.getArgument(5)).build();
              return AcquireInitialAdmissionBindHoldResponse.newBuilder().setHold(hold).build();
            });
    when(ownerService.attachHold(any()))
        .thenAnswer(
            invocation -> {
              InitialAdmissionBindHoldBinding binding = invocation.getArgument(0);
              return attempt(
                  request.get(),
                  UUID.fromString(binding.holdId()),
                  UUID.fromString(binding.holdFence()),
                  Status.PENDING);
            });
    when(ownerService.commit(any()))
        .thenAnswer(invocation -> terminalProof(invocation.getArgument(0), Outcome.COMMITTED));

    return new Fixture(
        new PublishedRealmInitialAdmissionBindCoordinator(
            catalogOwner, gameInstanceService, ownerService, worldManagementClient),
        command,
        catalogOwner,
        gameInstanceService,
        ownerService,
        worldManagementClient);
  }

  private static PublishedRealmInitialAdmissionBindCommand command() {
    return new PublishedRealmInitialAdmissionBindCommand(
        CANONICAL_TENANT_ID,
        PUBLISHED_VERSION_ID,
        GAME_TEMPLATE_ID,
        OWNER_ACCOUNT_ID,
        "earth",
        "main",
        INITIAL_ADMISSION_REQUEST_ID);
  }

  private static PublishedRealmCatalogSnapshot matchingSnapshot() {
    String workflow = "publish:published-bind-matching-unit-test";
    String manifest = "published-bind-matching-manifest";
    RealmEntryPolicy policy =
        RealmEntryPolicy.parse(
            "{\"schemaVersion\":1,\"worldSlug\":\"earth\",\"worldDisplayName\":\"Earth\","
                + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main\",\"visible\":true,"
                + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
                + "\"entryPolicy\":\"PRESEEDED_ONLY\"}",
            JSON);
    String releaseIdentity =
        PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
            CANONICAL_TENANT_ID, PUBLISHED_VERSION_ID, workflow, manifest, JSON);
    PublishedRealmEntryPolicyEvidence policyEvidence =
        PublishedRealmEntryPolicyEvidence.create(
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            CANONICAL_TENANT_ID,
            "RETAINED_GAME_V29",
            LOCAL_GAME_SESSION_TENANT_ID,
            "gd-source-41",
            PUBLISHED_VERSION_ID,
            3,
            903L,
            releaseIdentity,
            workflow,
            manifest,
            policy,
            JSON);
    PublishedRealmEntryPolicySetEvidence policySet =
        PublishedRealmEntryPolicySetEvidence.create(
            CANONICAL_TENANT_ID,
            PUBLISHED_VERSION_ID,
            3,
            releaseIdentity,
            workflow,
            manifest,
            List.of(policyEvidence),
            JSON);
    return new PublishedRealmCatalogSnapshot(
        LOCAL_GAME_SESSION_TENANT_ID,
        TARGET_NAMESPACE,
        CANONICAL_TENANT_ID,
        LOCAL_GAME_SESSION_TENANT_ID,
        "gd-source-41",
        "RETAINED_GAME_V29",
        CATALOG_REVISION,
        policySet,
        List.of(
            new PublishedRealmCatalogEntry(
                LOCAL_GAME_SESSION_TENANT_ID,
                CATALOG_REVISION,
                REALM_ID,
                NAMESPACE_ID,
                NamespaceResolution.RESOLVED,
                policyEvidence)),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static GameInstanceDto activeGameInstance() {
    return new GameInstanceDto(
        GAME_INSTANCE_ID,
        LOCAL_GAME_SESSION_TENANT_ID,
        "v1",
        null,
        null,
        null,
        null,
        GAME_TEMPLATE_ID,
        LAUNCH_DESCRIPTOR_ID,
        PUBLISHED_VERSION_ID,
        903L,
        5L,
        "config-1",
        null,
        OWNER_ACCOUNT_ID,
        "RUNNING");
  }

  private static GetWorldInstanceLifecycleResponse activeLifecycle(long lifecycleEpoch) {
    return GetWorldInstanceLifecycleResponse.newBuilder()
        .setWorldInstance(
            WorldInstanceLifecycleSnapshot.newBuilder()
                .setTenantId(Long.toString(LOCAL_GAME_SESSION_TENANT_ID))
                .setGameInstanceId(Long.toString(GAME_INSTANCE_ID))
                .setGameTemplateId(Long.toString(GAME_TEMPLATE_ID))
                .setControlPlaneRequestId(INITIAL_ADMISSION_REQUEST_ID)
                .setLaunchDescriptorId(LAUNCH_DESCRIPTOR_ID)
                .setVersionId(Long.toString(PUBLISHED_VERSION_ID))
                .setReleaseBundleId("903")
                .setPublishedReleaseBundleRef(PUBLISHED_RELEASE_BUNDLE_REF)
                .setVersionStateEpoch(5L)
                .setLifecycleEpoch(lifecycleEpoch)
                .setStatus(WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE)
                .build())
        .build();
  }

  private static InitialAdmissionBindHold.Builder exactHold(
      InitialAdmissionBindHoldStatus status, String requestDigest) {
    return InitialAdmissionBindHold.newBuilder()
        .setHoldId(HOLD_ID.toString())
        .setHoldFence(HOLD_FENCE.toString())
        .setTenantId(Long.toString(LOCAL_GAME_SESSION_TENANT_ID))
        .setRealmUuid(REALM_ID.toString())
        .setPlayableStateNamespaceUuid(NAMESPACE_ID.toString())
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .setGameInstanceId(Long.toString(GAME_INSTANCE_ID))
        .setVersionId(Long.toString(PUBLISHED_VERSION_ID))
        .setActiveLifecycleEpoch(ACTIVE_LIFECYCLE_EPOCH)
        .setInitialAdmissionRequestId(INITIAL_ADMISSION_REQUEST_ID)
        .setRequestDigest(requestDigest)
        .setExpectedNoPriorPointer(true)
        .setExpectedCatalogRevision(CATALOG_REVISION)
        .setStatus(status);
  }

  private static InitialAdmissionBindAttempt attempt(
      InitialAdmissionBindRequest request, UUID holdId, UUID holdFence, Status status) {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    return new InitialAdmissionBindAttempt(
        ATTEMPT_ID,
        request.tenantId(),
        request.initialAdmissionRequestId(),
        request.requestDigest(),
        REALM_ID,
        NAMESPACE_ID,
        "SHARED",
        true,
        CATALOG_REVISION,
        GAME_INSTANCE_ID,
        PUBLISHED_VERSION_ID,
        ACTIVE_LIFECYCLE_EPOCH,
        holdId,
        holdFence,
        status,
        status == Status.COMMITTED ? 1L : null,
        status == Status.COMMITTED ? 93L : null,
        now,
        now,
        status == Status.PENDING ? null : now,
        "V14_PUBLISHED",
        TARGET_NAMESPACE,
        CANONICAL_TENANT_ID,
        GAME_TEMPLATE_ID,
        LAUNCH_DESCRIPTOR_ID,
        903L,
        PUBLISHED_RELEASE_BUNDLE_REF,
        5L);
  }

  private static InitialAdmissionBindOwnerProof terminalProof(
      InitialAdmissionBindHoldBinding binding, Outcome outcome) {
    return terminalProof(
        binding,
        outcome,
        ATTEMPT_ID.toString(),
        outcome == Outcome.COMMITTED ? "93" : null,
        outcome == Outcome.COMMITTED ? 1L : 0L,
        outcome == Outcome.COMMITTED ? binding.requestDigest() : null,
        outcome == Outcome.ABORTED);
  }

  private static InitialAdmissionBindOwnerProof terminalProof(
      InitialAdmissionBindHoldBinding binding,
      Outcome outcome,
      String ownerProofId,
      String pointerAuditId,
      long pointerVersion,
      String pointerAuditRequestDigest,
      boolean futureCommitPrevented) {
    return new InitialAdmissionBindOwnerProof(
        outcome,
        binding.holdId(),
        binding.holdFence(),
        binding.tenantId(),
        binding.realmUuid(),
        binding.playableStateNamespaceUuid(),
        binding.playableStateScope(),
        binding.gameInstanceId(),
        binding.versionId(),
        binding.activeLifecycleEpoch(),
        binding.initialAdmissionRequestId(),
        binding.requestDigest(),
        binding.expectedNoPriorPointer(),
        binding.expectedCatalogRevision(),
        ownerProofId,
        pointerAuditId,
        pointerVersion,
        pointerAuditRequestDigest,
        futureCommitPrevented);
  }

  private static InitialAdmissionBindHoldBinding expectedBinding(String requestDigest) {
    return new InitialAdmissionBindHoldBinding(
        HOLD_ID.toString(),
        HOLD_FENCE.toString(),
        LOCAL_GAME_SESSION_TENANT_ID,
        REALM_ID.toString(),
        NAMESPACE_ID.toString(),
        "SHARED",
        GAME_INSTANCE_ID,
        PUBLISHED_VERSION_ID,
        ACTIVE_LIFECYCLE_EPOCH,
        INITIAL_ADMISSION_REQUEST_ID,
        requestDigest,
        true,
        CATALOG_REVISION);
  }

  private static InitialAdmissionBindRequest verifyLaunchAndIntent(Fixture fixture) {
    verify(fixture.catalogOwner())
        .materializePublishedSnapshot(CANONICAL_TENANT_ID, PUBLISHED_VERSION_ID);
    verify(fixture.gameInstanceService())
        .startRunOwnedInitialLaunch(
            new StartSessionRequest(
                LOCAL_GAME_SESSION_TENANT_ID,
                GAME_TEMPLATE_ID,
                INITIAL_ADMISSION_REQUEST_ID,
                OWNER_ACCOUNT_ID));
    verify(fixture.worldManagementClient())
        .getWorldInstanceLifecycle(LOCAL_GAME_SESSION_TENANT_ID, GAME_INSTANCE_ID);
    ArgumentCaptor<InitialAdmissionBindRequest> requestCaptor =
        ArgumentCaptor.forClass(InitialAdmissionBindRequest.class);
    verify(fixture.ownerService()).beginIntent(requestCaptor.capture());
    InitialAdmissionBindRequest request = requestCaptor.getValue();
    assertThat(request.tenantId()).isEqualTo(LOCAL_GAME_SESSION_TENANT_ID);
    assertThat(request.worldSlug()).isEqualTo("earth");
    assertThat(request.realmSlug()).isEqualTo("main");
    assertThat(request.initialAdmissionRequestId()).isEqualTo(INITIAL_ADMISSION_REQUEST_ID);
    assertThat(request.gameInstanceId()).isEqualTo(GAME_INSTANCE_ID);
    assertThat(request.versionId()).isEqualTo(PUBLISHED_VERSION_ID);
    assertThat(request.activeLifecycleEpoch()).isEqualTo(ACTIVE_LIFECYCLE_EPOCH);
    assertThat(request.publishedCatalog())
        .isEqualTo(
            new InitialAdmissionBindRequest.PublishedCatalogBinding(
                TARGET_NAMESPACE, CANONICAL_TENANT_ID, CATALOG_REVISION));
    assertThat(request.launchEvidence())
        .isEqualTo(
            new InitialAdmissionBindRequest.LaunchEvidence(
                GAME_TEMPLATE_ID, LAUNCH_DESCRIPTOR_ID, 903L, PUBLISHED_RELEASE_BUNDLE_REF, 5L));
    verifyNoMoreInteractions(fixture.catalogOwner());
    return request;
  }

  private static void verifyNoUnexpectedHoldInputs(
      Fixture fixture, InitialAdmissionBindRequest request) {
    verify(fixture.worldManagementClient())
        .acquireInitialAdmissionBindHold(
            eq(LOCAL_GAME_SESSION_TENANT_ID),
            eq(GAME_INSTANCE_ID),
            eq(PUBLISHED_VERSION_ID),
            eq(ACTIVE_LIFECYCLE_EPOCH),
            eq(INITIAL_ADMISSION_REQUEST_ID),
            eq(request.requestDigest()),
            eq(REALM_ID),
            eq(NAMESPACE_ID),
            eq(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED),
            eq(CATALOG_REVISION));
    verifyNoMoreInteractions(fixture.gameInstanceService(), fixture.worldManagementClient());
  }

  private static void verifyNoOwnerMutationAfterIntent(Fixture fixture) {
    verify(fixture.ownerService(), never()).attachHold(any());
    verify(fixture.ownerService(), never()).read(any());
    verify(fixture.ownerService(), never()).commit(any());
    verify(fixture.ownerService(), never()).abort(any());
    verifyNoMoreInteractions(fixture.ownerService());
  }

  private record Fixture(
      PublishedRealmInitialAdmissionBindCoordinator coordinator,
      PublishedRealmInitialAdmissionBindCommand command,
      PublishedRealmCatalogOwnerService catalogOwner,
      GameInstanceService gameInstanceService,
      InitialAdmissionBindOwnerService ownerService,
      WorldManagementClient worldManagementClient) {}

  private record TerminalCase(
      InitialAdmissionBindHoldStatus holdStatus,
      Outcome ownerOutcome,
      PublishedRealmInitialAdmissionBindCoordinator.TerminalOutcome resultOutcome) {}

  private static PublishedRealmCatalogSnapshot snapshotWithDistinctSourceAndLocalIds() {
    String workflow = "publish:published-bind-unit-test";
    String manifest = "published-bind-manifest";
    long versionId = PUBLISHED_VERSION_ID;
    int versionNumber = 3;
    RealmEntryPolicy policy =
        RealmEntryPolicy.parse(
            "{\"schemaVersion\":1,\"worldSlug\":\"earth\",\"worldDisplayName\":\"Earth\","
                + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main\",\"visible\":true,"
                + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
                + "\"entryPolicy\":\"PRESEEDED_ONLY\"}",
            JSON);
    String releaseIdentity =
        PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
            CANONICAL_TENANT_ID, versionId, workflow, manifest, JSON);
    PublishedRealmEntryPolicyEvidence policyEvidence =
        PublishedRealmEntryPolicyEvidence.create(
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            CANONICAL_TENANT_ID,
            "RETAINED_GAME_V29",
            SOURCE_GAME_ROW_ID,
            "gd-source-731",
            versionId,
            versionNumber,
            903L,
            releaseIdentity,
            workflow,
            manifest,
            policy,
            JSON);
    PublishedRealmEntryPolicySetEvidence policySet =
        PublishedRealmEntryPolicySetEvidence.create(
            CANONICAL_TENANT_ID,
            versionId,
            versionNumber,
            releaseIdentity,
            workflow,
            manifest,
            List.of(policyEvidence),
            JSON);
    return new PublishedRealmCatalogSnapshot(
        LOCAL_GAME_SESSION_TENANT_ID,
        "gameplay-test",
        CANONICAL_TENANT_ID,
        SOURCE_GAME_ROW_ID,
        "gd-source-731",
        "RETAINED_GAME_V29",
        5L,
        policySet,
        List.of(
            new PublishedRealmCatalogEntry(
                LOCAL_GAME_SESSION_TENANT_ID,
                5L,
                REALM_ID,
                NAMESPACE_ID,
                NamespaceResolution.RESOLVED,
                policyEvidence)),
        Instant.parse("2026-01-01T00:00:00Z"));
  }
}
