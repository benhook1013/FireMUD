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
  private static final String ACCOUNT_UUID = "4cae05e8-7a6b-4b14-9d44-665e3eec450b";
  private static final String OTHER_ACCOUNT_UUID = "a2e1342e-a139-49c6-a460-c8e25f6697ae";

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
    when(accountService.resolveAccountStorageId(java.util.UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(2L);

    String token = jwtUtil.generateToken(ACCOUNT_UUID, Map.of("accountId", ACCOUNT_UUID));
    mockMvc
        .perform(
            get("/profiles/" + ACCOUNT_UUID)
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
    when(accountService.resolveAccountStorageId(java.util.UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(2L);

    String token = jwtUtil.generateToken(ACCOUNT_UUID, Map.of("accountId", ACCOUNT_UUID));
    mockMvc
        .perform(
            put("/profiles/" + ACCOUNT_UUID)
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
            get("/profiles/" + ACCOUNT_UUID)
                .param("tenantId", "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void getProfileRejectsSameTenantAdminForAnotherAccount() throws Exception {
    String token =
        jwtUtil.generateToken(
            OTHER_ACCOUNT_UUID, Map.of("scopedRoles", Map.of("1", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            get("/profiles/" + ACCOUNT_UUID)
                .param("tenantId", "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());

    verifyNoInteractions(accountService);
  }

  @Test
  void getProfileRejectsGlobalAdminForAnotherAccount() throws Exception {
    String token =
        jwtUtil.generateToken(OTHER_ACCOUNT_UUID, Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/profiles/" + ACCOUNT_UUID)
                .param("tenantId", "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());

    verifyNoInteractions(accountService);
  }

  @Test
  void getProfileRejectsMalformedAccountIdBeforeDispatch() throws Exception {
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/profiles/not-a-uuid")
                .param("tenantId", "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("accountId must be a canonical non-nil UUID"));

    verifyNoInteractions(accountService);
  }

  @Test
  void getProfileRejectsZeroTenantIdBeforeDispatch() throws Exception {
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/profiles/" + ACCOUNT_UUID)
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
    when(accountService.resolveAccountStorageId(java.util.UUID.fromString(ACCOUNT_UUID)))
        .thenReturn(2L);

    String token = jwtUtil.generateToken(ACCOUNT_UUID, Map.of("accountId", ACCOUNT_UUID));
    mockMvc
        .perform(
            put("/profiles/" + ACCOUNT_UUID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }

  @Test
  void updateProfileRejectsReservedHiddenStaffPolicyBeforeDispatch() throws Exception {
    UpdateProfileRequest request =
        new UpdateProfileRequest(
            1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.HIDDEN_STAFF);
    String token = jwtUtil.generateToken(ACCOUNT_UUID, Map.of("accountId", ACCOUNT_UUID));

    mockMvc
        .perform(
            put("/profiles/" + ACCOUNT_UUID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(
            jsonPath("$.error.message")
                .value("Profile presence visibility policy HIDDEN_STAFF is reserved"));

    verifyNoInteractions(accountService);
  }

  @Test
  void updateProfileRejectsUnauthorizedCallerBeforeReservedPolicyValidation() throws Exception {
    UpdateProfileRequest request =
        new UpdateProfileRequest(
            1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.HIDDEN_STAFF);
    String token =
        jwtUtil.generateToken(OTHER_ACCOUNT_UUID, Map.of("accountId", OTHER_ACCOUNT_UUID));

    mockMvc
        .perform(
            put("/profiles/" + ACCOUNT_UUID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());

    verifyNoInteractions(accountService);
  }

  @Test
  void updateProfileRejectsSameTenantAdminForAnotherAccount() throws Exception {
    UpdateProfileRequest request =
        new UpdateProfileRequest(1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE);
    String token =
        jwtUtil.generateToken(
            OTHER_ACCOUNT_UUID, Map.of("scopedRoles", Map.of("1", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            put("/profiles/" + ACCOUNT_UUID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());

    verifyNoInteractions(accountService);
  }

  @Test
  void updateProfileRejectsGlobalAdminForAnotherAccount() throws Exception {
    UpdateProfileRequest request =
        new UpdateProfileRequest(1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE);
    String token =
        jwtUtil.generateToken(OTHER_ACCOUNT_UUID, Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            put("/profiles/" + ACCOUNT_UUID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());

    verifyNoInteractions(accountService);
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
        .andExpect(jsonPath("$.error.message").value("accountId must be a canonical non-nil UUID"));

    verifyNoInteractions(accountService);
  }

  @Test
  void updateProfileRejectsZeroTenantIdBeforeDispatch() throws Exception {
    UpdateProfileRequest req =
        new UpdateProfileRequest(0L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE);
    String token = jwtUtil.generateToken(ACCOUNT_UUID, Map.of("accountId", ACCOUNT_UUID));

    mockMvc
        .perform(
            put("/profiles/" + ACCOUNT_UUID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be positive"));

    verifyNoInteractions(accountService);
  }
}
