package net.firedevops.firemud.gamesession.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.firedevops.firemud.gamesession.command.text.LoginCommandHandler;
import net.firedevops.firemud.gamesession.command.text.LookCommandHandler;
import net.firedevops.firemud.gamesession.command.text.SayCommandHandler;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionRoleService;
import org.junit.jupiter.api.Test;
import org.lognet.springboot.grpc.GRpcServerRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@GameSessionIntegrationTest
class SessionRoleControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private SessionRoleService sessionRoleService;
  @MockitoBean private GRpcServerRunner grpcServerRunner;
  @MockitoBean private LoginCommandHandler loginCommandHandler;
  @MockitoBean private LookCommandHandler lookCommandHandler;
  @MockitoBean private SayCommandHandler sayCommandHandler;
  @MockitoBean private SessionAuthenticationService sessionAuthenticationService;
  @MockitoBean private GameInstanceRepository gameInstanceRepository;

  @Test
  void refreshRolesReturnsOk() throws Exception {
    org.mockito.Mockito.when(
            sessionRoleService.refreshRoles(org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn("refreshed");
    mockMvc
        .perform(post("/sessions/1/refresh-roles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value("refreshed"));
  }
}
