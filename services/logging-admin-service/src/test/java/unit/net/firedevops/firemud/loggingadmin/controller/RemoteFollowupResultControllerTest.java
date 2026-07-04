package net.firedevops.firemud.loggingadmin.controller;

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
import net.firedevops.firemud.loggingadmin.dto.RemoteFollowupResultDto;
import net.firedevops.firemud.loggingadmin.service.RemoteFollowupResultService;
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

@WebMvcTest(RemoteFollowupResultController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@WithFiremudJwtTestProperties
class RemoteFollowupResultControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;

  @MockitoBean private RemoteFollowupResultService remoteFollowupResultService;

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void getRemoteFollowupResultReturnsCanonicalRow() throws Exception {
    when(remoteFollowupResultService.getRemoteFollowupResult(2L, "rr-1"))
        .thenReturn(remoteResultDto());
    SessionContext.setContext("user", List.of("platformAdmin"), Map.of());
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/remote-followup-results/2/rr-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resultId").value("rr-1"))
        .andExpect(jsonPath("$.data.resultCommandId").value("auto-1"))
        .andExpect(jsonPath("$.data.currentTargetRuntimeGameInstanceId").value(9))
        .andExpect(jsonPath("$.data.targetRoutingBundleStale").value(true));
  }

  @Test
  void listRemoteFollowupResultsReturnsCanonicalRows() throws Exception {
    when(remoteFollowupResultService.listRemoteFollowupResults(
            org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(remoteResultDto()));
    SessionContext.setContext("user", List.of("platformAdmin"), Map.of());
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/remote-followup-results/2")
                .param("outcome", "REMOTE_APPLIED")
                .param("payloadKind", "trigger_script_event")
                .param("worldSlug", "demo")
                .param("realmSlug", "production")
                .param("pointerVersion", "17")
                .param("limit", "10")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].resultId").value("rr-1"))
        .andExpect(jsonPath("$.data[0].resultCommandId").value("auto-1"))
        .andExpect(jsonPath("$.data[0].currentTargetRuntimeGameInstanceId").value("9"))
        .andExpect(jsonPath("$.data[0].targetRoutingBundleStale").value(true));
  }

  @Test
  void listRemoteFollowupResultsRejectsCrossTenantScopedAdmin() throws Exception {
    SessionContext.setContext("user", List.of(), Map.of("8", List.of("tenantAdmin")));
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            get("/remote-followup-results/2").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  private RemoteFollowupResultDto remoteResultDto() {
    return new RemoteFollowupResultDto(
        "rr-1",
        2L,
        "coord-1",
        "rf-1",
        "region-a",
        3L,
        "region-b",
        4L,
        "REMOTE_APPLIED",
        "{\"commandId\":\"payload-cmd\",\"errorCode\":\"payload-error\",\"message\":\"payload message\"}",
        Instant.parse("2026-07-02T01:00:00Z"),
        "patch-1",
        "plugin-1",
        "plugin-v1",
        new RemoteFollowupResultDto.ScriptPatchPublicationLinkDto(
            "patch-1",
            17L,
            7L,
            "VERSION_LIFECYCLE_STATE_PUBLISHED",
            Instant.parse("2026-07-02T01:00:01Z"),
            null,
            null),
        "PLAYABLE_STATE_SCOPE_SHARED",
        "demo",
        "production",
        17L,
        "cmd-1",
        "dispatch-1",
        "work-1",
        "script-1",
        "auto-1",
        "RATE_LIMIT",
        "APPLIED",
        "SUCCESS",
        "Target region rejected the remote gameplay command",
        new RemoteFollowupResultDto.PluginPublicationLinkDto(
            "plugin-v1",
            31L,
            "VERSION_LIFECYCLE_STATE_PUBLISHED",
            null,
            Instant.parse("2026-07-02T01:00:02Z"),
            null,
            null),
        3L,
        88L,
        "late_result_safe_to_ignore",
        "7",
        "9",
        "npc-7",
        "remote-followup:dispatch-1",
        "trigger_script_event",
        true,
        "REMOTE_FOLLOWUP",
        "SCHEDULED",
        15L,
        44L,
        1714521600000L,
        "REMOTE_REJECTED",
        "Target runtime rejected the remote followup",
        "onEnterRegion",
        "v1",
        "remote-enter-1",
        "DIRECT",
        "game-session:onEnterRegion:7:3:remote-enter-1",
        "{\"fromRegionId\":\"room-a\",\"toRegionId\":\"room-b\"}",
        "entity:npc-7",
        "region-origin-current",
        13L,
        "region-target-current",
        14L,
        "REMOTE_FOLLOWUP",
        "TARGET_REGION_CLAIMED",
        3L,
        55L,
        1700L,
        "7",
        "9",
        "PLAYABLE_STATE_SCOPE_SHARED",
        "world-7",
        "realm-7",
        107L,
        "PLAYABLE_STATE_SCOPE_SHARED",
        "world-9",
        "realm-9",
        109L,
        true,
        true);
  }
}
