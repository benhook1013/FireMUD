package net.firedevops.firemud.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.firedevops.firemud.config.GameLogicGrpcClientConfig;
import net.firedevops.firemud.service.LookAggregationService;
import net.firedevops.firemud.service.PingService;
import net.firedevops.firemud.service.SayAggregationService;
import org.junit.jupiter.api.Test;
import org.lognet.springboot.grpc.GRpcServerRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PingController.class)
class PingControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private GameLogicGrpcClientConfig gameLogicGrpcClientConfig;
  @MockitoBean private GRpcServerRunner grpcServerRunner;
  @MockitoBean private LookAggregationService lookAggregationService;
  @MockitoBean private PingService pingService;
  @MockitoBean private SayAggregationService sayAggregationService;

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
