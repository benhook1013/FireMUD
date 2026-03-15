package net.firedevops.firemud.accountservice.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.firedevops.firemud.accountservice.dto.AuthenticationResult;
import net.firedevops.firemud.accountservice.dto.LoginRequest;
import net.firedevops.firemud.accountservice.dto.PasswordResetRequest;
import net.firedevops.firemud.accountservice.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private AccountService accountService;

  @Test
  void loginReturnsTokenAndAccountId() throws Exception {
    LoginRequest request = new LoginRequest(1L, "demo", "password", null);
    when(accountService.authenticate(1L, "demo", "password", null))
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
  void requestPasswordResetReturnsSuccess() throws Exception {
    PasswordResetRequest req = new PasswordResetRequest(1L, "demo@example.com");

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
    net.firedevops.firemud.accountservice.dto.AccountRefRequest req =
        new net.firedevops.firemud.accountservice.dto.AccountRefRequest(1L, 2L);

    mockMvc
        .perform(
            post("/auth/request-email-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }

  @Test
  void verifyEmailReturnsSuccess() throws Exception {
    net.firedevops.firemud.accountservice.dto.VerifyEmailRequest req =
        new net.firedevops.firemud.accountservice.dto.VerifyEmailRequest(1L, "tok");

    mockMvc
        .perform(
            post("/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }
}
