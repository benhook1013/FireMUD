package net.firedevops.firemud.loggingadmin.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.loggingadmin.dto.SagaInstanceDto;
import net.firedevops.firemud.loggingadmin.dto.SagaStepDto;
import net.firedevops.firemud.loggingadmin.service.SagaDashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SagaDashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@TestPropertySource(
    properties = {
      "firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234",
      "firemud.auth.jwt-expiration-ms=3600000"
    })
class SagaDashboardControllerTest {

  @Autowired private MockMvc mockMvc;
  private final JwtUtil jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 3600000);

  @MockitoBean private SagaDashboardService service;

  @Test
  void listInstancesReturnsData() throws Exception {
    when(service.listInstances())
        .thenReturn(List.of(new SagaInstanceDto(1L, "demo", "DONE", null, null)));

    mockMvc
        .perform(
            get("/sagas")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer "
                        + jwtUtil.generateToken(
                            "user",
                            java.util.Map.of("globalRoles", java.util.List.of("platformAdmin")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].sagaName").value("demo"));
  }

  @Test
  void listStepsReturnsData() throws Exception {
    when(service.listSteps(1L))
        .thenReturn(List.of(new SagaStepDto(1L, 1L, "step", "OK", 0, null, null)));

    mockMvc
        .perform(
            get("/sagas/1/steps")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer "
                        + jwtUtil.generateToken(
                            "user",
                            java.util.Map.of("globalRoles", java.util.List.of("platformAdmin")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("step"));
  }
}
