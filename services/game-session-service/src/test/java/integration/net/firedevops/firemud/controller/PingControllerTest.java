package net.firedevops.firemud.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.firedevops.firemud.service.PingService;
import org.lognet.springboot.grpc.GRpcServerRunner;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@GameSessionIntegrationTest
class PingControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private PingService pingService;
  @MockBean private GRpcServerRunner grpcServerRunner;

  @Test
  void pingReturnsApiResponse() throws Exception {
    Mockito.when(pingService.ping()).thenReturn("pong");

    mockMvc
        .perform(get("/ping"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data").value("pong"))
        .andExpect(jsonPath("$.error").value(nullValue()));
  }
}
