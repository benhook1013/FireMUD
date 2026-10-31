package net.firedevops.firemud.accountservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountDataExportDto;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.dto.AccountLoginAuthModesDto;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.TenantDataExportDto;
import net.firedevops.firemud.accountservice.dto.UpdateAccountLoginAuthModesRequest;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthMode;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.exception.AccountAlreadyExistsException;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(AccountController.class)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@WithFiremudHttpAuthTestProperties
@TestPropertySource(
    properties = {
      "firemud.auth.http.public-routes[0].method=POST",
      "firemud.auth.http.public-routes[0].path-pattern=/accounts",
      "firemud.auth.http.public-routes[1].method=POST",
      "firemud.auth.http.public-routes[1].path-pattern=/accounts/"
    })
class AccountControllerTest {
  private static final String ACCOUNT_UUID_TEXT = "4cae05e8-7a6b-4b14-9d44-665e3eec450b";
  private static final UUID ACCOUNT_UUID = UUID.fromString(ACCOUNT_UUID_TEXT);
  private static final long ACCOUNT_ROW_ID = 42L;
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
  void createAccountReturnsDto() throws Exception {
    CreateAccountRequest request = new CreateAccountRequest("demo", "demo@example.com", "password");
    AccountDto response =
        new AccountDto(
            "4cae05e8-7a6b-4b14-9d44-665e3eec450b", "demo", "demo@example.com", "player", true);
    when(accountService.createAccount(request)).thenReturn(response);

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.id").value("4cae05e8-7a6b-4b14-9d44-665e3eec450b"))
        .andExpect(jsonPath("$.data.username").value("demo"));
  }

