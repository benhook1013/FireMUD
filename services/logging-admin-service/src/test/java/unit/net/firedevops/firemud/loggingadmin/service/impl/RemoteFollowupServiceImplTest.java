package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.GetRemoteFollowupResponse;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupsResponse;
import net.firedevops.firemud.gamesession.v1.PluginPublicationLink;
import net.firedevops.firemud.gamesession.v1.RemoteFollowupEntry;
import net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupDto;
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupListRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RemoteFollowupServiceImplTest {
  @Mock private GameSessionControlPlaneClient gameSessionControlPlaneClient;

  @InjectMocks private RemoteFollowupServiceImpl service;

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void getRemoteFollowupReturnsCanonicalProjection() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.getRemoteFollowup(2L, "rf-1"))
        .thenReturn(
            GetRemoteFollowupResponse.newBuilder().setFollowup(remoteFollowup("2")).build());

    RemoteFollowupDto result = service.getRemoteFollowup(2L, "rf-1");

    assertAll(
        () -> assertEquals("rf-1", result.followupId()),
        () -> assertEquals(2L, result.tenantId()),
        () -> assertEquals(7L, result.originGameInstanceId()),
        () -> assertEquals(9L, result.targetGameInstanceId()),
        () -> assertEquals("SCHEDULED", result.status()),
        () -> assertEquals("LOOK", result.requestedCommand()),
        () -> assertEquals("target-cmd-1", result.targetCommandId()),
        () -> assertEquals(7L, result.currentOriginRuntimeGameInstanceId()),
        () -> assertEquals(9L, result.currentTargetRuntimeGameInstanceId()),
        () -> assertEquals(31L, result.pluginPublication().publicationId()));
  }

  @Test
  void getRemoteFollowupRejectsMismatchedTenantRow() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.getRemoteFollowup(2L, "rf-1"))
        .thenReturn(
            GetRemoteFollowupResponse.newBuilder().setFollowup(remoteFollowup("8")).build());

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> service.getRemoteFollowup(2L, "rf-1"));

    assertEquals(500, ex.getStatusCode().value());
  }

  @Test
  void getRemoteFollowupRejectsMismatchedFollowupId() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.getRemoteFollowup(2L, "rf-1"))
        .thenReturn(
            GetRemoteFollowupResponse.newBuilder()
                .setFollowup(remoteFollowup("2", "rf-9"))
                .build());

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> service.getRemoteFollowup(2L, "rf-1"));

    assertEquals(500, ex.getStatusCode().value());
  }

  @Test
  void getRemoteFollowupRequiresAccessibleTenant() {
    SessionContext.setContext("42", List.of(), Map.of("8", List.of("tenantAdmin")));

    assertThrows(ResponseStatusException.class, () -> service.getRemoteFollowup(2L, "rf-1"));
  }

  @Test
  void listRemoteFollowupsBuildsCanonicalFilterRequestAndMapsRows() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    RemoteFollowupListRequest request = new RemoteFollowupListRequest();
    request.setTargetRegionId("region-b");
    request.setStatus("SCHEDULED");
    request.setOriginGameInstanceId("7");
    request.setOriginRegionId("region-a");
    request.setTargetGameInstanceId("9");
    request.setTargetRegionEpoch(4L);
    request.setFollowupId("rf-1");
    request.setScriptId("script-1");
    request.setPluginId("plugin-1");
    request.setAutomationDispatchId("dispatch-1");
    request.setCommandId("cmd-1");
    request.setLimit(25);
    request.setOriginRegionEpoch(3L);
    request.setScriptPatchVersion("patch-1");
    request.setPluginVersionId("plugin-v1");
    request.setPlayableStateScope("PLAYABLE_STATE_SCOPE_ISOLATED");
    request.setWorldSlug("ops");
    request.setRealmSlug("preview");
    request.setPointerVersion(29L);
    request.setPayloadKind("enqueue_automation_command");
    request.setOriginSourceKind("REMOTE_FOLLOWUP");
    request.setAutomationWorkItemId("work-1");
    request.setTargetEntityId("entity-9");
    request.setEffectKey("damage:1");
    request.setRequiresSoloTick(true);
    request.setEventType("onEnterRegion");
    request.setScriptEventId("evt-1");
    request.setTargetCommandId("target-cmd-1");
    request.setTargetCommandExecutionOutcome("APPLIED");
    request.setTargetCommandGameplayResult("SUCCESS");
    request.setClaimedTickBatchId("91");
    request.setRequestedCommand("LOOK");
    request.setOriginSourceState("TARGET_REGION_EXECUTED");
    request.setOriginDeadlineRegionEpoch(3L);
    request.setOriginDeadlineTickId(88L);
    request.setLateResultPolicy("late_result_safe_to_ignore");
    request.setClaimTargetAggregate("entity:entity-9");
    request.setCurrentOriginRuntimeRegionId("region-origin-current");
    request.setCurrentOriginRuntimeRegionEpoch(13L);
    request.setCurrentTargetRuntimeRegionId("region-target-current");
    request.setCurrentTargetRuntimeRegionEpoch(14L);
    request.setQueueSourceKind("REMOTE_FOLLOWUP");
    request.setQueueSourceState("TARGET_REGION_CLAIMED");
    request.setQueueSourceOrdinal(2L);
    request.setQueueSourceDueTickId(55L);
    request.setQueueSourceDueAtMs(1700L);
    request.setCurrentOriginRuntimeGameInstanceId("7");
    request.setCurrentTargetRuntimeGameInstanceId("9");
    request.setFailureCode("NONE");

    when(gameSessionControlPlaneClient.listRemoteFollowups(
            argThat(
                grpcRequest ->
                    "2".equals(grpcRequest.getTenantId())
                        && "region-b".equals(grpcRequest.getTargetRegionId())
                        && "SCHEDULED".equals(grpcRequest.getStatus())
                        && "7".equals(grpcRequest.getOriginGameInstanceId())
                        && "region-a".equals(grpcRequest.getOriginRegionId())
                        && "9".equals(grpcRequest.getTargetGameInstanceId())
                        && grpcRequest.getTargetRegionEpoch() == 4L
                        && "rf-1".equals(grpcRequest.getFollowupId())
                        && "script-1".equals(grpcRequest.getScriptId())
                        && "plugin-1".equals(grpcRequest.getPluginId())
                        && "dispatch-1".equals(grpcRequest.getAutomationDispatchId())
                        && "cmd-1".equals(grpcRequest.getCommandId())
                        && grpcRequest.getLimit() == 25
                        && grpcRequest.getOriginRegionEpoch() == 3L
                        && "patch-1".equals(grpcRequest.getScriptPatchVersion())
                        && "plugin-v1".equals(grpcRequest.getPluginVersionId())
                        && grpcRequest.getPlayableStateScope()
                            == PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED
                        && "ops".equals(grpcRequest.getWorldSlug())
                        && "preview".equals(grpcRequest.getRealmSlug())
                        && grpcRequest.getPointerVersion() == 29L
                        && "enqueue_automation_command".equals(grpcRequest.getPayloadKind())
                        && "REMOTE_FOLLOWUP".equals(grpcRequest.getOriginSourceKind())
                        && "work-1".equals(grpcRequest.getAutomationWorkItemId())
                        && "entity-9".equals(grpcRequest.getTargetEntityId())
                        && "damage:1".equals(grpcRequest.getEffectKey())
                        && grpcRequest.getRequiresSoloTick()
                        && "onEnterRegion".equals(grpcRequest.getEventType())
                        && "evt-1".equals(grpcRequest.getScriptEventId())
                        && "target-cmd-1".equals(grpcRequest.getTargetCommandId())
                        && "APPLIED".equals(grpcRequest.getTargetCommandExecutionOutcome())
                        && "SUCCESS".equals(grpcRequest.getTargetCommandGameplayResult())
                        && "91".equals(grpcRequest.getClaimedTickBatchId())
                        && "LOOK".equals(grpcRequest.getRequestedCommand())
                        && "TARGET_REGION_EXECUTED".equals(grpcRequest.getOriginSourceState())
                        && grpcRequest.getOriginDeadlineRegionEpoch() == 3L
                        && grpcRequest.getOriginDeadlineTickId() == 88L
                        && "late_result_safe_to_ignore".equals(grpcRequest.getLateResultPolicy())
                        && "entity:entity-9".equals(grpcRequest.getClaimTargetAggregate())
                        && "region-origin-current"
                            .equals(grpcRequest.getCurrentOriginRuntimeRegionId())
                        && grpcRequest.getCurrentOriginRuntimeRegionEpoch() == 13L
                        && "region-target-current"
                            .equals(grpcRequest.getCurrentTargetRuntimeRegionId())
                        && grpcRequest.getCurrentTargetRuntimeRegionEpoch() == 14L
                        && "REMOTE_FOLLOWUP".equals(grpcRequest.getQueueSourceKind())
                        && "TARGET_REGION_CLAIMED".equals(grpcRequest.getQueueSourceState())
                        && grpcRequest.getQueueSourceOrdinal() == 2L
                        && grpcRequest.getQueueSourceDueTickId() == 55L
                        && grpcRequest.getQueueSourceDueAtMs() == 1700L
                        && "7".equals(grpcRequest.getCurrentOriginRuntimeGameInstanceId())
                        && "9".equals(grpcRequest.getCurrentTargetRuntimeGameInstanceId())
                        && "NONE".equals(grpcRequest.getFailureCode()))))
        .thenReturn(
            ListRemoteFollowupsResponse.newBuilder().addFollowups(remoteFollowup("2")).build());

    List<RemoteFollowupDto> result = service.listRemoteFollowups(2L, request);

    assertEquals(1, result.size());
    assertAll(
        () -> assertEquals("rf-1", result.getFirst().followupId()),
        () -> assertEquals(2L, result.getFirst().tenantId()),
        () -> assertEquals(7L, result.getFirst().originGameInstanceId()),
        () -> assertEquals(9L, result.getFirst().targetGameInstanceId()),
        () -> assertEquals("SCHEDULED", result.getFirst().status()),
        () -> assertEquals("tick-batch-7", result.getFirst().claimedTickBatchId()),
        () -> assertEquals("dispatch-1", result.getFirst().automationDispatchId()),
        () -> assertEquals("LOOK", result.getFirst().requestedCommand()),
        () -> assertEquals("target-cmd-1", result.getFirst().targetCommandId()),
        () -> assertEquals("APPLIED", result.getFirst().targetCommandExecutionOutcome()),
        () -> assertEquals("SUCCESS", result.getFirst().targetCommandGameplayResult()),
        () ->
            assertEquals("region-origin-current", result.getFirst().currentOriginRuntimeRegionId()),
        () -> assertEquals(7L, result.getFirst().currentOriginRuntimeGameInstanceId()),
        () -> assertEquals(9L, result.getFirst().currentTargetRuntimeGameInstanceId()),
        () ->
            assertEquals(
                "PLAYABLE_STATE_SCOPE_SHARED",
                result.getFirst().currentTargetRuntimePlayableStateScope()),
        () -> assertEquals(31L, result.getFirst().pluginPublication().publicationId()));
  }

  @Test
  void listRemoteFollowupsRejectsUnknownPlayableStateScopeFilter() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    RemoteFollowupListRequest request = new RemoteFollowupListRequest();
    request.setPlayableStateScope("NOT_A_SCOPE");

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> service.listRemoteFollowups(2L, request));

    assertEquals(400, ex.getStatusCode().value());
    verifyNoInteractions(gameSessionControlPlaneClient);
  }

  @Test
  void listRemoteFollowupsRejectsMismatchedTenantRow() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.listRemoteFollowups(
            argThat(grpcRequest -> "2".equals(grpcRequest.getTenantId()))))
        .thenReturn(
            ListRemoteFollowupsResponse.newBuilder().addFollowups(remoteFollowup("8")).build());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> service.listRemoteFollowups(2L, new RemoteFollowupListRequest()));

    assertEquals(500, ex.getStatusCode().value());
  }

  @Test
  void listRemoteFollowupsRequiresAccessibleTenant() {
    SessionContext.setContext("42", List.of(), Map.of("8", List.of("tenantAdmin")));

    assertThrows(
        ResponseStatusException.class,
        () -> service.listRemoteFollowups(2L, new RemoteFollowupListRequest()));
  }

  private RemoteFollowupEntry remoteFollowup(String tenantId) {
    return remoteFollowup(tenantId, "rf-1");
  }

  private RemoteFollowupEntry remoteFollowup(String tenantId, String followupId) {
    return RemoteFollowupEntry.newBuilder()
        .setFollowupId(followupId)
        .setTenantId(tenantId)
        .setOriginGameInstanceId("7")
        .setOriginRegionId("region-a")
        .setOriginRegionEpoch(3L)
        .setTargetGameInstanceId("9")
        .setTargetRegionId("region-b")
        .setTargetRegionEpoch(4L)
        .setDueTickId(55L)
        .setEffectKey("damage:1")
        .setTargetEntityId("entity-9")
        .setStatus("SCHEDULED")
        .setClaimedTickBatchId("tick-batch-7")
        .setPayloadJson("{\"kind\":\"enqueue_automation_command\",\"command\":\"LOOK\"}")
        .setFailureCode("NONE")
        .setFailureMessage("ok")
        .setCreatedAtMs(Instant.parse("2026-07-02T00:00:00Z").toEpochMilli())
        .setUpdatedAtMs(Instant.parse("2026-07-02T00:00:01Z").toEpochMilli())
        .setClaimOrdinal(2L)
        .setScriptPatchVersion("patch-1")
        .setPluginId("plugin-1")
        .setPluginVersionId("plugin-v1")
        .setPublication(
            ScriptPatchPublicationLink.newBuilder()
                .setScriptPatchVersion("patch-1")
                .setVersionId(17L)
                .setBaseVersionId(7L)
                .setPublicationState(
                    net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                        .VERSION_LIFECYCLE_STATE_PUBLISHED)
                .setLastChangedAtMs(Instant.parse("2026-07-02T00:00:02Z").toEpochMilli())
                .build())
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)
        .setWorldSlug("ops")
        .setRealmSlug("preview")
        .setPointerVersion(29L)
        .setCommandId("cmd-1")
        .setAutomationDispatchId("dispatch-1")
        .setAutomationWorkItemId("work-1")
        .setScriptId("script-1")
        .setPayloadKind("enqueue_automation_command")
        .setRequestedCommand("LOOK")
        .setTargetCommandId("target-cmd-1")
        .setTargetCommandExecutionOutcome("APPLIED")
        .setTargetCommandGameplayResult("SUCCESS")
        .setPluginPublication(
            PluginPublicationLink.newBuilder()
                .setPluginVersionId("plugin-v1")
                .setPublicationId(31L)
                .setPublicationState(
                    net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                        .VERSION_LIFECYCLE_STATE_PUBLISHED)
                .setLastChangedAtMs(Instant.parse("2026-07-02T00:00:03Z").toEpochMilli())
                .build())
        .setRequiresSoloTick(true)
        .setOriginSourceKind("REMOTE_FOLLOWUP")
        .setOriginSourceState("TARGET_REGION_EXECUTED")
        .setOriginSourceOrdinal(44L)
        .setOriginSourceDueTickId(55L)
        .setOriginSourceDueAtMs(1700L)
        .setOriginDeadlineRegionEpoch(3L)
        .setOriginDeadlineTickId(88L)
        .setLateResultPolicy("late_result_safe_to_ignore")
        .setEventType("onEnterRegion")
        .setEventSchemaVersion("v1")
        .setScriptEventId("evt-1")
        .setTriggerMode("TRIGGER_MODE_CATCH_UP")
        .setReadSnapshotToken("game-session:onEnterRegion:9:8:evt-1")
        .setEventPayloadJson("{\"fromRegionId\":\"room-a\",\"toRegionId\":\"room-b\"}")
        .setClaimTargetAggregate("entity:entity-9")
        .setCurrentOriginRuntimeRegionId("region-origin-current")
        .setCurrentOriginRuntimeRegionEpoch(13L)
        .setCurrentTargetRuntimeRegionId("region-target-current")
        .setCurrentTargetRuntimeRegionEpoch(14L)
        .setQueueSourceKind("REMOTE_FOLLOWUP")
        .setQueueSourceState("TARGET_REGION_CLAIMED")
        .setQueueSourceOrdinal(2L)
        .setQueueSourceDueTickId(55L)
        .setQueueSourceDueAtMs(1700L)
        .setCurrentOriginRuntimeGameInstanceId("7")
        .setCurrentTargetRuntimeGameInstanceId("9")
        .setCurrentOriginRuntimePlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .setCurrentOriginRuntimeWorldSlug("world-7")
        .setCurrentOriginRuntimeRealmSlug("realm-7")
        .setCurrentOriginRuntimePointerVersion(107L)
        .setCurrentTargetRuntimePlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .setCurrentTargetRuntimeWorldSlug("world-9")
        .setCurrentTargetRuntimeRealmSlug("realm-9")
        .setCurrentTargetRuntimePointerVersion(109L)
        .setIsOriginRoutingBundleStale(true)
        .setIsTargetRoutingBundleStale(true)
        .build();
  }
}
