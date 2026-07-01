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
import net.firedevops.firemud.loggingadmin.dto.GameSessionPinConvergenceDto;
import net.firedevops.firemud.loggingadmin.dto.PinnedScriptPatchVersionDto;
import net.firedevops.firemud.loggingadmin.service.GameSessionPinService;
import net.firedevops.firemud.test.WithFiremudPrivilegedHttpAuthTestProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GameSessionPinController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@WithFiremudPrivilegedHttpAuthTestProperties
class GameSessionPinControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;

  @MockitoBean private GameSessionPinService gameSessionPinService;

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void getPinnedScriptPatchVersionReturnsPinMetadata() throws Exception {
    when(gameSessionPinService.getPinnedScriptPatchVersion(1L, 7L)).thenReturn(pinnedVersionDto());
    String token =
        jwtUtil.generateToken(
            "42", Map.of("accountId", "42", "globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(get("/game-session-pins/1/7").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pinnedScriptPatchVersion").value("patch-9"))
        .andExpect(jsonPath("$.data.publication.versionId").value(17));
  }

  @Test
  void getGameSessionPinConvergenceReturnsConvergenceMetadata() throws Exception {
    when(gameSessionPinService.getGameSessionPinConvergence(1L, 7L))
        .thenReturn(pinConvergenceDto());
    String token =
        jwtUtil.generateToken(
            "42", Map.of("accountId", "42", "globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/game-session-pins/1/7/convergence")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.gameInstanceId").value(7))
        .andExpect(jsonPath("$.data.stale").value(true))
        .andExpect(jsonPath("$.data.publication.scriptPatchVersion").value("patch-9"));
  }

  @Test
  void getPinnedScriptPatchVersionRejectsCrossTenantScopedAdmin() throws Exception {
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(get("/game-session-pins/1/7").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  private PinnedScriptPatchVersionDto pinnedVersionDto() {
    return new PinnedScriptPatchVersionDto(
        "patch-9",
        Instant.parse("2026-04-22T00:00:00Z"),
        "operator-1",
        "req-99",
        new PinnedScriptPatchVersionDto.ScriptPatchPublicationLinkDto(
            "patch-9",
            17L,
            7L,
            "VERSION_LIFECYCLE_STATE_PUBLISHED",
            Instant.parse("2026-04-22T00:00:01Z"),
            "",
            ""));
  }

  private GameSessionPinConvergenceDto pinConvergenceDto() {
    return new GameSessionPinConvergenceDto(
        1L,
        7L,
        "patch-9",
        "req-99",
        Instant.parse("2026-04-22T00:00:00Z"),
        true,
        new PinnedScriptPatchVersionDto.ScriptPatchPublicationLinkDto(
            "patch-9",
            17L,
            7L,
            "VERSION_LIFECYCLE_STATE_PUBLISHED",
            Instant.parse("2026-04-22T00:00:01Z"),
            "",
            ""));
  }
}