  @Test
  void createAccountConflictUsesCanonicalEnvelope() throws Exception {
    CreateAccountRequest request = new CreateAccountRequest("demo", "demo@example.com", "password");
    when(accountService.createAccount(request))
        .thenThrow(new AccountAlreadyExistsException(new RuntimeException("duplicate")));

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value("ERROR"))
        .andExpect(jsonPath("$.error.code").value("ALREADY_EXISTS"))
        .andExpect(jsonPath("$.error.message").value("Account already exists"));
  }

  @Test
  void createAccountRetainsMinimumPasswordLength() throws Exception {
    CreateAccountRequest request = new CreateAccountRequest("demo", "demo@example.com", "12345");

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(accountService);
  }

  @Test
  void deleteAccountAllowsGlobalPlatformAdmin() throws Exception {
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID)).thenReturn(ACCOUNT_ROW_ID);
    String token =
        jwtUtil.generateToken(
            OTHER_ACCOUNT_UUID,
            Map.of("accountId", OTHER_ACCOUNT_UUID, "globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            delete("/accounts/" + ACCOUNT_UUID_TEXT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));

    verify(accountService).deleteAccount(ACCOUNT_ROW_ID);
  }

  @Test
  void deleteAccountRejectsScopedTenantAdminBecauseFullDeletionIsAccountScoped() throws Exception {
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID)).thenReturn(ACCOUNT_ROW_ID);
    String token =
        jwtUtil.generateToken(
            OTHER_ACCOUNT_UUID,
            Map.of(
                "accountId",
                OTHER_ACCOUNT_UUID,
                "scopedRoles",
                Map.of("7", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            delete("/accounts/" + ACCOUNT_UUID_TEXT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"))
        .andExpect(jsonPath("$.error.message").value("Account access required"));

    verify(accountService, never()).deleteAccount(ACCOUNT_ROW_ID);
  }

  @Test
  void exportAccountAllowsCurrentAccountWithoutTenantScope() throws Exception {
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID)).thenReturn(ACCOUNT_ROW_ID);
    AccountDto account =
        new AccountDto(
            "4cae05e8-7a6b-4b14-9d44-665e3eec450b", "demo", "demo@example.com", "player", true);
    when(accountService.exportAccountData(ACCOUNT_ROW_ID))
        .thenReturn(
            new AccountDataExportDto(
                account,
                List.of(
                    new net.firedevops.firemud.accountservice.dto.ProfileDto(
                        1L,
                        7L,
                        account.id(),
                        "demo",
                        null,
                        net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy
                            .FRIENDS_ONLY))));
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID)).thenReturn(42L);
    String token = jwtUtil.generateToken(ACCOUNT_UUID_TEXT, Map.of("accountId", ACCOUNT_UUID_TEXT));

    mockMvc
        .perform(
            get("/accounts/" + ACCOUNT_UUID_TEXT + "/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.profiles[0].accountId").value(account.id()));

    verify(accountService).resolveAccountStorageId(ACCOUNT_UUID);
    verify(accountService).exportAccountData(ACCOUNT_ROW_ID);
  }

  @Test
  void exportTenantDataAllowsScopedTenantRole() throws Exception {
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID)).thenReturn(ACCOUNT_ROW_ID);
    AccountDto account =
        new AccountDto(
            "4cae05e8-7a6b-4b14-9d44-665e3eec450b", "demo", "demo@example.com", "player", true);
    when(accountService.exportTenantData(7L, ACCOUNT_ROW_ID))
        .thenReturn(new TenantDataExportDto(7L, account, null));
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID)).thenReturn(42L);
    String token =
        jwtUtil.generateToken(
            OTHER_ACCOUNT_UUID,
            Map.of(
                "accountId", OTHER_ACCOUNT_UUID, "scopedRoles", Map.of("7", List.of("moderator"))));

    mockMvc
        .perform(
            get("/accounts/" + ACCOUNT_UUID_TEXT + "/tenant-export")
                .param("tenantId", "7")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));

    verify(accountService).resolveAccountStorageId(ACCOUNT_UUID);
    verify(accountService).exportTenantData(7L, ACCOUNT_ROW_ID);
  }

  @Test
  void accountSubjectCannotSelectAnotherAccountWithoutGlobalPrivilege() throws Exception {
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID)).thenReturn(43L);
    String token =
        jwtUtil.generateToken(OTHER_ACCOUNT_UUID, Map.of("accountId", OTHER_ACCOUNT_UUID));

    mockMvc
        .perform(
            get("/accounts/" + ACCOUNT_UUID_TEXT + "/login-auth-modes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.message").value("Account access required"));

    mockMvc
        .perform(
            get("/accounts/" + ACCOUNT_UUID_TEXT + "/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            delete("/accounts/" + ACCOUNT_UUID_TEXT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());

    verify(accountService, never()).getLoginAuthModes(43L);
    verify(accountService, never()).exportAccountData(43L);
    verify(accountService, never()).deleteAccount(43L);
    verify(accountService, never()).resolveAccountStorageId(any());
  }

  @Test
  void allAccountSelectorRoutesRejectMalformedNumericNilAndNoncanonicalUuidsBeforeDispatch()
      throws Exception {
    String token =
        jwtUtil.generateToken(
            OTHER_ACCOUNT_UUID,
            Map.of("accountId", OTHER_ACCOUNT_UUID, "globalRoles", List.of("platformAdmin")));
    List<String> invalidSelectors =
        List.of(
            "42",
            "not-a-uuid",
            "00000000-0000-0000-0000-000000000000",
            ACCOUNT_UUID_TEXT.toUpperCase(java.util.Locale.ROOT));

    for (String selector : invalidSelectors) {
      for (org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request :
          accountSelectorRequests(selector)) {
        mockMvc
            .perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
            .andExpect(
                jsonPath("$.error.message")
                    .value("accountId must be a canonical non-nil Account UUID"));
      }
    }

    verifyNoInteractions(accountService);
  }

  @Test
  void exportTenantDataRejectsZeroTenantIdBeforeDispatch() throws Exception {
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID)).thenReturn(ACCOUNT_ROW_ID);
    String token =
        jwtUtil.generateToken(
            OTHER_ACCOUNT_UUID,
            Map.of("accountId", OTHER_ACCOUNT_UUID, "globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/accounts/" + ACCOUNT_UUID_TEXT + "/tenant-export")
                .param("tenantId", "0")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be positive"));

    verifyNoInteractions(accountService);
  }

  @Test
  void deleteAccountAllowsCurrentAccountWithoutPrivilegedTenantRole() throws Exception {
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID)).thenReturn(ACCOUNT_ROW_ID);
    String token = jwtUtil.generateToken(ACCOUNT_UUID_TEXT, Map.of("accountId", ACCOUNT_UUID_TEXT));

    mockMvc
        .perform(
            delete("/accounts/" + ACCOUNT_UUID_TEXT)
                .param("tenantId", "7")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));

    verify(accountService).deleteAccount(ACCOUNT_ROW_ID);
  }

  @Test
  void deleteAccountRejectsNilUuidBeforeDispatch() throws Exception {
    String nilAccountUuid = "00000000-0000-0000-0000-000000000000";
    String token = jwtUtil.generateToken(ACCOUNT_UUID_TEXT, Map.of("accountId", ACCOUNT_UUID_TEXT));

    mockMvc
        .perform(
            delete("/accounts/" + nilAccountUuid)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(
            jsonPath("$.error.message")
                .value("accountId must be a canonical non-nil Account UUID"));

    verifyNoInteractions(accountService);
  }

  @Test
  void linkExternalRouteIsUnavailableForAuthenticatedRequest() throws Exception {
    String token =
        jwtUtil.generateToken(OTHER_ACCOUNT_UUID, Map.of("accountId", OTHER_ACCOUNT_UUID));

    mockMvc
        .perform(
            post("/accounts/2/external")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"tenantId\":1,\"accountId\":2,\"provider\":\"steam\",\"externalId\":\"demo\"}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isNotFound());

    verifyNoInteractions(accountService);
  }

  @Test
  void loginAuthModesAllowCurrentAccountWithoutTenantScope() throws Exception {
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID)).thenReturn(ACCOUNT_ROW_ID);
    AccountLoginAuthModesDto modes =
        new AccountLoginAuthModesDto(java.util.Set.of(AccountLoginAuthMode.EMAIL_OTP));
    when(accountService.getLoginAuthModes(ACCOUNT_ROW_ID)).thenReturn(modes);
    String token = jwtUtil.generateToken(ACCOUNT_UUID_TEXT, Map.of("accountId", ACCOUNT_UUID_TEXT));

    mockMvc
        .perform(
            get("/accounts/" + ACCOUNT_UUID_TEXT + "/login-auth-modes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.loginAuthModes[0]").value("EMAIL_OTP"));
    verify(accountService).resolveAccountStorageId(ACCOUNT_UUID);
    verify(accountService).getLoginAuthModes(ACCOUNT_ROW_ID);

    mockMvc
        .perform(
            put("/accounts/" + ACCOUNT_UUID_TEXT + "/login-auth-modes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginAuthModes\":[\"EMAIL_OTP\"]}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.error.code").value("NOT_IMPLEMENTED"))
        .andExpect(
            jsonPath("$.error.message")
                .value(
                    "Recent ordinary reauthentication is required; login-factor changes are unavailable until Account implements its evidence mechanism"));

    verify(accountService, never())
        .updateLoginAuthModes(anyLong(), any(UpdateAccountLoginAuthModesRequest.class));
  }

  @Test
  void updateLoginAuthModesRejectsPlatformAdminBeforeDispatch() throws Exception {
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID)).thenReturn(ACCOUNT_ROW_ID);
    String token =
        jwtUtil.generateToken(
            OTHER_ACCOUNT_UUID,
            Map.of("accountId", OTHER_ACCOUNT_UUID, "globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            put("/accounts/" + ACCOUNT_UUID_TEXT + "/login-auth-modes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginAuthModes\":[\"PASSWORD\"]}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.error.code").value("NOT_IMPLEMENTED"));

    verify(accountService, never())
        .updateLoginAuthModes(anyLong(), any(UpdateAccountLoginAuthModesRequest.class));
  }

  @Test
  void updateLoginAuthModesRejectsEmptySetBeforeDispatch() throws Exception {
    String token = jwtUtil.generateToken(ACCOUNT_UUID_TEXT, Map.of("accountId", ACCOUNT_UUID_TEXT));

    mockMvc
        .perform(
            put("/accounts/" + ACCOUNT_UUID_TEXT + "/login-auth-modes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginAuthModes\":[]}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

    verifyNoInteractions(accountService);
  }

  @Test
  void absentAndOtherAccountSelectorsHaveTheSameNonDisclosingDenial() throws Exception {
    for (boolean absent : List.of(true, false)) {
      String callerAccountUuid = absent ? ACCOUNT_UUID_TEXT : OTHER_ACCOUNT_UUID;
      String token =
          jwtUtil.generateToken(callerAccountUuid, Map.of("accountId", callerAccountUuid));
      org.mockito.Mockito.reset(accountService);
      if (absent) {
        when(accountService.resolveAccountStorageId(ACCOUNT_UUID))
            .thenThrow(new IllegalArgumentException("Account not found"));
      } else {
        when(accountService.resolveAccountStorageId(ACCOUNT_UUID)).thenReturn(43L);
      }
      for (var request : accountSelectorRequests(ACCOUNT_UUID_TEXT)) {
        mockMvc
            .perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"))
            .andExpect(jsonPath("$.error.message").value("Account access required"));
      }
      verify(accountService, never()).getLoginAuthModes(anyLong());
      verify(accountService, never()).exportAccountData(anyLong());
      verify(accountService, never()).exportTenantData(anyLong(), anyLong());
      verify(accountService, never()).deleteAccount(anyLong());
      if (!absent) {
        verify(accountService, never()).resolveAccountStorageId(any());
      }
    }
  }

  @Test
  void allAccountSelectorsMapTransactionBeginOutageToRetryableUnavailable() throws Exception {
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID))
        .thenThrow(
            new org.springframework.transaction.CannotCreateTransactionException(
                "Database connection unavailable"));
    String token = jwtUtil.generateToken(ACCOUNT_UUID_TEXT, Map.of("accountId", ACCOUNT_UUID_TEXT));
    for (var request : accountSelectorRequests(ACCOUNT_UUID_TEXT)) {
      mockMvc
          .perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.error.code").value("AUTH_UNAVAILABLE"));
    }
    verify(accountService, never()).getLoginAuthModes(anyLong());
    verify(accountService, never()).exportAccountData(anyLong());
    verify(accountService, never()).exportTenantData(anyLong(), anyLong());
    verify(accountService, never()).deleteAccount(anyLong());
  }

  @Test
  void accountOwnerReadbackInconsistencyAndOutageReturnRetryableUnavailable() throws Exception {
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID))
        .thenThrow(new IllegalStateException("Account UUID provenance is inconsistent"));
    String token = jwtUtil.generateToken(ACCOUNT_UUID_TEXT, Map.of("accountId", ACCOUNT_UUID_TEXT));

    mockMvc
        .perform(
            get("/accounts/" + ACCOUNT_UUID_TEXT + "/login-auth-modes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("AUTH_UNAVAILABLE"));

    org.mockito.Mockito.reset(accountService);
    when(accountService.resolveAccountStorageId(ACCOUNT_UUID))
        .thenThrow(new DataAccessResourceFailureException("Account owner read failed"));

    mockMvc
        .perform(
            get("/accounts/" + ACCOUNT_UUID_TEXT + "/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("AUTH_UNAVAILABLE"));
  }

  private static List<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>
      accountSelectorRequests(String selector) {
    return List.of(
        get("/accounts/" + selector + "/login-auth-modes"),
        put("/accounts/" + selector + "/login-auth-modes")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"loginAuthModes\":[\"PASSWORD\"]}"),
        get("/accounts/" + selector + "/export"),
        get("/accounts/" + selector + "/tenant-export").param("tenantId", "7"),
        delete("/accounts/" + selector));
  }
}
