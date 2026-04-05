package net.firedevops.firemud.entitymanagement.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import net.firedevops.firemud.entitymanagement.dto.CharacterDto;
import net.firedevops.firemud.entitymanagement.service.CharacterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CharacterController.class)
class CharacterControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CharacterService characterService;

  @Test
  void listReturnsCharacters() throws Exception {
    CharacterDto dto = new CharacterDto(1L, 1L, 1L, "Hero", 1, 0, 1, 1, 1, 1, 10, 5);
    when(characterService.listForTenantAndAccount(eq(1L), eq(1L), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(dto)));

    mockMvc
        .perform(get("/tenants/1/accounts/1/characters"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.content[0].name").value("Hero"));
  }
}
