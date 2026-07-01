package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.GameplayCommandStatus;
import net.firedevops.firemud.gamesession.v1.GetGameplayCommandStatusResponse;
import net.firedevops.firemud.gamesession.v1.PluginPublicationLink;
import net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.GameplayCommandStatusDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.server.ResponseStatusException;

class GameplayCommandStatusServiceImplTest {
  @Mock private GameSessionControlPlaneClient gameSessionControlPlaneClient;

  @InjectMocks private GameplayCommandStatusServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
  }

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void getGameplayCommandStatusReturnsCanonicalControlPlaneProjection() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.getGameplayCommandStatus("cmd-123"))
        .thenReturn(
            GetGameplayCommandStatusResponse.newBuilder().setCommand(commandStatus("2")).build());

    GameplayCommandStatusDto result = service.getGameplayCommandStatus(2L, "cmd-123");

    assertEquals("cmd-123", result.commandId());
    assertEquals(2L, result.tenantId());
    assertEquals(17L, result.publication().versionId());
    assertTrue(result.currentRuntimeRoutingBundleStale());
  }

  @Test
  void getGameplayCommandStatusRejectsMismatchedControlPlaneTenant() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.getGameplayCommandStatus("cmd-123"))
        .thenReturn(
            GetGameplayCommandStatusResponse.newBuilder().setCommand(commandStatus("8")).build());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> service.getGameplayCommandStatus(2L, "cmd-123"));

    assertEquals(500, ex.getStatusCode().value());
  }

  @Test
  void getGameplayCommandStatusRequiresAccessibleTenant() {
    SessionContext.setContext("42", List.of(), Map.of("8", List.of("tenantAdmin")));

    assertThrows(
        ResponseStatusException.class, () -> service.getGameplayCommandStatus(2L, "cmd-123"));
  }

  private GameplayCommandStatus commandStatus(String tenantId) {
    return GameplayCommandStatus.newBuilder()
        .setCommandId("cmd-123")
        .setTenantId(tenantId)
        .setGameInstanceId("7")
        .setSessionId("11")
        .setAccountId("42")
        .setCharacterId("99")
        .setCommandName("LOOK")
        .setSanitizedCommandText("look")
        .setRequiresSoloTick(false)
        .setExecutionOutcome("SUCCEEDED")
        .setGameplayResult("OK")
        .setAcceptedAtMs(Instant.parse("2026-06-30T00:00:00Z").toEpochMilli())
        .setStagedAtMs(Instant.parse("2026-06-30T00:00:01Z").toEpochMilli())
        .setCompletedAtMs(Instant.parse("2026-06-30T00:00:02Z").toEpochMilli())
        .setLastAttemptAtMs(Instant.parse("2026-06-30T00:00:03Z").toEpochMilli())
        .setAttemptCount(2)
        .setSourceType("PLAYER")
        .setAutomationDispatchId("dispatch-1")
        .setAutomationWorkItemId("work-1")
        .setScriptId("script-1")
        .setScriptPatchVersion("patch-9")
        .setPluginId("plugin-a")
        .setPluginVersionId("plugin-v1")
        .setTargetEntityId("entity-7")
        .setRegionId("region-7")
        .setRegionEpoch(22L)
        .setDueTickId(44L)
        .setEnqueueSeq(55L)
        .setPlayableStateScope(
            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                .PLAYABLE_STATE_SCOPE_SHARED)
        .setWorldSlug("demo")
        .setRealmSlug("production")
        .setPointerVersion(8L)
        .setOriginSourceKind("AUTOMATION")
        .setOriginSourceState("SCHEDULED")
        .setOriginSourceOrdinal(12L)
        .setOriginSourceDueTickId(13L)
        .setOriginSourceDueAtMs(14L)
        .setQueueSourceKind("REMOTE_FOLLOWUP_QUEUE")
        .setQueueSourceState("CLAIMED")
        .setQueueSourceOrdinal(15L)
        .setQueueSourceDueTickId(16L)
        .setQueueSourceDueAtMs(17L)
        .setRemoteCoordinatorId("coord-1")
        .setRemoteFollowupId("follow-1")
        .setRemoteState("COMPLETED")
        .setRemoteResultOutcome("SUCCEEDED")
        .setRemoteResultPayloadJson("{\"ok\":true}")
        .setRemoteResultObservedAtMs(Instant.parse("2026-06-30T00:00:04Z").toEpochMilli())
        .setPublication(
            ScriptPatchPublicationLink.newBuilder()
                .setScriptPatchVersion("patch-9")
                .setVersionId(17L)
                .setBaseVersionId(7L)
                .setPublicationState(
                    net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                        .VERSION_LIFECYCLE_STATE_PUBLISHED)
                .setLastChangedAtMs(Instant.parse("2026-06-30T00:00:05Z").toEpochMilli())
                .build())
        .setRemoteResultCommandId("remote-cmd-1")
        .setRemoteResultErrorCode("NONE")
        .setPluginPublication(
            PluginPublicationLink.newBuilder()
                .setPluginVersionId("plugin-v1")
                .setPublicationId(88L)
                .setPublicationState(
                    net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                        .VERSION_LIFECYCLE_STATE_PUBLISHED)
                .setLastChangedAtMs(Instant.parse("2026-06-30T00:00:06Z").toEpochMilli())
                .build())
        .setRemoteOriginGameInstanceId("7")
        .setRemoteOriginRegionId("origin-region")
        .setRemoteOriginRegionEpoch(21L)
        .setRemoteTargetGameInstanceId("8")
        .setRemoteTargetRegionId("target-region")
        .setRemoteTargetRegionEpoch(22L)
        .setRemoteOriginDeadlineRegionEpoch(23L)
        .setRemoteOriginDeadlineTickId(24L)
        .setRemoteLateResultPolicy("DROP_STALE")
        .setRemoteTargetCommandExecutionOutcome("SUCCEEDED")
        .setRemoteTargetCommandGameplayResult("OK")
        .setRemoteFollowupStatus("COMPLETED")
        .setRemoteFollowupPayloadKind("enqueue_gameplay_command")
        .setRemoteFollowupRequestedCommand("look north")
        .setRemoteFollowupRequiresSoloTick(false)
        .setRemoteFollowupOriginSourceKind("AUTOMATION")
        .setRemoteFollowupOriginSourceState("SCHEDULED")
        .setRemoteFollowupOriginSourceOrdinal(25L)
        .setRemoteFollowupOriginSourceDueTickId(26L)
        .setRemoteFollowupOriginSourceDueAtMs(27L)
        .setRemoteTargetEntityId("entity-8")
        .setRemoteFollowupEffectKey("effect-1")
        .setRemoteFollowupEventType("onCommand")
        .setRemoteFollowupEventSchemaVersion("v1")
        .setRemoteFollowupScriptEventId("event-1")
        .setRemoteFollowupTriggerMode("DIRECT")
        .setRemoteFollowupClaimTargetAggregate("entity:8")
        .setCurrentRuntimeRegionId("current-region")
        .setCurrentRuntimeRegionEpoch(28L)
        .setCurrentRuntimeGameInstanceId("7")
        .setCurrentRuntimePlayableStateScope(
            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                .PLAYABLE_STATE_SCOPE_SHARED)
        .setCurrentRuntimeWorldSlug("demo")
        .setCurrentRuntimeRealmSlug("production")
        .setCurrentRuntimePointerVersion(9L)
        .setIsCurrentRuntimeRoutingBundleStale(true)
        .build();
  }
}
