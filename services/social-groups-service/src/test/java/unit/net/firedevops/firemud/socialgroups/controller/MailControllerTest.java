package net.firedevops.firemud.socialgroups.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.GlobalExceptionHandler;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.socialgroups.dto.SendMailRequest;
import net.firedevops.firemud.socialgroups.service.MailService;
import net.firedevops.firemud.test.WithFiremudHttpAuthTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(MailController.class)
@Import({
  GlobalExceptionHandler.class,
  CommonSecurityAutoConfiguration.class,
  CommonSecurityServletAutoConfiguration.class
})
@WithFiremudHttpAuthTestProperties
class MailControllerTest {
  private static final String ACCOUNT_UUID = "c41744c9-285e-4ed0-9fb4-0f0acb7a0123";
  private static final String RECIPIENT_UUID = "d52755da-396f-4fd1-80c5-1f1bcb8b1234";

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private MailService mailService;

  @Test
  void sendMailAllowsCanonicalAuthenticatedSenderAndRecipient() throws Exception {
    SendMailRequest request =
        new SendMailRequest(1L, ACCOUNT_UUID, RECIPIENT_UUID, "hello", "test body");
    String token = accountToken();
    mockMvc
        .perform(
            post("/mail")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk());

    verify(mailService).sendMail(request);
  }

  @Test
  void sendMailRejectsLegacyNumericSenderSelectorBeforeDispatch() throws Exception {
    String token = accountToken();

    mockMvc
        .perform(
            post("/mail")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .content(
                    "{\"tenantId\":1,\"senderAccountId\":\"2\",\"recipientAccountId\":\"%s\",\"subject\":\"hello\",\"content\":\"test body\"}"
                        .formatted(RECIPIENT_UUID)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("ERROR"))
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("Malformed claim: senderAccountId"));

    verifyNoInteractions(mailService);
  }

  @Test
  void tenantRoleCannotSendMailAsAnotherAccount() throws Exception {
    String token =
        jwtUtil.generateToken(
            ACCOUNT_UUID,
            Map.of(
                "accountId", ACCOUNT_UUID,
                "globalRoles", List.of(),
                "scopedRoles", Map.of("1", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            post("/mail")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .content(
                    "{\"tenantId\":1,\"senderAccountId\":\"%s\",\"recipientAccountId\":\"%s\",\"subject\":\"hello\",\"content\":\"test body\"}"
                        .formatted(RECIPIENT_UUID, ACCOUNT_UUID)))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.error.message").value("Mail sender must match the authenticated account"));

    verifyNoInteractions(mailService);
  }

  private String accountToken() {
    return jwtUtil.generateToken(
        ACCOUNT_UUID, Map.of("accountId", ACCOUNT_UUID, "globalRoles", List.of()));
  }
}
