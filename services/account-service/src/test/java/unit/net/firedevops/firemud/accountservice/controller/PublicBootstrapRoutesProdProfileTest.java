package net.firedevops.firemud.accountservice.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.LoginRequest;
import net.firedevops.firemud.accountservice.dto.PlayerBootstrapResult;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.test.WithFiremudHttpAuthTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest({AuthController.class, AccountController.class})
@ActiveProfiles("prod")
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@WithFiremudHttpAuthTestProperties
class PublicBootstrapRoutesProdProfileTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private AccountService accountService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void playerBootstrapRemainsPublicInProdProfile() throws Exception {
    LoginRequest request = new LoginRequest(1L, "demo@example.com", "swordfish", null);
    when(accountService.issuePlayerBootstrap(1L, "demo@example.com", "swordfish", null))
        .thenReturn(
            new PlayerBootstrapResult(
                1L, "boot123", "2026-03-30T00:00:00Z", "2026-03-30T00:05:00Z"));

    mockMvc
        .perform(
            post("/auth/player-bootstrap")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.accountId").value(1))
        .andExpect(jsonPath("$.data.bootstrapToken").value("boot123"));
  }

  @Test
  void accountCreationRemainsPublicInProdProfile() throws Exception {
    CreateAccountRequest request =
        new CreateAccountRequest(1L, "demo", "demo@example.com", "swordfish");
    when(accountService.createAccount(request))
        .thenReturn(new AccountDto(1L, "demo", "demo@example.com", "player", true));

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.id").value(1))
        .andExpect(jsonPath("$.data.username").value("demo"));
  }
}
