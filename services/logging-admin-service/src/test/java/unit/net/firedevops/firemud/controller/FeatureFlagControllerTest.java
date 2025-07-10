package net.firedevops.firemud.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.firedevops.firemud.dto.FeatureFlagDto;
import net.firedevops.firemud.dto.ToggleFeatureFlagRequest;
import net.firedevops.firemud.service.FeatureFlagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FeatureFlagController.class)
class FeatureFlagControllerTest {

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private FeatureFlagService service;

  @Test
  void toggleReturnsDto() throws Exception {
    ToggleFeatureFlagRequest request = new ToggleFeatureFlagRequest(1L, "demo", true);
    FeatureFlagDto dto = new FeatureFlagDto(1L, 1L, "demo", true);
    when(service.toggleFlag(request)).thenReturn(dto);

    mockMvc
        .perform(
            post("/feature-flags/toggle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.enabled").value(true));
  }
}
