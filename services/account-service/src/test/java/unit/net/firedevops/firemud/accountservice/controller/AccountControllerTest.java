package net.firedevops.firemud.accountservice.controller;

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
  private static final String ACCOUNT_UUID = "550e8400-e29b-41d4-a716-446655440000";
  private static final String OTHER_ACCOUNT_UUID = "550e8400-e29b-41d4-a716-446655440001";

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
    AccountDto response = new AccountDto(1L, "demo", "demo@example.com", "player", true);
    when(accountService.createAccount(request)).thenReturn(response);

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
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
  void deleteAccountDeniesPlatformAdminBeforeServiceMutation() throws Exception {
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            delete("/accounts/" + ACCOUNT_UUID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ACCOUNT_DELETE_WORKFLOW_UNAVAILABLE"));

    verifyNoInteractions(accountService);
  }

  @Test
  void deleteAccountRejectsScopedTenantAdminBecauseFullDeletionIsAccountScoped() throws Exception {
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("7", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            delete("/accounts/" + ACCOUNT_UUID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("PERMISSION_DENIED"))
        .andExpect(jsonPath("$.error.message").value("Account access required"));
  }

  @Test
  void exportAccountAllowsCurrentAccountWithoutTenantScope() throws Exception {
    AccountDto account = new AccountDto(42L, "demo", "demo@example.com", "player", true);
    when(accountService.exportAccountData(42L))
        .thenReturn(new AccountDataExportDto(account, List.of()));
    when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID))).thenReturn(42L);
    String token = jwtUtil.generateToken(ACCOUNT_UUID, Map.of("accountId", ACCOUNT_UUID));

    mockMvc
        .perform(
            get("/accounts/" + ACCOUNT_UUID + "/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }

  @Test
  void exportAccountRejectsNumericOrDifferentSubjectBeforeIdentityLookup() throws Exception {
    for (String subject : List.of("42", OTHER_ACCOUNT_UUID)) {
      String token = jwtUtil.generateToken(subject, Map.of("accountId", subject));
      mockMvc
          .perform(
              get("/accounts/" + ACCOUNT_UUID + "/export")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
          .andExpect(status().isForbidden());
    }

    verifyNoInteractions(accountService);
  }

  @Test
  void exportTenantDataFailsClosedWithoutReadingAccountService() throws Exception {
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("7", List.of("moderator"))));

    mockMvc
        .perform(
            get("/accounts/" + ACCOUNT_UUID + "/tenant-export")
                .param("tenantId", "7")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isNotImplemented());

    verifyNoInteractions(accountService);
  }

  @Test
  void exportAccountRejectsMalformedAccountIdBeforeDispatch() throws Exception {
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/accounts/not-a-uuid/export").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("accountId must be a canonical non-nil UUID"));

    verifyNoInteractions(accountService);
  }

  @Test
  void exportTenantDataRejectsZeroTenantIdBeforeDispatch() throws Exception {
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/accounts/" + ACCOUNT_UUID + "/tenant-export")
                .param("tenantId", "0")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be positive"));

    verifyNoInteractions(accountService);
  }

  @Test
  void deleteAccountDeniesCurrentAccountBeforeServiceMutation() throws Exception {
    String token = jwtUtil.generateToken(ACCOUNT_UUID, Map.of("accountId", ACCOUNT_UUID));

    mockMvc
        .perform(
            delete("/accounts/" + ACCOUNT_UUID)
                .param("tenantId", "7")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ACCOUNT_DELETE_WORKFLOW_UNAVAILABLE"));

    verifyNoInteractions(accountService);
  }

  @Test
  void deleteAccountRejectsZeroAccountIdBeforeDispatch() throws Exception {
    String token = jwtUtil.generateToken("1", Map.of("accountId", "1"));

    mockMvc
        .perform(delete("/accounts/0").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("accountId must be a canonical non-nil UUID"));

    verifyNoInteractions(accountService);
  }

  @Test
  void linkExternalRouteIsUnavailableForAuthenticatedRequest() throws Exception {
    String token = jwtUtil.generateToken(ACCOUNT_UUID, Map.of("accountId", ACCOUNT_UUID));

    mockMvc
        .perform(
            post("/accounts/" + ACCOUNT_UUID + "/external")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"tenantId\":1,\"accountId\":2,\"provider\":\"steam\",\"externalId\":\"demo\"}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isNotFound());

    verifyNoInteractions(accountService);
  }

  @Test
  void loginAuthModesAllowCurrentAccountWithoutTenantScope() throws Exception {
    AccountLoginAuthModesDto modes =
        new AccountLoginAuthModesDto(java.util.Set.of(AccountLoginAuthMode.EMAIL_OTP));
    when(accountService.resolveAccountStorageId(UUID.fromString(ACCOUNT_UUID))).thenReturn(42L);
    when(accountService.getLoginAuthModes(42L)).thenReturn(modes);
    String token = jwtUtil.generateToken(ACCOUNT_UUID, Map.of("accountId", ACCOUNT_UUID));

    mockMvc
        .perform(
            get("/accounts/" + ACCOUNT_UUID + "/login-auth-modes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.loginAuthModes[0]").value("EMAIL_OTP"));

    mockMvc
        .perform(
            put("/accounts/" + ACCOUNT_UUID + "/login-auth-modes")
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
        .updateLoginAuthModes(
            42L,
            new UpdateAccountLoginAuthModesRequest(
                java.util.Set.of(AccountLoginAuthMode.EMAIL_OTP)));
  }

  @Test
  void updateLoginAuthModesRejectsPlatformAdminBeforeDispatch() throws Exception {
    String token =
        jwtUtil.generateToken("operator", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            put("/accounts/" + ACCOUNT_UUID + "/login-auth-modes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginAuthModes\":[\"PASSWORD\"]}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.error.code").value("NOT_IMPLEMENTED"));

    verifyNoInteractions(accountService);
  }

  @Test
  void updateLoginAuthModesRejectsEmptySetBeforeDispatch() throws Exception {
    String token = jwtUtil.generateToken(ACCOUNT_UUID, Map.of("accountId", ACCOUNT_UUID));

    mockMvc
        .perform(
            put("/accounts/" + ACCOUNT_UUID + "/login-auth-modes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginAuthModes\":[]}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

    verifyNoInteractions(accountService);
  }
}
