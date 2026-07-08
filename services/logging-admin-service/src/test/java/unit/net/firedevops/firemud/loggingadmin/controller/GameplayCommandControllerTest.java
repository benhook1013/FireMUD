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
import net.firedevops.firemud.loggingadmin.dto.GameplayCommandStatusDto;
import net.firedevops.firemud.loggingadmin.service.GameplayCommandStatusService;
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

@WebMvcTest(GameplayCommandController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@WithFiremudJwtTestProperties
class GameplayCommandControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;

  @MockitoBean private GameplayCommandStatusService gameplayCommandStatusService;

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void getGameplayCommandStatusReturnsCanonicalStatus() throws Exception {
    when(gameplayCommandStatusService.getGameplayCommandStatus(2L, "cmd-123"))
        .thenReturn(gameplayCommandStatusDto());
    SessionContext.setContext("user", List.of("platformAdmin"), Map.of());
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/gameplay-commands/2/cmd-123")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.commandId").value("cmd-123"))
        .andExpect(jsonPath("$.data.tenantId").value(2))
        .andExpect(jsonPath("$.data.publication.versionId").value(17))
        .andExpect(jsonPath("$.data.currentRuntimeRoutingBundleStale").value(true));
  }

  @Test
  void getGameplayCommandStatusRejectsCrossTenantScopedAdmin() throws Exception {
    SessionContext.setContext("user", List.of(), Map.of("8", List.of("tenantAdmin")));
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            get("/gameplay-commands/2/cmd-123")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void getGameplayCommandStatusRejectsZeroTenantIdBeforeDispatch() throws Exception {
    SessionContext.setContext("user", List.of("platformAdmin"), Map.of());
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/gameplay-commands/0/cmd-123")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be positive"));

    verifyNoInteractions(gameplayCommandStatusService);
  }

  private GameplayCommandStatusDto gameplayCommandStatusDto() {
    return new GameplayCommandStatusDto(
        "cmd-123",
        2L,
        7L,
        11L,
        42L,
        99L,
        "LOOK",
        "look",
        false,
        "SUCCEEDED",
        "OK",
        Instant.parse("2026-06-30T00:00:00Z"),
        Instant.parse("2026-06-30T00:00:01Z"),
        Instant.parse("2026-06-30T00:00:02Z"),
        Instant.parse("2026-06-30T00:00:03Z"),
        2,
        null,
        null,
        "PLAYER",
        "dispatch-1",
        "work-1",
        "script-1",
        "patch-9",
        "plugin-a",
        "plugin-v1",
        "entity-7",
        "region-7",
        22L,
        44L,
        55L,
        "PLAYABLE_STATE_SCOPE_SHARED",
        "demo",
        "production",
        8L,
        "AUTOMATION",
        "SCHEDULED",
        12L,
        13L,
        14L,
        "REMOTE_FOLLOWUP_QUEUE",
        "CLAIMED",
        15L,
        16L,
        17L,
        "coord-1",
        "follow-1",
        "COMPLETED",
        "SUCCEEDED",
        "{\"ok\":true}",
        Instant.parse("2026-06-30T00:00:04Z"),
        new GameplayCommandStatusDto.ScriptPatchPublicationLinkDto(
            "patch-9",
            17L,
            7L,
            "VERSION_LIFECYCLE_STATE_PUBLISHED",
            Instant.parse("2026-06-30T00:00:05Z"),
            null,
            null),
        "remote-cmd-1",
        "NONE",
        null,
        new GameplayCommandStatusDto.PluginPublicationLinkDto(
            "plugin-v1",
            88L,
            "VERSION_LIFECYCLE_STATE_PUBLISHED",
            null,
            Instant.parse("2026-06-30T00:00:06Z"),
            null,
            null),
        7L,
        "origin-region",
        21L,
        8L,
        "target-region",
        22L,
        23L,
        24L,
        "DROP_STALE",
        "SUCCEEDED",
        "OK",
        "COMPLETED",
        "enqueue_gameplay_command",
        "look north",
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
        "entity:8",
        "current-region",
        28L,
        7L,
        "PLAYABLE_STATE_SCOPE_SHARED",
        "demo",
        "production",
        9L,
        true);
  }
}
