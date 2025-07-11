package net.firedevops.firemud.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.firedevops.firemud.dto.ProfileDto;
import net.firedevops.firemud.dto.UpdateProfileRequest;
import net.firedevops.firemud.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProfileController.class)
class ProfileControllerTest {

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private AccountService accountService;

  @Test
  void getProfileReturnsDto() throws Exception {
    ProfileDto dto = new ProfileDto(1L, 1L, 2L, "demo", "bio");
    when(accountService.getProfile(1L, 2L)).thenReturn(dto);

    mockMvc
        .perform(get("/profiles/2").param("tenantId", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.displayName").value("demo"));
  }

  @Test
  void updateProfileReturnsDto() throws Exception {
    UpdateProfileRequest req = new UpdateProfileRequest(1L, 2L, "demo", "bio");
    ProfileDto dto = new ProfileDto(1L, 1L, 2L, "demo", "bio");
    when(accountService.updateProfile(req)).thenReturn(dto);

    mockMvc
        .perform(
            put("/profiles/2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.displayName").value("demo"));
  }
}
