package net.firedevops.firemud.automationscripting.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.firedevops.firemud.automationscripting.service.FactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FactionController.class)
class FactionControllerTest {
  @Autowired private MockMvc mockMvc;

  @MockitoBean private FactionService factionService;

  @Test
  void adjustReputationReturnsValue() throws Exception {
    when(factionService.adjustReputation(1L, 2L, 3L, 5)).thenReturn(5);

    mockMvc
        .perform(
            patch("/factions/3/reputation")
                .param("characterId", "2")
                .param("delta", "5")
                .param("tenantId", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(5));
  }
}
