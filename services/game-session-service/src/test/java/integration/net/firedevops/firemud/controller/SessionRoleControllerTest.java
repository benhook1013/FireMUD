package net.firedevops.firemud.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.firedevops.firemud.service.SessionRoleService;
import org.junit.jupiter.api.Test;
import org.lognet.springboot.grpc.GRpcServerRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@GameSessionIntegrationTest
class SessionRoleControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockBean private SessionRoleService sessionRoleService;
  @MockBean private GRpcServerRunner grpcServerRunner;

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
