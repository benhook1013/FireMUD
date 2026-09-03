package net.firedevops.firemud.loggingadmin.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.GlobalExceptionHandler;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.loggingadmin.dto.SagaInstanceDto;
import net.firedevops.firemud.loggingadmin.dto.SagaStepDto;
import net.firedevops.firemud.loggingadmin.service.SagaDashboardService;
import net.firedevops.firemud.test.WithFiremudJwtTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SagaDashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
  CommonSecurityAutoConfiguration.class,
  CommonSecurityServletAutoConfiguration.class,
  GlobalExceptionHandler.class
})
@WithFiremudJwtTestProperties
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

  @Test
  void listStepsRejectsMalformedIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(
            get("/sagas/not-a-number/steps")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer "
                        + jwtUtil.generateToken(
                            "user",
                            java.util.Map.of("globalRoles", java.util.List.of("platformAdmin")))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("id must be numeric"));

    verifyNoInteractions(service);
  }

  @Test
  void listStepsRejectsZeroIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(
            get("/sagas/0/steps")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer "
                        + jwtUtil.generateToken(
                            "user",
                            java.util.Map.of("globalRoles", java.util.List.of("platformAdmin")))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("id must be positive"));

    verifyNoInteractions(service);
  }

  @Test
  void listStepsReturnsNotFoundForUnknownSagaInstance() throws Exception {
    when(service.listSteps(404L))
        .thenThrow(
            new org.springframework.web.server.ResponseStatusException(
                HttpStatus.NOT_FOUND, "Saga instance not found"));

    mockMvc
        .perform(
            get("/sagas/404/steps")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer "
                        + jwtUtil.generateToken(
                            "user",
                            java.util.Map.of("globalRoles", java.util.List.of("platformAdmin")))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.error.message").value("Saga instance not found"));
  }

  @Test
  void unavailableDashboardReturns503() {
    org.springframework.beans.factory.ObjectProvider<SagaDashboardService> provider =
        org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(null);
    SagaDashboardController unavailable = new SagaDashboardController(provider);

    ResponseEntity<?> response = unavailable.listInstances();

    org.junit.jupiter.api.Assertions.assertEquals(503, response.getStatusCode().value());
    net.firedevops.firemud.common.ApiResponse<?> body =
        (net.firedevops.firemud.common.ApiResponse<?>) Objects.requireNonNull(response.getBody());
    org.junit.jupiter.api.Assertions.assertEquals(
        "SAGA_DASHBOARD_UNAVAILABLE", body.error().code());
  }

  @Test
  void unavailableDashboardReturns503ForSteps() {
    org.springframework.beans.factory.ObjectProvider<SagaDashboardService> provider =
        org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(null);
    SagaDashboardController unavailable = new SagaDashboardController(provider);

    ResponseEntity<?> response = unavailable.listSteps("1");

    org.junit.jupiter.api.Assertions.assertEquals(503, response.getStatusCode().value());
    net.firedevops.firemud.common.ApiResponse<?> body =
        (net.firedevops.firemud.common.ApiResponse<?>) Objects.requireNonNull(response.getBody());
    org.junit.jupiter.api.Assertions.assertEquals(
        "SAGA_DASHBOARD_UNAVAILABLE", body.error().code());
  }
}
