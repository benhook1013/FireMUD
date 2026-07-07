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
import net.firedevops.firemud.gamesession.v1.GetRemoteFollowupResultResponse;
import net.firedevops.firemud.gamesession.v1.ListRemoteFollowupResultsResponse;
import net.firedevops.firemud.gamesession.v1.PluginPublicationLink;
import net.firedevops.firemud.gamesession.v1.RemoteFollowupResultEntry;
import net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupResultDto;
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupResultListRequest;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.server.ResponseStatusException;

class RemoteFollowupResultServiceImplTest {
  @Mock private GameSessionControlPlaneClient gameSessionControlPlaneClient;

  @InjectMocks private RemoteFollowupResultServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
  }

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void getRemoteFollowupResultReturnsCanonicalProjection() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.getRemoteFollowupResult(2L, "rr-1"))
        .thenReturn(
            GetRemoteFollowupResultResponse.newBuilder().setResult(remoteResult("2")).build());

    RemoteFollowupResultDto result = service.getRemoteFollowupResult(2L, "rr-1");

    assertAll(
        () -> assertEquals("rr-1", result.resultId()),
        () -> assertEquals(2L, result.tenantId()),
        () -> assertEquals("coord-1", result.coordinatorId()),
        () -> assertEquals("rf-1", result.followupId()),
        () -> assertEquals("REMOTE_APPLIED", result.outcome()),
        () -> assertEquals("auto-1", result.resultCommandId()),
        () -> assertEquals("7", result.currentOriginRuntimeGameInstanceId()),
        () -> assertEquals("9", result.currentTargetRuntimeGameInstanceId()),
        () -> assertEquals(31L, result.pluginPublication().publicationId()));
  }

  @Test
  void getRemoteFollowupResultRejectsMismatchedTenantRow() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.getRemoteFollowupResult(2L, "rr-1"))
        .thenReturn(
            GetRemoteFollowupResultResponse.newBuilder().setResult(remoteResult("8")).build());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> service.getRemoteFollowupResult(2L, "rr-1"));

    assertEquals(500, ex.getStatusCode().value());
  }

  @Test
  void getRemoteFollowupResultRejectsMismatchedResultId() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.getRemoteFollowupResult(2L, "rr-1"))
        .thenReturn(
            GetRemoteFollowupResultResponse.newBuilder()
                .setResult(remoteResult("2", "rr-9"))
                .build());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> service.getRemoteFollowupResult(2L, "rr-1"));

    assertEquals(500, ex.getStatusCode().value());
  }

  @Test
  void getRemoteFollowupResultRejectsZeroTenantIdInResponse() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.getRemoteFollowupResult(2L, "rr-1"))
        .thenReturn(
            GetRemoteFollowupResultResponse.newBuilder().setResult(remoteResult("0")).build());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> service.getRemoteFollowupResult(2L, "rr-1"));

    assertEquals(500, ex.getStatusCode().value());
  }

  @Test
  void getRemoteFollowupResultRequiresAccessibleTenant() {
    SessionContext.setContext("42", List.of(), Map.of("8", List.of("tenantAdmin")));

    assertThrows(ResponseStatusException.class, () -> service.getRemoteFollowupResult(2L, "rr-1"));
  }

  @Test
  void getRemoteFollowupResultPropagatesNotFoundErrorAs404() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.getRemoteFollowupResult(2L, "rr-404"))
        .thenReturn(
            GetRemoteFollowupResultResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("NOT_FOUND")
                        .setMessage("Remote followup result not found")
                        .build())
                .build());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> service.getRemoteFollowupResult(2L, "rr-404"));

    assertEquals(404, ex.getStatusCode().value());
  }

  @Test
  void listRemoteFollowupResultsBuildsCanonicalFilterRequestAndMapsRows() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    RemoteFollowupResultListRequest request = new RemoteFollowupResultListRequest();
    request.setCoordinatorId("coord-1");
    request.setFollowupId("rf-1");
    request.setOriginGameInstanceId("7");
    request.setOriginRegionId("region-a");
    request.setOriginRegionEpoch(3L);
    request.setTargetGameInstanceId("9");
    request.setTargetRegionId("region-b");
    request.setTargetRegionEpoch(4L);
    request.setCurrentOriginRuntimeRegionId("region-origin-current");
    request.setCurrentOriginRuntimeRegionEpoch(13L);
    request.setCurrentOriginRuntimeGameInstanceId("7");
    request.setCurrentTargetRuntimeRegionId("region-target-current");
    request.setCurrentTargetRuntimeRegionEpoch(14L);
    request.setCurrentTargetRuntimeGameInstanceId("9");
    request.setOutcome("REMOTE_APPLIED");
    request.setScriptId("script-1");
    request.setPluginId("plugin-1");
    request.setScriptPatchVersion("patch-1");
    request.setPluginVersionId("plugin-v1");
    request.setPlayableStateScope("PLAYABLE_STATE_SCOPE_SHARED");
    request.setWorldSlug("demo");
    request.setRealmSlug("production");
    request.setPointerVersion(17L);
    request.setResultErrorCode("RATE_LIMIT");
    request.setAutomationWorkItemId("work-1");
    request.setResultCommandId("auto-1");
    request.setResultCommandExecutionOutcome("APPLIED");
    request.setResultCommandGameplayResult("SUCCESS");
    request.setTargetEntityId("npc-7");
    request.setClaimTargetAggregate("entity:npc-7");
    request.setEffectKey("remote-followup:dispatch-1");
    request.setFailureCode("REMOTE_REJECTED");
    request.setPayloadKind("trigger_script_event");
    request.setOriginSourceKind("REMOTE_FOLLOWUP");
    request.setOriginSourceState("SCHEDULED");
    request.setEventType("onEnterRegion");
    request.setScriptEventId("remote-enter-1");
    request.setResultMessage("Target region rejected the remote gameplay command");
    request.setRequiresSoloTick(true);
    request.setQueueSourceKind("REMOTE_FOLLOWUP");
    request.setQueueSourceState("TARGET_REGION_CLAIMED");
    request.setQueueSourceOrdinal(3L);
    request.setQueueSourceDueTickId(55L);
    request.setQueueSourceDueAtMs(1700L);
    request.setLateResultPolicy("late_result_safe_to_ignore");
    request.setClaimedTickBatchId("tick-batch-7");
    request.setAutomationDispatchId("dispatch-1");
    request.setCommandId("cmd-1");
    request.setLimit(25);

    when(gameSessionControlPlaneClient.listRemoteFollowupResults(
            argThat(
                grpcRequest ->
                    "2".equals(grpcRequest.getTenantId())
                        && "coord-1".equals(grpcRequest.getCoordinatorId())
                        && "rf-1".equals(grpcRequest.getFollowupId())
                        && "7".equals(grpcRequest.getOriginGameInstanceId())
                        && "region-a".equals(grpcRequest.getOriginRegionId())
                        && grpcRequest.getOriginRegionEpoch() == 3L
                        && "9".equals(grpcRequest.getTargetGameInstanceId())
                        && "region-b".equals(grpcRequest.getTargetRegionId())
                        && grpcRequest.getTargetRegionEpoch() == 4L
                        && "region-origin-current"
                            .equals(grpcRequest.getCurrentOriginRuntimeRegionId())
                        && grpcRequest.getCurrentOriginRuntimeRegionEpoch() == 13L
                        && "7".equals(grpcRequest.getCurrentOriginRuntimeGameInstanceId())
                        && "region-target-current"
                            .equals(grpcRequest.getCurrentTargetRuntimeRegionId())
                        && grpcRequest.getCurrentTargetRuntimeRegionEpoch() == 14L
                        && "9".equals(grpcRequest.getCurrentTargetRuntimeGameInstanceId())
                        && "REMOTE_APPLIED".equals(grpcRequest.getOutcome())
                        && "script-1".equals(grpcRequest.getScriptId())
                        && "plugin-1".equals(grpcRequest.getPluginId())
                        && "patch-1".equals(grpcRequest.getScriptPatchVersion())
                        && "plugin-v1".equals(grpcRequest.getPluginVersionId())
                        && grpcRequest.getPlayableStateScope()
                            == PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
                        && "demo".equals(grpcRequest.getWorldSlug())
                        && "production".equals(grpcRequest.getRealmSlug())
                        && grpcRequest.getPointerVersion() == 17L
                        && "RATE_LIMIT".equals(grpcRequest.getResultErrorCode())
                        && "work-1".equals(grpcRequest.getAutomationWorkItemId())
                        && "auto-1".equals(grpcRequest.getResultCommandId())
                        && "APPLIED".equals(grpcRequest.getResultCommandExecutionOutcome())
                        && "SUCCESS".equals(grpcRequest.getResultCommandGameplayResult())
                        && "npc-7".equals(grpcRequest.getTargetEntityId())
                        && "entity:npc-7".equals(grpcRequest.getClaimTargetAggregate())
                        && "remote-followup:dispatch-1".equals(grpcRequest.getEffectKey())
                        && "REMOTE_REJECTED".equals(grpcRequest.getFailureCode())
                        && "trigger_script_event".equals(grpcRequest.getPayloadKind())
                        && "REMOTE_FOLLOWUP".equals(grpcRequest.getOriginSourceKind())
                        && "SCHEDULED".equals(grpcRequest.getOriginSourceState())
                        && "onEnterRegion".equals(grpcRequest.getEventType())
                        && "remote-enter-1".equals(grpcRequest.getScriptEventId())
                        && "Target region rejected the remote gameplay command"
                            .equals(grpcRequest.getResultMessage())
                        && grpcRequest.getRequiresSoloTick()
                        && "REMOTE_FOLLOWUP".equals(grpcRequest.getQueueSourceKind())
                        && "TARGET_REGION_CLAIMED".equals(grpcRequest.getQueueSourceState())
                        && grpcRequest.getQueueSourceOrdinal() == 3L
                        && grpcRequest.getQueueSourceDueTickId() == 55L
                        && grpcRequest.getQueueSourceDueAtMs() == 1700L
                        && "late_result_safe_to_ignore".equals(grpcRequest.getLateResultPolicy())
                        && "tick-batch-7".equals(grpcRequest.getClaimedTickBatchId())
                        && "dispatch-1".equals(grpcRequest.getAutomationDispatchId())
                        && "cmd-1".equals(grpcRequest.getCommandId())
                        && grpcRequest.getLimit() == 25)))
        .thenReturn(
            ListRemoteFollowupResultsResponse.newBuilder().addResults(remoteResult("2")).build());

    List<RemoteFollowupResultDto> result = service.listRemoteFollowupResults(2L, request);

    assertEquals(1, result.size());
    assertAll(
        () -> assertEquals("rr-1", result.getFirst().resultId()),
        () -> assertEquals(2L, result.getFirst().tenantId()),
        () -> assertEquals("coord-1", result.getFirst().coordinatorId()),
        () -> assertEquals("rf-1", result.getFirst().followupId()),
        () -> assertEquals("7", result.getFirst().originGameInstanceId()),
        () -> assertEquals("9", result.getFirst().targetGameInstanceId()),
        () -> assertEquals("REMOTE_APPLIED", result.getFirst().outcome()),
        () -> assertEquals("dispatch-1", result.getFirst().automationDispatchId()),
        () -> assertEquals("auto-1", result.getFirst().resultCommandId()),
        () -> assertEquals("APPLIED", result.getFirst().resultCommandExecutionOutcome()),
        () -> assertEquals("SUCCESS", result.getFirst().resultCommandGameplayResult()),
        () -> assertEquals("entity:npc-7", result.getFirst().claimTargetAggregate()),
        () ->
            assertEquals("region-origin-current", result.getFirst().currentOriginRuntimeRegionId()),
        () -> assertEquals("7", result.getFirst().currentOriginRuntimeGameInstanceId()),
        () -> assertEquals("9", result.getFirst().currentTargetRuntimeGameInstanceId()),
        () ->
            assertEquals(
                "PLAYABLE_STATE_SCOPE_SHARED",
                result.getFirst().currentTargetRuntimePlayableStateScope()),
        () -> assertEquals(31L, result.getFirst().pluginPublication().publicationId()));
  }

  @Test
  void listRemoteFollowupResultsMapsInvalidArgumentControlPlaneError() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.listRemoteFollowupResults(
            argThat(grpcRequest -> "2".equals(grpcRequest.getTenantId()))))
        .thenReturn(
            ListRemoteFollowupResultsResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("INVALID_ARGUMENT")
                        .setMessage("bad remote followup result filter")
                        .build())
                .build());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> service.listRemoteFollowupResults(2L, new RemoteFollowupResultListRequest()));

    assertEquals(400, ex.getStatusCode().value());
    assertEquals("bad remote followup result filter", ex.getReason());
  }

  @Test
  void listRemoteFollowupResultsRejectsUnknownPlayableStateScopeFilter() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    RemoteFollowupResultListRequest request = new RemoteFollowupResultListRequest();
    request.setPlayableStateScope("NOT_A_SCOPE");

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> service.listRemoteFollowupResults(2L, request));

    assertEquals(400, ex.getStatusCode().value());
    verifyNoInteractions(gameSessionControlPlaneClient);
  }

  @Test
  void listRemoteFollowupResultsRejectsMismatchedTenantRow() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.listRemoteFollowupResults(
            argThat(grpcRequest -> "2".equals(grpcRequest.getTenantId()))))
        .thenReturn(
            ListRemoteFollowupResultsResponse.newBuilder().addResults(remoteResult("8")).build());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> service.listRemoteFollowupResults(2L, new RemoteFollowupResultListRequest()));

    assertEquals(500, ex.getStatusCode().value());
  }

  @Test
  void listRemoteFollowupResultsRequiresAccessibleTenant() {
    SessionContext.setContext("42", List.of(), Map.of("8", List.of("tenantAdmin")));

    assertThrows(
        ResponseStatusException.class,
        () -> service.listRemoteFollowupResults(2L, new RemoteFollowupResultListRequest()));
  }

  private RemoteFollowupResultEntry remoteResult(String tenantId) {
    return remoteResult(tenantId, "rr-1");
  }

  private RemoteFollowupResultEntry remoteResult(String tenantId, String resultId) {
    return RemoteFollowupResultEntry.newBuilder()
        .setResultId(resultId)
        .setTenantId(tenantId)
        .setCoordinatorId("coord-1")
        .setFollowupId("rf-1")
        .setOriginRegionId("region-a")
        .setOriginRegionEpoch(3L)
        .setTargetRegionId("region-b")
        .setTargetRegionEpoch(4L)
        .setOutcome("REMOTE_APPLIED")
        .setResultPayloadJson(
            "{\"commandId\":\"payload-cmd\",\"errorCode\":\"payload-error\",\"message\":\"payload message\"}")
        .setObservedAtMs(Instant.parse("2026-07-02T01:00:00Z").toEpochMilli())
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
                .setLastChangedAtMs(Instant.parse("2026-07-02T01:00:01Z").toEpochMilli())
                .build())
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .setWorldSlug("demo")
        .setRealmSlug("production")
        .setPointerVersion(17L)
        .setCommandId("cmd-1")
        .setAutomationDispatchId("dispatch-1")
        .setAutomationWorkItemId("work-1")
        .setScriptId("script-1")
        .setResultCommandId("auto-1")
        .setResultErrorCode("RATE_LIMIT")
        .setResultCommandExecutionOutcome("APPLIED")
        .setResultCommandGameplayResult("SUCCESS")
        .setResultMessage("Target region rejected the remote gameplay command")
        .setPluginPublication(
            PluginPublicationLink.newBuilder()
                .setPluginVersionId("plugin-v1")
                .setPublicationId(31L)
                .setPublicationState(
                    net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                        .VERSION_LIFECYCLE_STATE_PUBLISHED)
                .setLastChangedAtMs(Instant.parse("2026-07-02T01:00:02Z").toEpochMilli())
                .build())
        .setOriginDeadlineRegionEpoch(3L)
        .setOriginDeadlineTickId(88L)
        .setLateResultPolicy("late_result_safe_to_ignore")
        .setOriginGameInstanceId("7")
        .setTargetGameInstanceId("9")
        .setTargetEntityId("npc-7")
        .setEffectKey("remote-followup:dispatch-1")
        .setPayloadKind("trigger_script_event")
        .setRequiresSoloTick(true)
        .setOriginSourceKind("REMOTE_FOLLOWUP")
        .setOriginSourceState("SCHEDULED")
        .setOriginSourceOrdinal(15L)
        .setOriginSourceDueTickId(44L)
        .setOriginSourceDueAtMs(1714521600000L)
        .setFailureCode("REMOTE_REJECTED")
        .setFailureMessage("Target runtime rejected the remote followup")
        .setEventType("onEnterRegion")
        .setEventSchemaVersion("v1")
        .setScriptEventId("remote-enter-1")
        .setTriggerMode("DIRECT")
        .setReadSnapshotToken("game-session:onEnterRegion:7:3:remote-enter-1")
        .setEventPayloadJson("{\"fromRegionId\":\"room-a\",\"toRegionId\":\"room-b\"}")
        .setClaimTargetAggregate("entity:npc-7")
        .setCurrentOriginRuntimeRegionId("region-origin-current")
        .setCurrentOriginRuntimeRegionEpoch(13L)
        .setCurrentTargetRuntimeRegionId("region-target-current")
        .setCurrentTargetRuntimeRegionEpoch(14L)
        .setQueueSourceKind("REMOTE_FOLLOWUP")
        .setQueueSourceState("TARGET_REGION_CLAIMED")
        .setQueueSourceOrdinal(3L)
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
