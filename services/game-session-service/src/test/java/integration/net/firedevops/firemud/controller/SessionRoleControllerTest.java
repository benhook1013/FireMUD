package net.firedevops.firemud.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.firedevops.firemud.service.SessionRoleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SessionRoleController.class)
@AutoConfigureMockMvc
class SessionRoleControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockBean private SessionRoleService sessionRoleService;

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
