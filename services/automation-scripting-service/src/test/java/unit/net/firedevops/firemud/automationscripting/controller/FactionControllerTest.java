package net.firedevops.firemud.automationscripting.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.firedevops.firemud.automationscripting.service.FactionService;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
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
    when(factionService.adjustReputation(
            1L, 2L, "live", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, 3L, 5))
        .thenReturn(5);

    mockMvc
        .perform(
            patch("/factions/3/reputation")
                .param("characterId", "2")
                .param("gameInstanceId", "live")
                .param("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED")
                .param("delta", "5")
                .param("tenantId", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(5));
  }

  @Test
  void adjustReputationRejectsMalformedTenantIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(
            patch("/factions/3/reputation")
                .param("characterId", "2")
                .param("gameInstanceId", "live")
                .param("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED")
                .param("delta", "5")
                .param("tenantId", "bad-tenant"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be numeric"));

    verifyNoInteractions(factionService);
  }

  @Test
  void adjustReputationRejectsZeroCharacterIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(
            patch("/factions/3/reputation")
                .param("characterId", "0")
                .param("gameInstanceId", "live")
                .param("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED")
                .param("delta", "5")
                .param("tenantId", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("characterId must be positive"));

    verifyNoInteractions(factionService);
  }
}
