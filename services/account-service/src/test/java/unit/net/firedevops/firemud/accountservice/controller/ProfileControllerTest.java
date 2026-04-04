package net.firedevops.firemud.accountservice.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.accountservice.config.AuthConfig;
import net.firedevops.firemud.accountservice.config.WebConfig;
import net.firedevops.firemud.accountservice.dto.ProfileDto;
import net.firedevops.firemud.accountservice.dto.UpdateProfileRequest;
import net.firedevops.firemud.accountservice.security.JwtAuthInterceptor;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(ProfileController.class)
@Import({AuthConfig.class, WebConfig.class, JwtAuthInterceptor.class})
@TestPropertySource(
    properties = {
      "firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234",
      "firemud.auth.jwt-expiration-ms=3600000"
    })
class ProfileControllerTest {

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private AccountService accountService;
  @Autowired private JwtUtil jwtUtil;

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void getProfileReturnsDto() throws Exception {
    ProfileDto dto = new ProfileDto(1L, 1L, 2L, "demo", "bio");
    when(accountService.getProfile(1L, 2L)).thenReturn(dto);

    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));
    mockMvc
        .perform(
            get("/profiles/2")
                .param("tenantId", "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.displayName").value("demo"));
  }

  @Test
  void updateProfileReturnsDto() throws Exception {
    UpdateProfileRequest req = new UpdateProfileRequest(1L, 2L, "demo", "bio");
    ProfileDto dto = new ProfileDto(1L, 1L, 2L, "demo", "bio");
    when(accountService.updateProfile(req)).thenReturn(dto);

    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));
    mockMvc
        .perform(
            put("/profiles/2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.displayName").value("demo"));
  }

  @Test
  void getProfileRejectsCrossTenantScopedAdmin() throws Exception {
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("admin"))));
    mockMvc
        .perform(
            get("/profiles/2")
                .param("tenantId", "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }
}
