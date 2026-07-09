package net.firedevops.firemud.loggingadmin.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.RuntimeOwnershipStatusDto;
import net.firedevops.firemud.loggingadmin.dto.TickRemediationActionDto;
import net.firedevops.firemud.loggingadmin.dto.TickRemediationRequest;
import net.firedevops.firemud.loggingadmin.service.TickRemediationService;
import net.firedevops.firemud.test.WithFiremudPrivilegedHttpAuthTestProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(TickRemediationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@WithFiremudPrivilegedHttpAuthTestProperties
class TickRemediationControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private TickRemediationService tickRemediationService;

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void getRuntimeOwnershipStatusReturnsStatusDto() throws Exception {
    when(tickRemediationService.getRuntimeOwnershipStatus(1L, "7", null))
        .thenReturn(
            new RuntimeOwnershipStatusDto(
                1L,
                7L,
                3L,
                "fence-3",
                "game-session-service",
                "gs-1",
                false,
                "tb-9",
                java.time.Instant.parse("2026-04-20T00:00:00Z"),
                14L,
                "region-7",
                3L,
                2L,
                13L,
                2000L));
    String token =
        jwtUtil.generateToken(
            "42", Map.of("accountId", "42", "globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/tick-remediation/status/1")
                .queryParam("gameInstanceId", "7")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.gameInstanceId").value(7))
        .andExpect(jsonPath("$.data.regionId").value("region-7"))
        .andExpect(jsonPath("$.data.pendingGameplayCommandCount").value(3));
  }

  @Test
  void getRuntimeOwnershipStatusRejectsCrossTenantScopedAdmin() throws Exception {
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/tick-remediation/status/1")
                .queryParam("gameInstanceId", "7")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void getRuntimeOwnershipStatusRejectsMalformedTenantIdBeforeDispatch() throws Exception {
    String token =
        jwtUtil.generateToken(
            "42", Map.of("accountId", "42", "globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/tick-remediation/status/not-a-number")
                .queryParam("gameInstanceId", "7")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be numeric"));

    verifyNoInteractions(tickRemediationService);
  }

  @Test
  void getRuntimeOwnershipStatusRejectsMalformedGameInstanceIdBeforeDispatch() throws Exception {
    String token =
        jwtUtil.generateToken(
            "42", Map.of("accountId", "42", "globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/tick-remediation/status/1")
                .queryParam("gameInstanceId", "bad-instance")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("gameInstanceId must be numeric"));

    verifyNoInteractions(tickRemediationService);
  }

  @Test
  void pauseReturnsActionDto() throws Exception {
    TickRemediationRequest request = new TickRemediationRequest(1L, "7", null, "maintenance");
    when(tickRemediationService.pauseTicksForScope(request))
        .thenReturn(
            new TickRemediationActionDto(1L, "game_instance", "7", "pause", "42", "maintenance"));
    String token =
        jwtUtil.generateToken(
            "42", Map.of("accountId", "42", "globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            post("/tick-remediation/pause")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.action").value("pause"))
        .andExpect(jsonPath("$.data.scopeType").value("game_instance"));
  }

  @Test
  void resumeRejectsCrossTenantScopedAdmin() throws Exception {
    TickRemediationRequest request = new TickRemediationRequest(1L, "7", null, "maintenance");
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            post("/tick-remediation/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void pauseRejectsZeroGameInstanceIdBeforeDispatch() throws Exception {
    TickRemediationRequest request = new TickRemediationRequest(1L, "0", null, "maintenance");
    String token =
        jwtUtil.generateToken(
            "42", Map.of("accountId", "42", "globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            post("/tick-remediation/pause")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("gameInstanceId must be positive"));

    verifyNoInteractions(tickRemediationService);
  }
}
