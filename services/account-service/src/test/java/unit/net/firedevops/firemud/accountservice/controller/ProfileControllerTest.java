package net.firedevops.firemud.accountservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.ProfileDto;
import net.firedevops.firemud.accountservice.dto.UpdateProfileRequest;
import net.firedevops.firemud.accountservice.dto.UpdateProfileRestRequest;
import net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.test.WithFiremudHttpAuthTestProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(ProfileController.class)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@WithFiremudHttpAuthTestProperties
class ProfileControllerTest {
  private static final String ACCOUNT_UUID_TEXT = "d09f80cb-a46a-415e-8d35-c9e8068e49de";
  private static final UUID ACCOUNT_UUID = UUID.fromString(ACCOUNT_UUID_TEXT);
  private static final String OTHER_ACCOUNT_UUID = "a2e1342e-a139-49c6-a460-c8e25f6697ae";

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private AccountService accountService;
  @Autowired private JwtUtil jwtUtil;

  @BeforeEach
  void configureOwnerResolution() {
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID)).thenReturn(2L);
  }

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void getProfileResolvesCanonicalUuidAndReturnsUuidDto() throws Exception {
    ProfileDto dto = profileDto(ProfilePresenceVisibilityPolicy.FRIENDS_ONLY);
    when(accountService.getProfile(1L, 2L)).thenReturn(dto);
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID)).thenReturn(2L);

    mockMvc
        .perform(
            get("/profiles/" + ACCOUNT_UUID_TEXT)
                .param("tenantId", "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinaryToken(2L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.displayName").value("demo"))
        .andExpect(jsonPath("$.data.accountId").value(ACCOUNT_UUID_TEXT));

    verify(accountService).resolveAccountStorageId(ACCOUNT_UUID);
    verify(accountService).getProfile(1L, 2L);
  }

  @Test
  void updateProfileResolvesMatchingCanonicalUuidSelectors() throws Exception {
    UpdateProfileRequest internalRequest =
        new UpdateProfileRequest(1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE);
    when(accountService.updateProfile(internalRequest))
        .thenReturn(profileDto(ProfilePresenceVisibilityPolicy.PRIVATE));

    mockMvc
        .perform(
            put("/profiles/" + ACCOUNT_UUID_TEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        restRequest(ProfilePresenceVisibilityPolicy.PRIVATE)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinaryToken(2L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.displayName").value("demo"))
        .andExpect(jsonPath("$.data.accountId").value(ACCOUNT_UUID_TEXT))
        .andExpect(jsonPath("$.data.presenceVisibilityPolicy").value("PRIVATE"));

    verify(accountService).resolveAccountStorageId(ACCOUNT_UUID);
    verify(accountService).updateProfile(internalRequest);
  }

  @Test
  void getProfileRejectsCrossTenantScopedAdmin() throws Exception {
    String token =
        jwtUtil.generateToken(
            OTHER_ACCOUNT_UUID,
            Map.of(
                "accountId",
                OTHER_ACCOUNT_UUID,
                "scopedRoles",
                Map.of("8", List.of("tenantAdmin"))));
    mockMvc
        .perform(
            get("/profiles/" + ACCOUNT_UUID_TEXT)
                .param("tenantId", "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void getProfileRejectsSameTenantAdminForAnotherAccount() throws Exception {
    String token =
        jwtUtil.generateToken(
            OTHER_ACCOUNT_UUID,
            Map.of(
                "accountId",
                OTHER_ACCOUNT_UUID,
                "scopedRoles",
                Map.of("1", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            get("/profiles/" + ACCOUNT_UUID_TEXT)
                .param("tenantId", "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());

    verifyNoProfileDispatch();
  }

  @Test
  void getProfileRejectsGlobalAdminForAnotherAccount() throws Exception {
    String token =
        jwtUtil.generateToken(
            OTHER_ACCOUNT_UUID,
            Map.of("accountId", OTHER_ACCOUNT_UUID, "globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/profiles/" + ACCOUNT_UUID_TEXT)
                .param("tenantId", "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());

    verifyNoProfileDispatch();
  }

  @Test
  void getProfileRejectsNumericMalformedAndNilPathSelectors() throws Exception {
    String token = jwtUtil.generateToken(ACCOUNT_UUID_TEXT, Map.of("accountId", ACCOUNT_UUID_TEXT));
    for (String selector :
        List.of(
            "2",
            "not-a-uuid",
            "00000000-0000-0000-0000-000000000000",
            ACCOUNT_UUID_TEXT.toUpperCase(java.util.Locale.ROOT))) {
      mockMvc
          .perform(
              get("/profiles/" + selector)
                  .param("tenantId", "1")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
          .andExpect(
              jsonPath("$.error.message")
                  .value("accountId must be a canonical non-nil Account UUID"));
    }

    verifyNoInteractions(accountService);
  }

  @Test
  void getProfileRejectsZeroTenantIdBeforeDispatch() throws Exception {
    String token =
        jwtUtil.generateToken(
            OTHER_ACCOUNT_UUID,
            Map.of("accountId", OTHER_ACCOUNT_UUID, "globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/profiles/" + ACCOUNT_UUID_TEXT)
                .param("tenantId", "0")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be positive"));

    verifyNoInteractions(accountService);
  }

  @Test
  void updateProfileAllowsCurrentUuidSessionOwner() throws Exception {
    UpdateProfileRequest internalRequest =
        new UpdateProfileRequest(1L, 2L, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE);
    when(accountService.updateProfile(internalRequest))
        .thenReturn(profileDto(ProfilePresenceVisibilityPolicy.PRIVATE));

    mockMvc
        .perform(
            put("/profiles/" + ACCOUNT_UUID_TEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        restRequest(ProfilePresenceVisibilityPolicy.PRIVATE)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinaryToken(2L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.accountId").value(ACCOUNT_UUID_TEXT));
  }

  @Test
  void updateProfileRejectsReservedHiddenStaffPolicyBeforeDispatch() throws Exception {
    String token = ordinaryToken(2L);
    mockMvc
        .perform(
            put("/profiles/" + ACCOUNT_UUID_TEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        restRequest(ProfilePresenceVisibilityPolicy.HIDDEN_STAFF)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(
            jsonPath("$.error.message")
                .value("Profile presence visibility policy is not selectable"));

    verifyNoProfileDispatch();
  }

  @Test
  void updateProfileRejectsUnauthorizedCallerBeforeReservedPolicyValidation() throws Exception {
    UpdateProfileRestRequest request = restRequest(ProfilePresenceVisibilityPolicy.HIDDEN_STAFF);
    String token =
        jwtUtil.generateToken(OTHER_ACCOUNT_UUID, Map.of("accountId", OTHER_ACCOUNT_UUID));

    mockMvc
        .perform(
            put("/profiles/" + ACCOUNT_UUID_TEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());

    verifyNoProfileDispatch();
  }

  @Test
  void updateProfileRejectsSameTenantAdminForAnotherAccount() throws Exception {
    String token =
        jwtUtil.generateToken(
            OTHER_ACCOUNT_UUID,
            Map.of(
                "accountId",
                OTHER_ACCOUNT_UUID,
                "scopedRoles",
                Map.of("1", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            put("/profiles/" + ACCOUNT_UUID_TEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        restRequest(ProfilePresenceVisibilityPolicy.PRIVATE)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());

    verifyNoProfileDispatch();
  }

  @Test
  void updateProfileRejectsGlobalAdminForAnotherAccount() throws Exception {
    String token =
        jwtUtil.generateToken(
            OTHER_ACCOUNT_UUID,
            Map.of("accountId", OTHER_ACCOUNT_UUID, "globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            put("/profiles/" + ACCOUNT_UUID_TEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        restRequest(ProfilePresenceVisibilityPolicy.PRIVATE)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());

    verifyNoProfileDispatch();
  }

  @Test
  void updateProfileRejectsMalformedNilAndNoncanonicalBodySelectors() throws Exception {
    String token = ordinaryToken(2L);
    for (String bodySelector :
        List.of(
            "not-a-uuid",
            "00000000-0000-0000-0000-000000000000",
            ACCOUNT_UUID_TEXT.toUpperCase(java.util.Locale.ROOT))) {
      String body =
          "{\"tenantId\":1,\"accountId\":\""
              + bodySelector
              + "\",\"displayName\":\"demo\",\"bio\":\"bio\","
              + "\"presenceVisibilityPolicy\":\"PRIVATE\"}";
      mockMvc
          .perform(
              put("/profiles/" + ACCOUNT_UUID_TEXT)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    verifyNoInteractions(accountService);
  }

  @Test
  void updateProfileRejectsNumericJsonBodySelector() throws Exception {
    mockMvc
        .perform(
            put("/profiles/" + ACCOUNT_UUID_TEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"tenantId\":1,\"accountId\":2,\"displayName\":\"demo\","
                        + "\"bio\":\"bio\",\"presenceVisibilityPolicy\":\"PRIVATE\"}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinaryToken(2L)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

    verifyNoInteractions(accountService);
  }

  @Test
  void updateProfileRejectsBodyPathMismatchBeforeOwnerResolution() throws Exception {
    UpdateProfileRestRequest request =
        new UpdateProfileRestRequest(
            1L,
            "eaf3edee-a47e-45b2-9940-d32af33d0f52",
            "demo",
            "bio",
            ProfilePresenceVisibilityPolicy.PRIVATE);

    mockMvc
        .perform(
            put("/profiles/" + ACCOUNT_UUID_TEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinaryToken(2L)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("accountId must match the path selector"));

    verifyNoInteractions(accountService);
  }

  @Test
  void absentAndOtherProfileOwnersHaveTheSameNonDisclosingDenial() throws Exception {
    for (boolean absent : List.of(true, false)) {
      org.mockito.Mockito.reset(accountService);
      if (absent) {
        when(accountService.resolveAccountStorageId(ACCOUNT_UUID))
            .thenThrow(new IllegalArgumentException("Account not found"));
      } else {
        when(accountService.resolveAccountStorageId(ACCOUNT_UUID)).thenReturn(3L);
      }
      UpdateProfileRestRequest body =
          new UpdateProfileRestRequest(
              1L, ACCOUNT_UUID_TEXT, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE);
      for (var request :
          List.of(
              get("/profiles/" + ACCOUNT_UUID_TEXT).param("tenantId", "1"),
              put("/profiles/" + ACCOUNT_UUID_TEXT)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(body)))) {
        mockMvc
            .perform(
                request.header(
                    HttpHeaders.AUTHORIZATION, "Bearer " + ordinaryToken(absent ? 2L : 3L)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"))
            .andExpect(jsonPath("$.error.message").value("Account access required"));
      }
      verify(accountService, never()).getProfile(any(), any());
      verify(accountService, never()).updateProfile(any(UpdateProfileRequest.class));
    }
  }

  @Test
  void profileOwnerReadbackInconsistencyReturnsRetryableUnavailable() throws Exception {
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID))
        .thenThrow(new IllegalStateException("Account UUID provenance is inconsistent"));

    mockMvc
        .perform(
            get("/profiles/" + ACCOUNT_UUID_TEXT)
                .param("tenantId", "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinaryToken(2L)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("AUTH_UNAVAILABLE"));
  }

  @Test
  void profileSelectorsMapTransactionBeginOutageToRetryableUnavailable() throws Exception {
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID))
        .thenThrow(
            new org.springframework.transaction.CannotCreateTransactionException(
                "Database connection unavailable"));
    var body =
        new UpdateProfileRestRequest(
            1L, ACCOUNT_UUID_TEXT, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE);
    for (var request :
        List.of(
            get("/profiles/" + ACCOUNT_UUID_TEXT).param("tenantId", "1"),
            put("/profiles/" + ACCOUNT_UUID_TEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))) {
      mockMvc
          .perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinaryToken(2L)))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.error.code").value("AUTH_UNAVAILABLE"));
    }
    verify(accountService, never()).getProfile(any(), any());
    verify(accountService, never()).updateProfile(any(UpdateProfileRequest.class));
  }

  @Test
  void profileOwnerReadbackOutageReturnsRetryableUnavailable() throws Exception {
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID))
        .thenThrow(new DataAccessResourceFailureException("Account owner read failed"));

    mockMvc
        .perform(
            get("/profiles/" + ACCOUNT_UUID_TEXT)
                .param("tenantId", "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinaryToken(2L)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("AUTH_UNAVAILABLE"));
  }

  @Test
  void updateProfileRejectsZeroTenantIdBeforeDispatch() throws Exception {
    UpdateProfileRestRequest request =
        new UpdateProfileRestRequest(
            0L, ACCOUNT_UUID_TEXT, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE);

    mockMvc
        .perform(
            put("/profiles/" + ACCOUNT_UUID_TEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ordinaryToken(2L)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be positive"));

    verifyNoInteractions(accountService);
  }

  @Test
  void updateProfileRejectsChangedBodyAccountBeforeDispatch() throws Exception {
    UpdateProfileRestRequest request =
        new UpdateProfileRestRequest(
            1L, OTHER_ACCOUNT_UUID, "demo", "bio", ProfilePresenceVisibilityPolicy.PRIVATE);
    String token = jwtUtil.generateToken(ACCOUNT_UUID_TEXT, Map.of("accountId", ACCOUNT_UUID_TEXT));

    mockMvc
        .perform(
            put("/profiles/" + ACCOUNT_UUID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(accountService);
  }

  @Test
  void getProfileRefusesWrongOwnerProjection() throws Exception {
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID)).thenReturn(2L);
    when(accountService.getProfile(1L, 2L))
        .thenReturn(
            new ProfileDto(
                1L,
                1L,
                OTHER_ACCOUNT_UUID,
                "private",
                "secret",
                ProfilePresenceVisibilityPolicy.PRIVATE));
    String token = jwtUtil.generateToken(ACCOUNT_UUID_TEXT, Map.of("accountId", ACCOUNT_UUID_TEXT));

    mockMvc
        .perform(
            get("/profiles/" + ACCOUNT_UUID)
                .param("tenantId", "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isServiceUnavailable());
  }

  private String ordinaryToken(long accountId) {
    String accountUuid = accountId == 2L ? ACCOUNT_UUID_TEXT : OTHER_ACCOUNT_UUID;
    return jwtUtil.generateToken(accountUuid, Map.of("accountId", accountUuid));
  }

  private static UpdateProfileRestRequest restRequest(
      ProfilePresenceVisibilityPolicy visibilityPolicy) {
    return new UpdateProfileRestRequest(1L, ACCOUNT_UUID_TEXT, "demo", "bio", visibilityPolicy);
  }

  private static ProfileDto profileDto(ProfilePresenceVisibilityPolicy visibilityPolicy) {
    return new ProfileDto(1L, 1L, ACCOUNT_UUID_TEXT, "demo", "bio", visibilityPolicy);
  }

  private void verifyNoProfileDispatch() {
    verify(accountService, never()).getProfile(1L, 2L);
    verify(accountService, never()).updateProfile(any());
  }
}
