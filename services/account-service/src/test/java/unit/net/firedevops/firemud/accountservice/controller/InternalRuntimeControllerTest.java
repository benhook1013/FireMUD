package net.firedevops.firemud.accountservice.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.firedevops.firemud.accountservice.dto.PublicProductionMembershipRequest;
import net.firedevops.firemud.accountservice.dto.PublicProductionMembershipResult;
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
  void ensurePublicProductionMembershipReturnsResult() throws Exception {
    String token =
        jwtUtil.generateToken(
            "user", java.util.Map.of("globalRoles", java.util.List.of("platformAdmin")));
    PublicProductionMembershipRequest request =
        new PublicProductionMembershipRequest(11L, 7L, "demo", "production", "req-1");
    when(accountService.ensurePublicProductionPlayerMembership(
            11L, 7L, "demo", "production", "req-1"))
        .thenReturn(
            new PublicProductionMembershipResult(
                11L, 7L, "demo", "production", 711L, true, "req-1", "2026-04-13T10:00:00Z", false));

    mockMvc
        .perform(
            post("/internal/runtime/public-production-membership")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.membershipVersion").value(711))
        .andExpect(jsonPath("$.data.created").value(true))
        .andExpect(jsonPath("$.data.requestId").value("req-1"))
        .andExpect(jsonPath("$.data.replayed").value(false));
  }
}
