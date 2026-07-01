package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.GetRemoteCommandCoordinatorResponse;
import net.firedevops.firemud.gamesession.v1.PluginPublicationLink;
import net.firedevops.firemud.gamesession.v1.RemoteCommandCoordinatorEntry;
import net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.RemoteCommandCoordinatorDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.server.ResponseStatusException;

class RemoteCommandCoordinatorServiceImplTest {
  @Mock private GameSessionControlPlaneClient gameSessionControlPlaneClient;

  @InjectMocks private RemoteCommandCoordinatorServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
  }

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void getRemoteCommandCoordinatorReturnsCanonicalControlPlaneProjection() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.getRemoteCommandCoordinator(2L, "coord-123"))
        .thenReturn(
            GetRemoteCommandCoordinatorResponse.newBuilder()
                .setCoordinator(remoteCoordinator("2", "coord-123"))
                .build());

    RemoteCommandCoordinatorDto result = service.getRemoteCommandCoordinator(2L, "coord-123");

    assertEquals("coord-123", result.coordinatorId());
    assertEquals(2L, result.tenantId());
    assertEquals(17L, result.publication().versionId());
    assertTrue(result.targetRoutingBundleStale());
  }

  @Test
  void getRemoteCommandCoordinatorRejectsMismatchedControlPlaneTenant() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.getRemoteCommandCoordinator(2L, "coord-123"))
        .thenReturn(
            GetRemoteCommandCoordinatorResponse.newBuilder()
                .setCoordinator(remoteCoordinator("8", "coord-123"))
                .build());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> service.getRemoteCommandCoordinator(2L, "coord-123"));

    assertEquals(500, ex.getStatusCode().value());
  }

  @Test
  void getRemoteCommandCoordinatorRejectsMismatchedCoordinatorId() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.getRemoteCommandCoordinator(2L, "coord-123"))
        .thenReturn(
            GetRemoteCommandCoordinatorResponse.newBuilder()
                .setCoordinator(remoteCoordinator("2", "coord-999"))
                .build());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> service.getRemoteCommandCoordinator(2L, "coord-123"));

    assertEquals(500, ex.getStatusCode().value());
  }

  @Test
  void getRemoteCommandCoordinatorRequiresAccessibleTenant() {
    SessionContext.setContext("42", List.of(), Map.of("8", List.of("tenantAdmin")));

    assertThrows(
        ResponseStatusException.class, () -> service.getRemoteCommandCoordinator(2L, "coord-123"));
  }

  private RemoteCommandCoordinatorEntry remoteCoordinator(String tenantId, String coordinatorId) {
    return RemoteCommandCoordinatorEntry.newBuilder()
        .setCoordinatorId(coordinatorId)
        .setTenantId(tenantId)
        .setCommandId("cmd-123")
        .setFollowupId("follow-1")
        .setOriginGameInstanceId("7")
        .setOriginRegionId("origin-region")
        .setOriginRegionEpoch(21L)
        .setTargetGameInstanceId("8")
        .setTargetRegionId("target-region")
        .setTargetRegionEpoch(22L)
        .setTargetDueTickId(44L)
        .setOriginDeadlineRegionEpoch(23L)
        .setOriginDeadlineTickId(24L)
        .setState("PENDING_REMOTE")
        .setLateResultPolicy("DROP_STALE")
        .setExecutionOutcome("PENDING_REMOTE")
        .setGameplayResult("PENDING_REMOTE")
        .setUpdatedAtMs(Instant.parse("2026-07-01T00:00:00Z").toEpochMilli())
        .setFollowupStatus("CLAIMED")
        .setFollowupClaimedTickBatchId("91")
        .setLatestResultOutcome("SUCCEEDED")
        .setLatestResultPayloadJson("{\"ok\":true}")
        .setLatestResultObservedAtMs(Instant.parse("2026-07-01T00:00:01Z").toEpochMilli())
        .setFollowupClaimOrdinal(2L)
        .setScriptPatchVersion("patch-9")
        .setPluginId("plugin-a")
        .setPluginVersionId("plugin-v1")
        .setPublication(
            ScriptPatchPublicationLink.newBuilder()
                .setScriptPatchVersion("patch-9")
                .setVersionId(17L)
                .setBaseVersionId(7L)
                .setPublicationState(
                    net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                        .VERSION_LIFECYCLE_STATE_PUBLISHED)
                .setLastChangedAtMs(Instant.parse("2026-07-01T00:00:02Z").toEpochMilli())
                .build())
        .setPlayableStateScope(
            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                .PLAYABLE_STATE_SCOPE_SHARED)
        .setWorldSlug("demo")
        .setRealmSlug("production")
        .setPointerVersion(8L)
        .setAutomationDispatchId("dispatch-1")
        .setAutomationWorkItemId("work-1")
        .setScriptId("script-1")
        .setFollowupPayloadKind("enqueue_gameplay_command")
        .setFollowupRequestedCommand("look north")
        .setLatestResultCommandId("target-cmd-1")
        .setLatestResultErrorCode("NONE")
        .setTargetCommandId("target-cmd-1")
        .setTargetCommandExecutionOutcome("SUCCEEDED")
        .setTargetCommandGameplayResult("OK")
        .setPluginPublication(
            PluginPublicationLink.newBuilder()
                .setPluginVersionId("plugin-v1")
                .setPublicationId(88L)
                .setPublicationState(
                    net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                        .VERSION_LIFECYCLE_STATE_PUBLISHED)
                .setLastChangedAtMs(Instant.parse("2026-07-01T00:00:03Z").toEpochMilli())
                .build())
        .setFollowupRequiresSoloTick(false)
        .setFollowupOriginSourceKind("AUTOMATION")
        .setFollowupOriginSourceState("SCHEDULED")
        .setFollowupOriginSourceOrdinal(25L)
        .setFollowupOriginSourceDueTickId(26L)
        .setFollowupOriginSourceDueAtMs(27L)
        .setTargetEntityId("entity-8")
        .setFollowupEffectKey("effect-1")
        .setFollowupEventType("onCommand")
        .setFollowupEventSchemaVersion("v1")
        .setFollowupScriptEventId("event-1")
        .setFollowupTriggerMode("DIRECT")
        .setFollowupReadSnapshotToken("snapshot-1")
        .setFollowupEventPayloadJson("{\"target\":8}")
        .setFollowupClaimTargetAggregate("entity:8")
        .setCurrentOriginRuntimeRegionId("origin-region-current")
        .setCurrentOriginRuntimeRegionEpoch(31L)
        .setCurrentTargetRuntimeRegionId("target-region-current")
        .setCurrentTargetRuntimeRegionEpoch(32L)
        .setFollowupQueueSourceKind("REMOTE_FOLLOWUP")
        .setFollowupQueueSourceState("TARGET_REGION_CLAIMED")
        .setFollowupQueueSourceOrdinal(4L)
        .setFollowupQueueSourceDueTickId(45L)
        .setFollowupQueueSourceDueAtMs(46L)
        .setCurrentOriginRuntimeGameInstanceId("7")
        .setCurrentTargetRuntimeGameInstanceId("8")
        .setCurrentOriginRuntimePlayableStateScope(
            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                .PLAYABLE_STATE_SCOPE_SHARED)
        .setCurrentOriginRuntimeWorldSlug("demo")
        .setCurrentOriginRuntimeRealmSlug("production")
        .setCurrentOriginRuntimePointerVersion(9L)
        .setCurrentTargetRuntimePlayableStateScope(
            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                .PLAYABLE_STATE_SCOPE_SHARED)
        .setCurrentTargetRuntimeWorldSlug("demo")
        .setCurrentTargetRuntimeRealmSlug("production")
        .setCurrentTargetRuntimePointerVersion(10L)
        .setIsOriginRoutingBundleStale(false)
        .setIsTargetRoutingBundleStale(true)
        .build();
  }
}
