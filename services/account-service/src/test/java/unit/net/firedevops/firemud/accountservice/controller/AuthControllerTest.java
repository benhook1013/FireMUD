package net.firedevops.firemud.accountservice.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import net.firedevops.firemud.accountservice.dto.AuthenticationResult;
import net.firedevops.firemud.accountservice.dto.BootstrapCharacterDto;
import net.firedevops.firemud.accountservice.dto.BootstrapRealmDto;
import net.firedevops.firemud.accountservice.dto.BootstrapWorldDto;
import net.firedevops.firemud.accountservice.dto.ConnectTokenRequest;
import net.firedevops.firemud.accountservice.dto.ConnectTokenResult;
import net.firedevops.firemud.accountservice.dto.LoginRequest;
import net.firedevops.firemud.accountservice.dto.PasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.PlayerBootstrapRequest;
import net.firedevops.firemud.accountservice.dto.PlayerBootstrapResult;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(AuthController.class)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
class AuthControllerTest {

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private AccountService accountService;

  @Test
  void loginReturnsTokenAndAccountId() throws Exception {
    LoginRequest request = new LoginRequest("demo", "password");
    when(accountService.authenticate("demo", "password"))
        .thenReturn(new AuthenticationResult(1L, "tok123"));

    mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.accountId").value(1))
        .andExpect(jsonPath("$.data.authToken").value("tok123"));
  }

  @Test
  void playerBootstrapReturnsShortLivedToken() throws Exception {
    PlayerBootstrapRequest request = new PlayerBootstrapRequest("demo", "password");
    when(accountService.issuePlayerBootstrap("demo", "password"))
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
  void connectTokenSetsCookieAndReturnsMetadataOnly() throws Exception {
    ConnectTokenRequest request = new ConnectTokenRequest("scope-1", "req-7");
    when(accountService.issueConnectToken("boot123", new ConnectTokenRequest("scope-1", "req-7")))
        .thenReturn(
            new ConnectTokenResult(
                1L,
                1L,
                42L,
                "production",
                "scope-1",
                "conn123",
                "jti-1",
                "req-7",
                "2026-03-30T00:00:00Z",
                "2026-03-30T00:00:30Z",
                false));

    mockMvc
        .perform(
            post("/auth/connect-token")
                .header(HttpHeaders.AUTHORIZATION, "Bearer boot123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.connectToken").doesNotHaveJsonPath())
        .andExpect(content().string(not(containsString("conn123"))))
        .andExpect(jsonPath("$.data.accountId").value(1))
        .andExpect(jsonPath("$.data.tenantId").value(1))
        .andExpect(jsonPath("$.data.gameInstanceId").value(42))
        .andExpect(jsonPath("$.data.realmSlug").value("production"))
        .andExpect(jsonPath("$.data.connectScopeId").value("scope-1"))
        .andExpect(jsonPath("$.data.jti").value("jti-1"))
        .andExpect(jsonPath("$.data.requestId").value("req-7"))
        .andExpect(jsonPath("$.data.issuedAt").value("2026-03-30T00:00:00Z"))
        .andExpect(jsonPath("$.data.expiresAt").value("2026-03-30T00:00:30Z"))
        .andExpect(jsonPath("$.data.replayed").value(false))
        .andExpect(
            header()
                .string(HttpHeaders.SET_COOKIE, containsString("Firemud-Connect-Token=conn123")))
        .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
        .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
        .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
        .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/ws/game")))
        .andExpect(
            header()
                .string(
                    HttpHeaders.SET_COOKIE, matchesPattern(".*(?:^|;\\s*)Max-Age=30(?:;|$).*")));
  }

  @Test
  void listBootstrapWorldsReturnsVisibleWorlds() throws Exception {
    when(accountService.listBootstrapWorlds("boot123"))
        .thenReturn(List.of(new BootstrapWorldDto("demo", "Demo World")));

    mockMvc
        .perform(get("/auth/bootstrap/worlds").header(HttpHeaders.AUTHORIZATION, "Bearer boot123"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].worldSlug").value("demo"));
  }

  @Test
  void listBootstrapRealmsReturnsVisibleRealms() throws Exception {
    when(accountService.listBootstrapRealms("boot123", "demo"))
        .thenReturn(
            List.of(
                new BootstrapRealmDto(
                    "demo",
                    "production",
                    "Live Realm",
                    1L,
                    42L,
                    17L,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    "2026-03-30T00:00:00Z",
                    "2026-03-30T00:02:00Z",
                    "scope-1")));

    mockMvc
        .perform(
            get("/auth/bootstrap/worlds/demo/realms")
                .header(HttpHeaders.AUTHORIZATION, "Bearer boot123"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].realmSlug").value("production"))
        .andExpect(jsonPath("$.data[0].connectScopeId").value("scope-1"));
  }

  @Test
  void listBootstrapCharactersReturnsCharacters() throws Exception {
    when(accountService.listBootstrapCharacters("boot123", "demo", "production", "scope-1"))
        .thenReturn(
            List.of(new BootstrapCharacterDto("char-1", "Mara", 12, "SHARED", "ALLOW_NEW")));

    mockMvc
        .perform(
            get("/auth/bootstrap/worlds/demo/realms/production/characters")
                .param("connectScopeId", "scope-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer boot123"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].characterId").value("char-1"))
        .andExpect(jsonPath("$.data[0].characterName").value("Mara"));
  }

  @Test
  void listBootstrapCharactersRejectsMissingConnectScopeIdBeforeDispatch() throws Exception {
    mockMvc
        .perform(
            get("/auth/bootstrap/worlds/demo/realms/production/characters")
                .header(HttpHeaders.AUTHORIZATION, "Bearer boot123"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(accountService);
  }

  @Test
  void requestPasswordResetReturnsSuccess() throws Exception {
    PasswordResetRequest req = new PasswordResetRequest("demo@example.com");

    mockMvc
        .perform(
            post("/auth/request-password-reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }

  @Test
  void requestEmailVerificationReturnsSuccess() throws Exception {
    net.firedevops.firemud.accountservice.dto.AccountIdRequest req =
        new net.firedevops.firemud.accountservice.dto.AccountIdRequest(2L);

    mockMvc
        .perform(
            post("/auth/request-email-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }

  @Test
  void requestEmailVerificationRejectsZeroAccountIdBeforeDispatch() throws Exception {
    net.firedevops.firemud.accountservice.dto.AccountIdRequest req =
        new net.firedevops.firemud.accountservice.dto.AccountIdRequest(0L);

    mockMvc
        .perform(
            post("/auth/request-email-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("accountId must be positive"));

    verifyNoInteractions(accountService);
  }

  @Test
  void verifyEmailReturnsSuccess() throws Exception {
    net.firedevops.firemud.accountservice.dto.VerifyEmailRequest req =
        new net.firedevops.firemud.accountservice.dto.VerifyEmailRequest("tok");

    mockMvc
        .perform(
            post("/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }
}
