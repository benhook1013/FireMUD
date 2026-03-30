package net.firedevops.firemud.gamelogic.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.firedevops.firemud.gamelogic.config.GameLogicGrpcClientConfig;
import net.firedevops.firemud.gamelogic.service.CommunicationAggregationService;
import net.firedevops.firemud.gamelogic.service.LookAggregationService;
import net.firedevops.firemud.gamelogic.service.PingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PingController.class)
class PingControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private GameLogicGrpcClientConfig gameLogicGrpcClientConfig;
  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;
  @MockitoBean private LookAggregationService lookAggregationService;
  @MockitoBean private PingService pingService;
  @MockitoBean private CommunicationAggregationService communicationAggregationService;

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
