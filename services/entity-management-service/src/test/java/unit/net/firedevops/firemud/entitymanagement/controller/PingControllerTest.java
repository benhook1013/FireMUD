package net.firedevops.firemud.entitymanagement.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.security.JwtAuthInterceptor;
import net.firedevops.firemud.entitymanagement.service.PingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PingController.class)
class PingControllerTest {
  @Autowired private MockMvc mockMvc;

  @MockitoBean private PingService pingService;
  @MockitoBean private JwtAuthInterceptor jwtAuthInterceptor;

  @BeforeEach
  void setUpSecurityContext() throws Exception {
    doAnswer(
            invocation -> {
              SessionContext.setContext("test-account", List.of("platformAdmin"), Map.of());
              return true;
            })
        .when(jwtAuthInterceptor)
        .preHandle(any(), any(), any());
  }

  @AfterEach
  void clearSecurityContext() {
    SessionContext.clear();
  }

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
