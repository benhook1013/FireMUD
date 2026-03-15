package net.firedevops.firemud.tcpproxy.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.firedevops.firemud.tcpproxy.service.PingService;
import net.firedevops.firemud.tcpproxy.telnet.TelnetServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PingController.class)
class PingControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PingService pingService;
  @MockitoBean private TelnetServer telnetServer;

  @Test
  void pingEndpointReturnsPong() throws Exception {
    when(pingService.ping()).thenReturn("pong");

    mockMvc
        .perform(get("/ping"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data").value("pong"));
  }
}
