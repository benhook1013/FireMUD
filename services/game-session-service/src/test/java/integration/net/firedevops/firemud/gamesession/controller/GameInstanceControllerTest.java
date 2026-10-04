package net.firedevops.firemud.gamesession.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.command.text.CommunicationCommandHandler;
import net.firedevops.firemud.gamesession.command.text.LoginCommandHandler;
import net.firedevops.firemud.gamesession.command.text.LookCommandHandler;
import net.firedevops.firemud.gamesession.command.text.MoveCommandHandler;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@GameSessionIntegrationTest
class GameInstanceControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtUtil jwtUtil;

  @MockitoBean private GameInstanceService gameInstanceService;
  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;
  @MockitoBean private LoginCommandHandler loginCommandHandler;
  @MockitoBean private LookCommandHandler lookCommandHandler;
  @MockitoBean private MoveCommandHandler moveCommandHandler;
  @MockitoBean private CommunicationCommandHandler communicationCommandHandler;
  @MockitoBean private SessionAuthenticationService sessionAuthenticationService;
  @MockitoBean private GameInstanceRepository gameInstanceRepository;

  @Test
  void startSessionReturnsDto() throws Exception {
    GameInstanceDto dto =
        new GameInstanceDto(
            1L,
            1L,
            "11",
            null,
            7L,
            "ld-1",
            11L,
            77L,
            77L,
            "genrev-11",
            "123e4567-e89b-12d3-a456-426614174000",
            "RUNNING");
    org.mockito.Mockito.when(
            gameInstanceService.startSession(
                org.mockito.ArgumentMatchers.any(StartSessionRequest.class),
                org.mockito.ArgumentMatchers.eq(false)))
        .thenReturn(dto);
    StartSessionRequest request =
        new StartSessionRequest(1L, 7L, "cp-1", "123e4567-e89b-12d3-a456-426614174000");
    mockMvc
        .perform(
            post("/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + PlatformAdminJwtTestSupport.privilegedToken(jwtUtil)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(1));
  }

  @Test
  void stopSessionReturnsDto() throws Exception {
    GameInstanceDto dto =
        new GameInstanceDto(
            1L,
            1L,
            "11",
            null,
            7L,
            "ld-1",
            11L,
            77L,
            77L,
            "genrev-11",
            "123e4567-e89b-12d3-a456-426614174000",
            "STOPPED");
    org.mockito.Mockito.when(gameInstanceService.stopSession(1L)).thenReturn(dto);
    mockMvc
        .perform(
            post("/sessions/1/stop")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + PlatformAdminJwtTestSupport.privilegedToken(jwtUtil)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("STOPPED"));
  }

  @Test
  void restartSessionReturnsDto() throws Exception {
    GameInstanceDto dto =
        new GameInstanceDto(
            1L,
            1L,
            "11",
            null,
            7L,
            "ld-1",
            11L,
            77L,
            77L,
            "genrev-11",
            "123e4567-e89b-12d3-a456-426614174000",
            "RUNNING");
    org.mockito.Mockito.when(gameInstanceService.restartSession(1L)).thenReturn(dto);
    mockMvc
        .perform(
            post("/sessions/1/restart")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + PlatformAdminJwtTestSupport.privilegedToken(jwtUtil)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("RUNNING"));
  }

  @Test
  void startSessionRejectsScopedTenantAdmin() throws Exception {
    StartSessionRequest request =
        new StartSessionRequest(1L, 7L, "cp-1", "123e4567-e89b-12d3-a456-426614174000");
    String token =
        jwtUtil.generateToken(
            "018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0a",
            Map.of(
                "accountId",
                "018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0a",
                "scopedRoles",
                Map.of("1", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            post("/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void startSessionRejectsBlankOwnerAccountIdBeforeDispatch() throws Exception {
    StartSessionRequest request = new StartSessionRequest(1L, 7L, "cp-1", "");

    mockMvc
        .perform(
            post("/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + PlatformAdminJwtTestSupport.privilegedToken(jwtUtil)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("ownerAccountId must not be blank"));

    verifyNoInteractions(gameInstanceService);
  }

  @Test
  void stopSessionRejectsUnauthenticatedCaller() throws Exception {
    mockMvc.perform(post("/sessions/1/stop")).andExpect(status().isUnauthorized());
  }

  @Test
  void stopSessionRejectsMalformedSessionIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(
            post("/sessions/not-a-number/stop")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + PlatformAdminJwtTestSupport.privilegedToken(jwtUtil)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("sessionId must be numeric"));

    verifyNoInteractions(gameInstanceService);
  }

  @Test
  void restartSessionRejectsZeroSessionIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(
            post("/sessions/0/restart")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + PlatformAdminJwtTestSupport.privilegedToken(jwtUtil)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("sessionId must be positive"));

    verifyNoInteractions(gameInstanceService);
  }
}
