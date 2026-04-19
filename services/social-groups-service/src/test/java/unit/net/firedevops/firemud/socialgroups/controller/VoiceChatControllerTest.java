package net.firedevops.firemud.socialgroups.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.socialgroups.dto.VoiceTokenDto;
import net.firedevops.firemud.socialgroups.security.SocialAccessGuard;
import net.firedevops.firemud.socialgroups.service.VoiceChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(VoiceChatController.class)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@TestPropertySource(
    properties = {
      "firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234",
      "firemud.auth.jwt-expiration-ms=3600000",
      "firemud.auth.http.enabled=true"
    })
class VoiceChatControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;

  @MockitoBean private VoiceChatService service;
  @MockitoBean private SocialAccessGuard socialAccessGuard;

  @Test
  void createTokenReturnsToken() throws Exception {
    when(service.createToken(any())).thenReturn(new VoiceTokenDto("abc", Instant.now()));
    String token = jwtUtil.generateToken("2", Map.of("accountId", "2", "globalRoles", List.of()));
    String body = "{\"tenantId\":1,\"accountId\":2,\"channelId\":\"guild-1\"}";

    mockMvc
        .perform(
            post("/voice/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }
}
