package net.firedevops.firemud.socialgroups.controller;

import static net.firedevops.firemud.socialgroups.test.AccountUuidFixtures.accountUuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.GlobalExceptionHandler;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.socialgroups.dto.MailMessageDto;
import net.firedevops.firemud.socialgroups.dto.SendMailRequest;
import net.firedevops.firemud.socialgroups.security.SocialAccessGuard;
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
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(MailController.class)
@Import({
  GlobalExceptionHandler.class,
  CommonSecurityAutoConfiguration.class,
  CommonSecurityServletAutoConfiguration.class
})
@WithFiremudHttpAuthTestProperties
class MailControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private MailService mailService;
  @MockitoBean private SocialAccessGuard socialAccessGuard;

  @Test
  void sendMailReturnsDto() throws Exception {
    SendMailRequest request =
        new SendMailRequest(1L, accountUuid(2L), accountUuid(3L), "hello", "test body");
    MailMessageDto response =
        new MailMessageDto(
            1L, 1L, accountUuid(2L), accountUuid(3L), "hello", "test body", null, null);
    when(mailService.sendMail(request)).thenReturn(response);

    String token =
        jwtUtil.generateToken(
            accountUuid(2L), Map.of("accountId", accountUuid(2L), "globalRoles", List.of()));
    mockMvc
        .perform(
            post("/mail")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.subject").value("hello"));
  }

  @Test
  void sendMailRejectsZeroSenderAccountIdBeforeAccessCheckAndDispatch() throws Exception {
    String token =
        jwtUtil.generateToken(
            accountUuid(2L), Map.of("accountId", accountUuid(2L), "globalRoles", List.of()));

    mockMvc
        .perform(
            post("/mail")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .content(
                    """
                    {"tenantId":1,"senderAccountId":0,"recipientAccountId":"10000000-0000-4000-8000-000000000003","subject":"hello","content":"test body"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("ERROR"))
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("Request body is malformed"));

    verifyNoInteractions(mailService, socialAccessGuard);
  }

  @Test
  void sendMailRejectsSenderImpersonationBeforeAccessCheckAndDispatch() throws Exception {
    String token =
        jwtUtil.generateToken(
            accountUuid(2L),
            Map.of(
                "accountId", accountUuid(2L),
                "globalRoles", List.of(),
                "scopedRoles", Map.of("1", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            post("/mail")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .content(
                    """
                    {"tenantId":1,"senderAccountId":"10000000-0000-4000-8000-000000000003","recipientAccountId":"10000000-0000-4000-8000-000000000004","subject":"hello","content":"test body"}
                    """))
        .andExpect(status().isForbidden());

    verifyNoInteractions(mailService, socialAccessGuard);
  }

  @Test
  void malformedCallerIdentityIsForbiddenBeforeAccessChecksOrMailDispatch() {
    SendMailRequest request =
        new SendMailRequest(1L, accountUuid(2L), accountUuid(3L), "hello", "test body");
    MailController controller = new MailController(mailService, socialAccessGuard);
    for (String caller :
        List.of(
            "2",
            "not-an-account",
            "00000000-0000-0000-0000-000000000000",
            "A0000000-0000-4000-8000-000000000002")) {
      SessionContext.setContext(caller, List.of(), Map.of());
      try {
        assertThatThrownBy(() -> controller.sendMail(request))
            .isInstanceOfSatisfying(
                ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(mailService, socialAccessGuard);
      } finally {
        SessionContext.clear();
      }
    }
  }
}
