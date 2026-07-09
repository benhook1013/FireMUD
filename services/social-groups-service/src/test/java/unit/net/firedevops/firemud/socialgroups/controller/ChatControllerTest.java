package net.firedevops.firemud.socialgroups.controller;

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
import net.firedevops.firemud.socialgroups.security.SocialAccessGuard;
import net.firedevops.firemud.socialgroups.service.ChatService;
import net.firedevops.firemud.test.WithFiremudHttpAuthTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChatController.class)
@Import({
  GlobalExceptionHandler.class,
  CommonSecurityAutoConfiguration.class,
  CommonSecurityServletAutoConfiguration.class
})
@WithFiremudHttpAuthTestProperties
class ChatControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;

  @MockitoBean private ChatService chatService;
  @MockitoBean private SocialAccessGuard socialAccessGuard;

  @Test
  void sendMessageRejectsZeroTenantIdBeforeAccessCheckAndDispatch() throws Exception {
    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));
    String body =
        """
        {"tenantId":0,"senderAccountId":2,"type":"SAY","content":"hello"}
        """;

    mockMvc
        .perform(
            post("/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("ERROR"))
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be greater than 0"));

    verifyNoInteractions(chatService, socialAccessGuard);
  }
}
