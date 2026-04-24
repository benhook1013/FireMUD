package net.firedevops.firemud.entitymanagement.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.dto.CharacterEquipmentEntryDto;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.service.EquipmentService;
import net.firedevops.firemud.entitymanagement.service.ScopedCharacterResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EquipmentController.class)
class EquipmentControllerTest {
  @Autowired private MockMvc mockMvc;

  @MockitoBean private EquipmentService equipmentService;
  @MockitoBean private ScopedCharacterResolver scopedCharacterResolver;

  @BeforeEach
  void setUpSecurityContext() {
    SessionContext.setContext("test-account", List.of("platformAdmin"), java.util.Map.of());
  }

  @AfterEach
  void clearSecurityContext() {
    SessionContext.clear();
  }

  @Test
  void listReturnsEquipment() throws Exception {
    CharacterEquipmentEntryDto dto =
        new CharacterEquipmentEntryDto(
            1L, 2L, "HEAD", 3L, "Leather Cap", "A worn cap", null, null, null);
    when(scopedCharacterResolver.requireScopedCharacter(
            1L, 2L, "live", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(new Character());
    when(equipmentService.listEquipment(eq(1L), eq(2L), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(dto)));

    mockMvc
        .perform(
            get("/tenants/1/characters/2/equipment")
                .param("gameInstanceId", "live")
                .param("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.content[0].slot").value("HEAD"))
        .andExpect(jsonPath("$.data.content[0].itemName").value("Leather Cap"));
  }

  @Test
  void wearAndRemoveUseTenantScopedPath() throws Exception {
    when(scopedCharacterResolver.requireScopedCharacter(
            1L, 2L, "live", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .thenReturn(new Character());
    when(equipmentService.wearItem(eq(1L), eq(2L), eq(3L), eq(null)))
        .thenReturn(
            new CharacterEquipmentEntryDto(
                1L, 2L, "HEAD", 3L, "Leather Cap", null, null, null, null));

    mockMvc
        .perform(
            post("/tenants/1/characters/2/equipment")
                .param("gameInstanceId", "live")
                .param("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemId\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.slot").value("HEAD"));

    when(equipmentService.removeWornItem(eq(1L), eq(2L), eq("HEAD")))
        .thenReturn(
            new CharacterEquipmentEntryDto(
                1L, 2L, "HEAD", 3L, "Leather Cap", null, null, null, null));

    mockMvc
        .perform(
            delete("/tenants/1/characters/2/equipment/HEAD")
                .param("gameInstanceId", "live")
                .param("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }
}
