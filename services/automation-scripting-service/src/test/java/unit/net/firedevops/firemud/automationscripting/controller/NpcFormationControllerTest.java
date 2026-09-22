package net.firedevops.firemud.automationscripting.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import net.firedevops.firemud.automationscripting.service.NpcFormationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NpcFormationController.class)
class NpcFormationControllerTest {
  @Autowired private MockMvc mockMvc;

  @MockitoBean private NpcFormationService formationService;

  @Test
  void addMemberReturnsSuccess() throws Exception {
    mockMvc
        .perform(post("/formations/7/members").param("tenantId", "1").param("npcId", "9"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(true));
  }

  @Test
  void createFormationRejectsZeroLeaderNpcIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(
            post("/formations")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "tenantId": 1,
                      "name": "demo",
                      "leaderNpcId": 0,
                      "formationType": "LINE"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("leaderNpcId must be positive"));

    verifyNoInteractions(formationService);
  }

  @Test
  void addMemberRejectsMalformedFormationIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(
            post("/formations/not-a-number/members").param("tenantId", "1").param("npcId", "9"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("formationId must be numeric"));

    verifyNoInteractions(formationService);
  }

  @Test
  void listMembersRejectsZeroTenantIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(get("/formations/7/members").param("tenantId", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be positive"));

    verifyNoInteractions(formationService);
  }

  @Test
  void listMembersReturnsValues() throws Exception {
    when(formationService.getMembers(1L, 7L)).thenReturn(List.of(9L, 10L));

    mockMvc
        .perform(get("/formations/7/members").param("tenantId", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0]").value(9))
        .andExpect(jsonPath("$.data[1]").value(10));
  }
}
