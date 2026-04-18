package net.firedevops.firemud.socialgroups.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.socialgroups.config.WebConfig;
import net.firedevops.firemud.socialgroups.security.JwtAuthInterceptor;
import net.firedevops.firemud.socialgroups.service.PingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PingController.class)
@Import({CommonSecurityAutoConfiguration.class, WebConfig.class, JwtAuthInterceptor.class})
@TestPropertySource(
    properties = {
      "firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234",
      "firemud.auth.jwt-expiration-ms=3600000"
    })
class PingControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;

  @MockitoBean private PingService pingService;

  @Test
  void pingEndpointReturnsPong() throws Exception {
    when(pingService.ping()).thenReturn("pong");

    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));
    mockMvc
        .perform(get("/ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data").value("pong"));
  }
}
