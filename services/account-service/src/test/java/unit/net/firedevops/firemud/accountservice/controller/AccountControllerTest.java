package net.firedevops.firemud.accountservice.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.accountservice.dto.AccountDataExportDto;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.TenantDataExportDto;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(AccountController.class)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@WithFiremudHttpAuthTestProperties
@TestPropertySource(
    properties = {
      "firemud.auth.http.public-path-patterns[0]=/accounts",
      "firemud.auth.http.public-path-patterns[1]=/accounts/"
    })
class AccountControllerTest {

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
    CreateAccountRequest request =
        new CreateAccountRequest(7L, "demo", "demo@example.com", "password");
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
  void deleteAccountAllowsScopedTenantAdmin() throws Exception {
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(delete("/accounts/42").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }

  @Test
  void deleteAccountRejectsScopedTenantAdminBecauseFullDeletionIsAccountScoped() throws Exception {
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("7", List.of("tenantAdmin"))));

    mockMvc
        .perform(delete("/accounts/42").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void exportAccountAllowsCurrentAccountWithoutTenantScope() throws Exception {
    AccountDto account = new AccountDto(42L, "demo", "demo@example.com", "player", true);
    when(accountService.exportAccountData(42L))
        .thenReturn(new AccountDataExportDto(account, List.of()));
    String token = jwtUtil.generateToken("42", Map.of("accountId", "42"));

    mockMvc
        .perform(get("/accounts/42/export").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }

  @Test
  void exportTenantDataAllowsScopedTenantRole() throws Exception {
    AccountDto account = new AccountDto(42L, "demo", "demo@example.com", "player", true);
    when(accountService.exportTenantData(7L, 42L))
        .thenReturn(new TenantDataExportDto(7L, account, null));
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("7", List.of("moderator"))));

    mockMvc
        .perform(
            get("/accounts/42/tenant-export")
                .param("tenantId", "7")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }

  @Test
  void deleteAccountAllowsCurrentAccountWithoutPrivilegedTenantRole() throws Exception {
    String token = jwtUtil.generateToken("42", Map.of("accountId", "42"));

    mockMvc
        .perform(
            delete("/accounts/42")
                .param("tenantId", "7")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }
}
