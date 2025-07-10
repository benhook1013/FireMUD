package net.firedevops.firemud.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import net.firedevops.firemud.dto.SagaInstanceDto;
import net.firedevops.firemud.dto.SagaStepDto;
import net.firedevops.firemud.service.SagaDashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SagaDashboardController.class)
class SagaDashboardControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SagaDashboardService service;

  @Test
  void listInstancesReturnsData() throws Exception {
    when(service.listInstances())
        .thenReturn(List.of(new SagaInstanceDto(1L, "demo", "DONE", null, null)));

    mockMvc
        .perform(get("/sagas"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].sagaName").value("demo"));
  }

  @Test
  void listStepsReturnsData() throws Exception {
    when(service.listSteps(1L))
        .thenReturn(List.of(new SagaStepDto(1L, 1L, "step", "OK", 0, null, null)));

    mockMvc
        .perform(get("/sagas/1/steps"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("step"));
  }
}
