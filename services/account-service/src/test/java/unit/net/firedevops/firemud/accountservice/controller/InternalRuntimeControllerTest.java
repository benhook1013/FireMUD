package net.firedevops.firemud.accountservice.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.firedevops.firemud.accountservice.dto.RealmAccessGrantRequest;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.test.WithFiremudPrivilegedHttpAuthTestProperties;
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

@WebMvcTest(InternalRuntimeController.class)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@WithFiremudPrivilegedHttpAuthTestProperties
class InternalRuntimeControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;

  @MockitoBean private AccountService accountService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void grantRealmAccessRejectsZeroAccountIdBeforeDispatch() throws Exception {
    String token =
        jwtUtil.generateToken(
            "user", java.util.Map.of("globalRoles", java.util.List.of("platformAdmin")));

    mockMvc
        .perform(
            post("/internal/runtime/realm-access-grants")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "accountId": 0,
                      "tenantId": 7,
                      "worldSlug": "demo",
                      "realmSlug": "production",
                      "grantedBy": "operator",
                      "grantReason": "test",
                      "requestId": "req-2"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("accountId must be positive"));

    verifyNoInteractions(accountService);
  }

  @Test
  void revokeRealmAccessRejectsMalformedAccountIdBeforeDispatch() throws Exception {
    String token =
        jwtUtil.generateToken(
            "user", java.util.Map.of("globalRoles", java.util.List.of("platformAdmin")));

    mockMvc
        .perform(
            delete("/internal/runtime/realm-access-grants")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .param("accountId", "not-a-number")
                .param("tenantId", "7")
                .param("worldSlug", "demo")
                .param("realmSlug", "production"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("accountId must be numeric"));

    verifyNoInteractions(accountService);
  }

  @Test
  void validPrivilegedGrantMutationsFailClosedBeforeDispatch() throws Exception {
    for (String role : java.util.List.of("platformAdmin", "moderator")) {
      String token = jwtTokenWithGlobalRole(role);

      mockMvc
          .perform(
              post("/internal/runtime/realm-access-grants")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(validGrantRequestBody()))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.error.code").value("AUTH_UNAVAILABLE"))
          .andExpect(
              jsonPath("$.error.message")
                  .value("Lifecycle-qualified realm-grant mutations are unavailable"));

      mockMvc
          .perform(
              delete("/internal/runtime/realm-access-grants")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                  .param("accountId", "11")
                  .param("tenantId", "7")
                  .param("worldSlug", "demo")
                  .param("realmSlug", "preview"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.error.code").value("AUTH_UNAVAILABLE"))
          .andExpect(
              jsonPath("$.error.message")
                  .value("Lifecycle-qualified realm-grant mutations are unavailable"));
    }

    verifyNoInteractions(accountService);
  }

  @Test
  void missingOrNonprivilegedAuthenticationStillDeniesGrantMutations() throws Exception {
    mockMvc
        .perform(
            post("/internal/runtime/realm-access-grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validGrantRequestBody()))
        .andExpect(status().isUnauthorized());

    mockMvc
        .perform(
            delete("/internal/runtime/realm-access-grants")
                .param("accountId", "11")
                .param("tenantId", "7")
                .param("worldSlug", "demo")
                .param("realmSlug", "preview"))
        .andExpect(status().isUnauthorized());

    String playerToken = jwtTokenWithGlobalRole("player");
    mockMvc
        .perform(
            post("/internal/runtime/realm-access-grants")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + playerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validGrantRequestBody()))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            delete("/internal/runtime/realm-access-grants")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenWithGlobalRole("player"))
                .param("accountId", "11")
                .param("tenantId", "7")
                .param("worldSlug", "demo")
                .param("realmSlug", "preview"))
        .andExpect(status().isForbidden());

    verifyNoInteractions(accountService);
  }

  private String jwtTokenWithGlobalRole(String role) {
    return jwtUtil.generateToken("user", java.util.Map.of("globalRoles", java.util.List.of(role)));
  }

  private String validGrantRequestBody() throws Exception {
    return objectMapper.writeValueAsString(
        new RealmAccessGrantRequest(
            11L, 7L, "demo", "preview", "operator", "preview access", "request-1"));
  }
}
