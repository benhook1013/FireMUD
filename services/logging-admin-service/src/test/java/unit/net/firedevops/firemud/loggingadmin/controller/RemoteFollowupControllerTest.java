package net.firedevops.firemud.loggingadmin.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupDto;
import net.firedevops.firemud.loggingadmin.service.RemoteFollowupService;
import net.firedevops.firemud.test.WithFiremudJwtTestProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RemoteFollowupController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@WithFiremudJwtTestProperties
class RemoteFollowupControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;

  @MockitoBean private RemoteFollowupService remoteFollowupService;

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void getRemoteFollowupReturnsCanonicalRow() throws Exception {
    when(remoteFollowupService.getRemoteFollowup(2L, "rf-1")).thenReturn(remoteFollowupDto());
    SessionContext.setContext("user", List.of("platformAdmin"), Map.of());
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/remote-followups/2/rf-1").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.followupId").value("rf-1"))
        .andExpect(jsonPath("$.data.targetCommandId").value("target-cmd-1"))
        .andExpect(jsonPath("$.data.currentTargetRuntimeGameInstanceId").value(9))
        .andExpect(jsonPath("$.data.targetRoutingBundleStale").value(true));
  }

  @Test
  void getRemoteFollowupRejectsCrossTenantScopedAdmin() throws Exception {
    SessionContext.setContext("user", List.of(), Map.of("8", List.of("tenantAdmin")));
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            get("/remote-followups/2/rf-1").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());

    verifyNoInteractions(remoteFollowupService);
  }

  @Test
  void listRemoteFollowupsReturnsCanonicalRows() throws Exception {
    when(remoteFollowupService.listRemoteFollowups(
            org.mockito.ArgumentMatchers.eq(2L),
            org.mockito.ArgumentMatchers.argThat(
                request ->
                    "SCHEDULED".equals(request.getStatus())
                        && "enqueue_automation_command".equals(request.getPayloadKind())
                        && "ops".equals(request.getWorldSlug())
                        && "preview".equals(request.getRealmSlug())
                        && Long.valueOf(29L).equals(request.getPointerVersion())
                        && Integer.valueOf(10).equals(request.getLimit()))))
        .thenReturn(List.of(remoteFollowupDto()));
    SessionContext.setContext("user", List.of("platformAdmin"), Map.of());
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/remote-followups/2")
                .param("status", "SCHEDULED")
                .param("payloadKind", "enqueue_automation_command")
                .param("worldSlug", "ops")
                .param("realmSlug", "preview")
                .param("pointerVersion", "29")
                .param("limit", "10")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].followupId").value("rf-1"))
        .andExpect(jsonPath("$.data[0].targetCommandId").value("target-cmd-1"))
        .andExpect(jsonPath("$.data[0].currentTargetRuntimeGameInstanceId").value(9))
        .andExpect(jsonPath("$.data[0].targetRoutingBundleStale").value(true));
  }

  @Test
  void listRemoteFollowupsRejectsCrossTenantScopedAdmin() throws Exception {
    SessionContext.setContext("user", List.of(), Map.of("8", List.of("tenantAdmin")));
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(get("/remote-followups/2").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());

    verifyNoInteractions(remoteFollowupService);
  }

  private RemoteFollowupDto remoteFollowupDto() {
    return RemoteFollowupDto.builder()
        .followupId("rf-1")
        .tenantId(2L)
        .originGameInstanceId(7L)
        .originRegionId("region-a")
        .originRegionEpoch(3L)
        .targetGameInstanceId(9L)
        .targetRegionId("region-b")
        .targetRegionEpoch(4L)
        .dueTickId(55L)
        .effectKey("damage:1")
        .targetEntityId("entity-9")
        .status("SCHEDULED")
        .claimedTickBatchId("tick-batch-7")
        .payloadJson("{\"kind\":\"enqueue_automation_command\",\"command\":\"LOOK\"}")
        .failureCode("NONE")
        .failureMessage("ok")
        .createdAt(Instant.parse("2026-07-02T00:00:00Z"))
        .updatedAt(Instant.parse("2026-07-02T00:00:01Z"))
        .claimOrdinal(2L)
        .scriptPatchVersion("patch-1")
        .pluginId("plugin-1")
        .pluginVersionId("plugin-v1")
        .publication(
            RemoteFollowupDto.ScriptPatchPublicationLinkDto.builder()
                .scriptPatchVersion("patch-1")
                .versionId(17L)
                .baseVersionId(7L)
                .publicationState("VERSION_LIFECYCLE_STATE_PUBLISHED")
                .lastChangedAt(Instant.parse("2026-07-02T00:00:02Z"))
                .build())
        .playableStateScope("PLAYABLE_STATE_SCOPE_ISOLATED")
        .worldSlug("ops")
        .realmSlug("preview")
        .pointerVersion(29L)
        .commandId("cmd-1")
        .automationDispatchId("dispatch-1")
        .automationWorkItemId("work-1")
        .scriptId("script-1")
        .payloadKind("enqueue_automation_command")
        .requestedCommand("LOOK")
        .targetCommandId("target-cmd-1")
        .targetCommandExecutionOutcome("APPLIED")
        .targetCommandGameplayResult("SUCCESS")
        .pluginPublication(
            RemoteFollowupDto.PluginPublicationLinkDto.builder()
                .pluginVersionId("plugin-v1")
                .publicationId(31L)
                .publicationState("VERSION_LIFECYCLE_STATE_PUBLISHED")
                .lastChangedAt(Instant.parse("2026-07-02T00:00:03Z"))
                .build())
        .requiresSoloTick(true)
        .originSourceKind("REMOTE_FOLLOWUP")
        .originSourceState("TARGET_REGION_EXECUTED")
        .originSourceOrdinal(44L)
        .originSourceDueTickId(55L)
        .originSourceDueAtMs(1700L)
        .originDeadlineRegionEpoch(3L)
        .originDeadlineTickId(88L)
        .lateResultPolicy("late_result_safe_to_ignore")
        .eventType("onEnterRegion")
        .eventSchemaVersion("v1")
        .scriptEventId("evt-1")
        .triggerMode("TRIGGER_MODE_CATCH_UP")
        .readSnapshotToken("game-session:onEnterRegion:9:8:evt-1")
        .eventPayloadJson("{\"fromRegionId\":\"room-a\",\"toRegionId\":\"room-b\"}")
        .claimTargetAggregate("entity:entity-9")
        .currentOriginRuntimeRegionId("region-origin-current")
        .currentOriginRuntimeRegionEpoch(13L)
        .currentTargetRuntimeRegionId("region-target-current")
        .currentTargetRuntimeRegionEpoch(14L)
        .queueSourceKind("REMOTE_FOLLOWUP")
        .queueSourceState("TARGET_REGION_CLAIMED")
        .queueSourceOrdinal(2L)
        .queueSourceDueTickId(55L)
        .queueSourceDueAtMs(1700L)
        .currentOriginRuntimeGameInstanceId(7L)
        .currentTargetRuntimeGameInstanceId(9L)
        .currentOriginRuntimePlayableStateScope("PLAYABLE_STATE_SCOPE_SHARED")
        .currentOriginRuntimeWorldSlug("world-7")
        .currentOriginRuntimeRealmSlug("realm-7")
        .currentOriginRuntimePointerVersion(107L)
        .currentTargetRuntimePlayableStateScope("PLAYABLE_STATE_SCOPE_SHARED")
        .currentTargetRuntimeWorldSlug("world-9")
        .currentTargetRuntimeRealmSlug("realm-9")
        .currentTargetRuntimePointerVersion(109L)
        .originRoutingBundleStale(true)
        .targetRoutingBundleStale(true)
        .build();
  }
}
