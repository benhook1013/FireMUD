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
import net.firedevops.firemud.loggingadmin.dto.RemoteCommandCoordinatorDto;
import net.firedevops.firemud.loggingadmin.service.RemoteCommandCoordinatorService;
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

@WebMvcTest(RemoteCommandCoordinatorController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@WithFiremudJwtTestProperties
class RemoteCommandCoordinatorControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;

  @MockitoBean private RemoteCommandCoordinatorService remoteCommandCoordinatorService;

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void getRemoteCommandCoordinatorReturnsCanonicalStatus() throws Exception {
    when(remoteCommandCoordinatorService.getRemoteCommandCoordinator(2L, "coord-123"))
        .thenReturn(remoteCommandCoordinatorDto());
    SessionContext.setContext("user", List.of("platformAdmin"), Map.of());
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/remote-command-coordinators/2/coord-123")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.coordinatorId").value("coord-123"))
        .andExpect(jsonPath("$.data.followupId").value("follow-1"))
        .andExpect(jsonPath("$.data.currentTargetRuntimeGameInstanceId").value(8))
        .andExpect(jsonPath("$.data.targetRoutingBundleStale").value(true));
  }

  @Test
  void getRemoteCommandCoordinatorRejectsCrossTenantScopedAdmin() throws Exception {
    SessionContext.setContext("user", List.of(), Map.of("8", List.of("tenantAdmin")));
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            get("/remote-command-coordinators/2/coord-123")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  private RemoteCommandCoordinatorDto remoteCommandCoordinatorDto() {
    return new RemoteCommandCoordinatorDto(
        "coord-123",
        2L,
        "cmd-123",
        "follow-1",
        7L,
        "origin-region",
        21L,
        8L,
        "target-region",
        22L,
        44L,
        23L,
        24L,
        "PENDING_REMOTE",
        "DROP_STALE",
        "PENDING_REMOTE",
        "PENDING_REMOTE",
        Instant.parse("2026-07-01T00:00:00Z"),
        "CLAIMED",
        91L,
        "SUCCEEDED",
        "{\"ok\":true}",
        Instant.parse("2026-07-01T00:00:01Z"),
        2L,
        "patch-9",
        "plugin-a",
        "plugin-v1",
        new RemoteCommandCoordinatorDto.ScriptPatchPublicationLinkDto(
            "patch-9",
            17L,
            7L,
            "VERSION_LIFECYCLE_STATE_PUBLISHED",
            Instant.parse("2026-07-01T00:00:02Z"),
            null,
            null),
        "PLAYABLE_STATE_SCOPE_SHARED",
        "demo",
        "production",
        8L,
        "dispatch-1",
        "work-1",
        "script-1",
        "enqueue_gameplay_command",
        "look north",
        "target-cmd-1",
        "NONE",
        "target-cmd-1",
        "SUCCEEDED",
        "OK",
        null,
        new RemoteCommandCoordinatorDto.PluginPublicationLinkDto(
            "plugin-v1",
            88L,
            "VERSION_LIFECYCLE_STATE_PUBLISHED",
            null,
            Instant.parse("2026-07-01T00:00:03Z"),
            null,
            null),
        false,
        "AUTOMATION",
        "SCHEDULED",
        25L,
        26L,
        27L,
        "entity-8",
        "effect-1",
        null,
        null,
        "onCommand",
        "v1",
        "event-1",
        "DIRECT",
        "snapshot-1",
        "{\"target\":8}",
        "entity:8",
        "origin-region-current",
        31L,
        "target-region-current",
        32L,
        "REMOTE_FOLLOWUP",
        "TARGET_REGION_CLAIMED",
        4L,
        45L,
        46L,
        7L,
        8L,
        "PLAYABLE_STATE_SCOPE_SHARED",
        "demo",
        "production",
        9L,
        "PLAYABLE_STATE_SCOPE_SHARED",
        "demo",
        "production",
        10L,
        false,
        true);
  }
}
