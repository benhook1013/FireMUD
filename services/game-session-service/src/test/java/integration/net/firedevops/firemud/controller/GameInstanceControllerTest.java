package net.firedevops.firemud.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.firedevops.firemud.dto.GameInstanceDto;
import net.firedevops.firemud.dto.StartSessionRequest;
import net.firedevops.firemud.service.GameInstanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GameInstanceController.class)
@AutoConfigureMockMvc
class GameInstanceControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private GameInstanceService gameInstanceService;

  @Test
  void startSessionReturnsDto() throws Exception {
    GameInstanceDto dto = new GameInstanceDto(1L, 1L, "v1", 1L, "RUNNING");
    org.mockito.Mockito.when(gameInstanceService.startSession(org.mockito.ArgumentMatchers.any()))
        .thenReturn(dto);
    StartSessionRequest request = new StartSessionRequest(1L, "v1", 1L);
    mockMvc
        .perform(
            post("/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(1));
  }
}
