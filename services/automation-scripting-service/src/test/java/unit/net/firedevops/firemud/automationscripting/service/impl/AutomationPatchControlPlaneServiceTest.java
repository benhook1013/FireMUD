package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.firedevops.firemud.automationscripting.client.GameDesignControlPlaneClient;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptRuntimeProperties;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchPinProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.AutomationAdmissionMode;
import net.firedevops.firemud.automationscripting.v1.ListScriptDeadLettersRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptHandoffEventsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptScheduleInstancesRequest;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedScriptPatchVersion;
import net.firedevops.firemud.gamesession.v1.AdmissionPointerControlPlaneEntry;
import net.firedevops.firemud.gamesession.v1.GameplayCommandStatus;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.gamesession.v1.GetGameplayCommandStatusResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AutomationPatchControlPlaneServiceTest {
  private static GameDesignControlPlaneClient gameDesignClient() {
    GameDesignControlPlaneClient client = Mockito.mock(GameDesignControlPlaneClient.class);
    Mockito.when(client.getPublishedScriptPatchVersion(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            GetPublishedScriptPatchVersionResponse.newBuilder()
                .setScriptPatch(
                    PublishedScriptPatchVersion.newBuilder()
                        .setScriptPatchVersion("patch-2")
                        .setVersionId(17L)
                        .setBaseVersionId(7L)
                        .setPublicationState(
                            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                                .VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setLastChangedAtMs(150L)
                        .build())
                .build());
    return client;
  }

  private static AutomationPatchControlPlaneService newService(
      ScriptWorkItemService workItemService,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchPinProjectionService scriptPatchPinProjectionService,
      ScriptScheduleInstanceService scriptScheduleInstanceService,
      ScriptRuntimeProperties runtimeProperties,
      GameSessionControlPlaneClient gameSessionControlPlaneClient) {
    return new AutomationPatchControlPlaneService(
        workItemService,
        automationAdmissionStateService,
        scriptPatchPinProjectionService,
        scriptScheduleInstanceService,
        gameDesignClient(),
        gameSessionControlPlaneClient,
        runtimeProperties,
        new TemporalScriptPatchReadinessWorkflowMetadataResolver(
            java.util.Optional.empty(), java.util.Optional.empty()));
  }

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  private static AdmissionPointerControlPlaneEntry currentPointer(
      String worldSlug, String realmSlug, long pointerVersion) {
    return AdmissionPointerControlPlaneEntry.newBuilder()
        .setWorldSlug(worldSlug)
        .setRealmSlug(realmSlug)
        .setTenantId("1")
        .setGameInstanceId("game-1")
        .setPointerVersion(pointerVersion)
        .setStateScope("SHARED")
        .build();
  }

  @Test
  void listsScriptScheduleInstancesWithCurrentRuntimeScope() {
    ScriptScheduleInstanceService scheduleInstanceService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    Mockito.when(scheduleInstanceService.listInstances("1", "game-1", "patch-1", 25))
        .thenReturn(
            List.of(
                new ScriptScheduleInstanceService.ScheduleInstanceSummary(
                    "1",
                    "game-1",
                    "patch-1",
                    9L,
                    "npc-guard",
                    "SHARED",
                    "demo",
                    "production",
                    "17",
                    "",
                    "",
                    "onTimerExpire",
                    "guard.alert.expire.v1",
                    "TIMER",
                    5000L,
                    "MILLISECONDS",
                    "normal",
                    "ENTITY",
                    "guard-1",
                    10,
                    false,
                    "READY",
                    5555L,
                    0L,
                    "runtime-v2",
                    "req-9",
                    System.currentTimeMillis(),
                    1235L,
                    1236L,
                    "region-1",
                    12L,
                    100L,
                    System.currentTimeMillis(),
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "patch-1",
                        17L,
                        9L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        140L,
                        "",
                        ""),
                    null)));
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    Mockito.when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setRegionId("region-1")
                        .setRegionEpoch(12L)
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_SHARED)
                        .addCurrentAdmissionPointers(currentPointer("demo", "production", 17L))
                        .setWorldSlug("demo")
                        .setRealmSlug("production")
                        .setPointerVersion(17L)
                        .build())
                .build());
    var service =
        newService(
            Mockito.mock(ScriptWorkItemService.class),
            Mockito.mock(AutomationAdmissionStateService.class),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            scheduleInstanceService,
            new ScriptRuntimeProperties(),
            gameSessionClient);

    var response =
        service.listScriptScheduleInstances(
            ListScriptScheduleInstancesRequest.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setScriptPatchVersion("patch-1")
                .setLimit(25)
                .build());

    assertThat(response.hasError()).isFalse();
    assertThat(response.getSchedulesCount()).isEqualTo(1);
    assertThat(response.getSchedules(0).getCurrentRuntimeRegionId()).isEqualTo("region-1");
    assertThat(response.getSchedules(0).getIsRoutingBundleStale()).isFalse();
    assertThat(response.getSchedules(0).getPublication().getVersionId()).isEqualTo(17L);
  }

  @Test
  void listsScriptHandoffEventsWithTargetRuntimeAndGameplayStatus() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    Mockito.when(
            workItemService.listHandoffEvents(
                "1",
                "game-1",
                "patch-1",
                "99",
                "enqueued",
                "game-2",
                "region-2",
                17L,
                "remote-coordinator:workItem:99#0",
                "remote-followup:workItem:99#0",
                "script-1",
                "plugin-1",
                "workItem:99#0",
                "command-1",
                "target-1",
                "SHARED",
                "demo",
                "production",
                "17",
                "SCHEDULE_TIMER",
                "SCHEDULE_DUE_CLAIMED",
                10L,
                20L,
                50))
        .thenReturn(
            List.of(
                new ScriptWorkItemService.HandoffEventSummary(
                    "event-1",
                    "1",
                    "game-1",
                    "patch-1",
                    "script-1",
                    "plugin-1",
                    "plugin-v1",
                    "99",
                    0,
                    "workItem:99#0",
                    "command-1",
                    "game-2",
                    "region-2",
                    17L,
                    "remote-coordinator:workItem:99#0",
                    "remote-followup:workItem:99#0",
                    "target-1",
                    "SHARED",
                    "demo",
                    "production",
                    "17",
                    "SCHEDULE_TIMER",
                    "SCHEDULE_DUE_CLAIMED",
                    5000L,
                    0L,
                    5000L,
                    "LOOK AT old chest",
                    "enqueued",
                    "game_session_accepted",
                    15L,
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "patch-1",
                        17L,
                        9L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        140L,
                        "",
                        ""),
                    null)));
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    Mockito.when(gameSessionClient.getGameInstanceRuntimeState("1", "game-2", "region-2"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState.newBuilder()
                        .setGameInstanceId("game-2")
                        .setRegionId("region-live")
                        .setRegionEpoch(22L)
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_ISOLATED)
                        .setWorldSlug("demo-next")
                        .setRealmSlug("staging")
                        .setPointerVersion(99L)
                        .build())
                .build());
    Mockito.when(gameSessionClient.getGameplayCommandStatus("1", "game-2", "command-1"))
        .thenReturn(
            GetGameplayCommandStatusResponse.newBuilder()
                .setCommand(
                    GameplayCommandStatus.newBuilder()
                        .setCommandId("command-1")
                        .setExecutionOutcome("APPLIED")
                        .setGameplayResult("SUCCESS")
                        .setRemoteState("REMOTE_APPLIED")
                        .setRemoteTargetCommandExecutionOutcome("APPLIED")
                        .setRemoteTargetCommandGameplayResult("SUCCESS")
                        .build())
                .build());
    var service =
        newService(
            workItemService,
            Mockito.mock(AutomationAdmissionStateService.class),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptScheduleInstanceService.class),
            new ScriptRuntimeProperties(),
            gameSessionClient);

    var response =
        service.listScriptHandoffEvents(
            ListScriptHandoffEventsRequest.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setScriptPatchVersion("patch-1")
                .setWorkItemId("99")
                .setHandoffOutcome("enqueued")
                .setTargetGameInstanceId("game-2")
                .setTargetRegionId("region-2")
                .setTargetRegionEpoch(17L)
                .setRemoteCoordinatorId("remote-coordinator:workItem:99#0")
                .setRemoteFollowupId("remote-followup:workItem:99#0")
                .setScriptId("script-1")
                .setPluginId("plugin-1")
                .setAutomationDispatchId("workItem:99#0")
                .setGameSessionCommandId("command-1")
                .setTargetEntityId("target-1")
                .setPlayableStateScope(
                    net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                        .PLAYABLE_STATE_SCOPE_SHARED)
                .setWorldSlug("demo")
                .setRealmSlug("production")
                .setPointerVersion("17")
                .setSourceKind("SCHEDULE_TIMER")
                .setSourceState("SCHEDULE_DUE_CLAIMED")
                .setChangedAfterMs(10L)
                .setChangedBeforeMs(20L)
                .setLimit(50)
                .build());

    assertThat(response.hasError()).isFalse();
    assertThat(response.getEventsCount()).isEqualTo(1);
    assertThat(response.getEvents(0).getCurrentTargetRuntimeRegionId()).isEqualTo("region-live");
    assertThat(response.getEvents(0).getIsTargetRuntimeScopeStale()).isTrue();
    assertThat(response.getEvents(0).getGameplayRemoteTargetCommandGameplayResult())
        .isEqualTo("SUCCESS");
    Mockito.verify(gameSessionClient).getGameplayCommandStatus("1", "game-2", "command-1");
  }

  @Test
  void omitsGameplayStatusWhenHandoffTargetGameInstanceIsMissing() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    Mockito.when(
            workItemService.listHandoffEvents(
                "1", "", "", "", "", "", "", 0L, "", "", "", "", "", "", "", "", "", "", "", "", "",
                0L, 0L, 0))
        .thenReturn(
            List.of(
                new ScriptWorkItemService.HandoffEventSummary(
                    "event-missing-target",
                    "1",
                    "game-1",
                    "patch-1",
                    "script-1",
                    "plugin-1",
                    "plugin-v1",
                    "99",
                    0,
                    "workItem:99#0",
                    "command-1",
                    "",
                    "",
                    0L,
                    "",
                    "",
                    "target-1",
                    "SHARED",
                    "demo",
                    "production",
                    "17",
                    "SCHEDULE_TIMER",
                    "SCHEDULE_DUE_CLAIMED",
                    5000L,
                    0L,
                    5000L,
                    "LOOK AT old chest",
                    "enqueued",
                    "game_session_accepted",
                    15L,
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "patch-1",
                        17L,
                        9L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        140L,
                        "",
                        ""),
                    null)));
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    var service =
        newService(
            workItemService,
            Mockito.mock(AutomationAdmissionStateService.class),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptScheduleInstanceService.class),
            new ScriptRuntimeProperties(),
            gameSessionClient);

    var response =
        service.listScriptHandoffEvents(
            ListScriptHandoffEventsRequest.newBuilder().setTenantId("1").build());

    assertThat(response.hasError()).isFalse();
    assertThat(response.getEventsCount()).isEqualTo(1);
    assertThat(response.getEvents(0).getGameInstanceId()).isEqualTo("game-1");
    assertThat(response.getEvents(0).getTargetGameInstanceId()).isBlank();
    assertThat(response.getEvents(0).getGameplayCommandExecutionOutcome()).isBlank();
    Mockito.verifyNoInteractions(gameSessionClient);
  }

  @Test
  void listsScriptDeadLettersAndCollapsesPartialRoutingBundles() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    Mockito.when(workItemService.listDeadLetters("1", "game-1", "patch-1", 25))
        .thenReturn(
            List.of(
                new ScriptWorkItemService.DeadLetterSummary(
                    "99",
                    "1",
                    "game-1",
                    "region-1",
                    12L,
                    "entity-1",
                    "SHARED",
                    "demo",
                    "",
                    "17",
                    "GAMEPLAY_EVENT",
                    "WORK_ITEM_PERSISTED",
                    0L,
                    0L,
                    0L,
                    "script-1",
                    "",
                    "",
                    "onCommand",
                    "patch-1",
                    0L,
                    "",
                    "event-1",
                    "DEAD_LETTERED",
                    "STALE_TIMELINE",
                    100L,
                    200L,
                    new ScriptWorkItemService.ScriptPatchPublicationLink(
                        "patch-1",
                        18L,
                        9L,
                        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                            .VERSION_LIFECYCLE_STATE_PUBLISHED,
                        140L,
                        "",
                        ""),
                    null)));
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    Mockito.when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState.newBuilder()
                        .setGameInstanceId("game-1")
                        .setRegionId("region-1")
                        .setRegionEpoch(99L)
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_SHARED)
                        .setWorldSlug("demo-next")
                        .build())
                .build());
    var service =
        newService(
            workItemService,
            Mockito.mock(AutomationAdmissionStateService.class),
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptScheduleInstanceService.class),
            new ScriptRuntimeProperties(),
            gameSessionClient);

    var response =
        service.listScriptDeadLetters(
            ListScriptDeadLettersRequest.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setScriptPatchVersion("patch-1")
                .setLimit(25)
                .build());

    assertThat(response.hasError()).isFalse();
    assertThat(response.getDeadLettersCount()).isEqualTo(1);
    assertThat(response.getDeadLetters(0).getWorldSlug()).isBlank();
    assertThat(response.getDeadLetters(0).getRealmSlug()).isBlank();
    assertThat(response.getDeadLetters(0).getPointerVersion()).isBlank();
    assertThat(response.getDeadLetters(0).getIsRoutingBundleStale()).isFalse();
  }

  @Test
  void setsAutomationAdmissionModeThroughAdmissionStateService() {
    AutomationAdmissionStateService admissionStateService =
        Mockito.mock(AutomationAdmissionStateService.class);
    Mockito.when(admissionStateService.setMode(Mockito.any()))
        .thenReturn(
            new AutomationAdmissionStateService.AdmissionStateSummary(
                "1",
                "game-1",
                "region-1",
                "PAUSED_FOR_ROLLBACK",
                2L,
                "req-2",
                "admin",
                "rollback",
                300L));
    var service =
        newService(
            Mockito.mock(ScriptWorkItemService.class),
            admissionStateService,
            Mockito.mock(ScriptPatchPinProjectionService.class),
            Mockito.mock(ScriptScheduleInstanceService.class),
            new ScriptRuntimeProperties(),
            Mockito.mock(GameSessionControlPlaneClient.class));

    var response =
        service.setAutomationAdmissionMode(
            net.firedevops.firemud.automationscripting.v1.SetAutomationAdmissionModeRequest
                .newBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setRegionId("region-1")
                .setMode(AutomationAdmissionMode.AUTOMATION_ADMISSION_MODE_PAUSED_FOR_ROLLBACK)
                .setControlPlaneRequestId("req-2")
                .setActorPrincipal("admin")
                .setReason("rollback")
                .build());

    assertThat(response.hasError()).isFalse();
    assertThat(response.getMode())
        .isEqualTo(AutomationAdmissionMode.AUTOMATION_ADMISSION_MODE_PAUSED_FOR_ROLLBACK);
    assertThat(response.getAdmissionEpoch()).isEqualTo(2L);
  }
}
