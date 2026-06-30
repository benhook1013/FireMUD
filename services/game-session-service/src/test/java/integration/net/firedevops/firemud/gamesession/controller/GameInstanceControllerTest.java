package net.firedevops.firemud.gamesession.controller;

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
            1L, 1L, "11", null, 7L, "ld-1", 11L, 77L, 77L, "genrev-11", 1L, "RUNNING");
    org.mockito.Mockito.when(
            gameInstanceService.startSession(
                org.mockito.ArgumentMatchers.any(StartSessionRequest.class),
                org.mockito.ArgumentMatchers.eq(false)))
        .thenReturn(dto);
    StartSessionRequest request = new StartSessionRequest(1L, 7L, "cp-1", 1L);
    mockMvc
        .perform(
            post("/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + privilegedToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(1));
  }

  @Test
  void stopSessionReturnsDto() throws Exception {
    GameInstanceDto dto =
        new GameInstanceDto(
            1L, 1L, "11", null, 7L, "ld-1", 11L, 77L, 77L, "genrev-11", 1L, "STOPPED");
    org.mockito.Mockito.when(gameInstanceService.stopSession(1L)).thenReturn(dto);
    mockMvc
        .perform(
            post("/sessions/1/stop")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + privilegedToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("STOPPED"));
  }

  @Test
  void restartSessionReturnsDto() throws Exception {
    GameInstanceDto dto =
        new GameInstanceDto(
            1L, 1L, "11", null, 7L, "ld-1", 11L, 77L, 77L, "genrev-11", 1L, "RUNNING");
    org.mockito.Mockito.when(gameInstanceService.restartSession(1L)).thenReturn(dto);
    mockMvc
        .perform(
            post("/sessions/1/restart")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + privilegedToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("RUNNING"));
  }

  @Test
  void startSessionRejectsScopedTenantAdmin() throws Exception {
    StartSessionRequest request = new StartSessionRequest(1L, 7L, "cp-1", 1L);
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("1", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            post("/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void stopSessionRejectsUnauthenticatedCaller() throws Exception {
    mockMvc.perform(post("/sessions/1/stop")).andExpect(status().isUnauthorized());
  }

  private String privilegedToken() {
    return jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));
  }
}
