package net.firedevops.firemud.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.config.AuthConfig;
import net.firedevops.firemud.config.WebConfig;
import net.firedevops.firemud.dto.CharacterDto;
import net.firedevops.firemud.security.JwtAuthInterceptor;
import net.firedevops.firemud.service.CharacterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CharacterController.class)
@Import({AuthConfig.class, WebConfig.class, JwtAuthInterceptor.class})
@TestPropertySource(
    properties = {
      "firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234",
      "firemud.auth.jwt-expiration-ms=3600000"
    })
class CharacterControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;

  @MockitoBean private CharacterService characterService;

  @Test
  void listReturnsCharacters() throws Exception {
    CharacterDto dto = new CharacterDto(1L, 1L, 1L, "Hero", 1, 0, 1, 1, 1, 1, 10, 5);
    when(characterService.listForAccount(1L)).thenReturn(List.of(dto));

    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));
    mockMvc
        .perform(get("/accounts/1/characters").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].name").value("Hero"));
  }
}
