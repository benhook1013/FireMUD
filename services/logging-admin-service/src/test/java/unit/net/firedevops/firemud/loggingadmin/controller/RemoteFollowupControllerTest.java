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
  void listRemoteFollowupsReturnsCanonicalRows() throws Exception {
    when(remoteFollowupService.listRemoteFollowups(
            org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.any()))
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
  }

  private RemoteFollowupDto remoteFollowupDto() {
    return new RemoteFollowupDto(
        "rf-1",
        2L,
        7L,
        "region-a",
        3L,
        9L,
        "region-b",
        4L,
        55L,
        "damage:1",
        "entity-9",
        "SCHEDULED",
        "tick-batch-7",
        "{\"kind\":\"enqueue_automation_command\",\"command\":\"LOOK\"}",
        "NONE",
        "ok",
        Instant.parse("2026-07-02T00:00:00Z"),
        Instant.parse("2026-07-02T00:00:01Z"),
        2L,
        "patch-1",
        "plugin-1",
        "plugin-v1",
        new RemoteFollowupDto.ScriptPatchPublicationLinkDto(
            "patch-1",
            17L,
            7L,
            "VERSION_LIFECYCLE_STATE_PUBLISHED",
            Instant.parse("2026-07-02T00:00:02Z"),
            null,
            null),
        "PLAYABLE_STATE_SCOPE_ISOLATED",
        "ops",
        "preview",
        29L,
        "cmd-1",
        "dispatch-1",
        "work-1",
        "script-1",
        "enqueue_automation_command",
        "LOOK",
        "target-cmd-1",
        "APPLIED",
        "SUCCESS",
        new RemoteFollowupDto.PluginPublicationLinkDto(
            "plugin-v1",
            31L,
            "VERSION_LIFECYCLE_STATE_PUBLISHED",
            null,
            Instant.parse("2026-07-02T00:00:03Z"),
            null,
            null),
        true,
        "REMOTE_FOLLOWUP",
        "TARGET_REGION_EXECUTED",
        44L,
        55L,
        1700L,
        3L,
        88L,
        "late_result_safe_to_ignore",
        "onEnterRegion",
        "v1",
        "evt-1",
        "TRIGGER_MODE_CATCH_UP",
        "game-session:onEnterRegion:9:8:evt-1",
        "{\"fromRegionId\":\"room-a\",\"toRegionId\":\"room-b\"}",
        "entity:entity-9",
        "region-origin-current",
        13L,
        "region-target-current",
        14L,
        "REMOTE_FOLLOWUP",
        "TARGET_REGION_CLAIMED",
        2L,
        55L,
        1700L,
        7L,
        9L,
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
