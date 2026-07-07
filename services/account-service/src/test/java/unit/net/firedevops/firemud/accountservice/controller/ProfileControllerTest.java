package net.firedevops.firemud.accountservice.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.accountservice.dto.ProfileDto;
import net.firedevops.firemud.accountservice.dto.UpdateProfileRequest;
import net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.test.WithFiremudHttpAuthTestProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(ProfileController.class)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@WithFiremudHttpAuthTestProperties
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
    ProfileDto dto =
        new ProfileDto(1L, 1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.FRIENDS_ONLY);
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
    UpdateProfileRequest req =
        new UpdateProfileRequest(1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE);
    ProfileDto dto =
        new ProfileDto(1L, 1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE);
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
        .andExpect(jsonPath("$.data.displayName").value("demo"))
        .andExpect(jsonPath("$.data.presenceVisibilityPolicy").value("PRIVATE"));
  }

  @Test
  void getProfileRejectsCrossTenantScopedAdmin() throws Exception {
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));
    mockMvc
        .perform(
            get("/profiles/2")
                .param("tenantId", "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void getProfileRejectsMalformedAccountIdBeforeDispatch() throws Exception {
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/profiles/not-a-number")
                .param("tenantId", "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("accountId must be numeric"));

    verifyNoInteractions(accountService);
  }

  @Test
  void getProfileRejectsZeroTenantIdBeforeDispatch() throws Exception {
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/profiles/2")
                .param("tenantId", "0")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be positive"));

    verifyNoInteractions(accountService);
  }

  @Test
  void updateProfileAllowsCurrentAccountWithoutPrivilegedTenantRole() throws Exception {
    UpdateProfileRequest req =
        new UpdateProfileRequest(1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE);
    ProfileDto dto =
        new ProfileDto(1L, 1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE);
    when(accountService.updateProfile(req)).thenReturn(dto);

    String token = jwtUtil.generateToken("2", Map.of("accountId", "2"));
    mockMvc
        .perform(
            put("/profiles/2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }

  @Test
  void updateProfileRejectsZeroAccountIdBeforeDispatch() throws Exception {
    UpdateProfileRequest req =
        new UpdateProfileRequest(1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE);
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            put("/profiles/0")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("accountId must be positive"));

    verifyNoInteractions(accountService);
  }
}
