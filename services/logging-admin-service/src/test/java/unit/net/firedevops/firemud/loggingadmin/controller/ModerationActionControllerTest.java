package net.firedevops.firemud.loggingadmin.controller;

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
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.ApplyModerationActionRequest;
import net.firedevops.firemud.loggingadmin.service.ModerationService;
import net.firedevops.firemud.test.WithFiremudPrivilegedHttpAuthTestProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(ModerationActionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
  CommonSecurityAutoConfiguration.class,
  CommonSecurityServletAutoConfiguration.class,
  GlobalExceptionHandler.class
})
@WithFiremudPrivilegedHttpAuthTestProperties
class ModerationActionControllerTest {

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private ModerationService service;
  @Autowired private JwtUtil jwtUtil;

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void applyRejectsAuthorizedCallerWhileMutationGateIsUnavailable() throws Exception {
    ApplyModerationActionRequest req = new ApplyModerationActionRequest(1L, 2L, 9L, "ban", "");
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            post("/moderation/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("ERROR"))
        .andExpect(jsonPath("$.error.code").value("MODERATION_ACTION_UNAVAILABLE"))
        .andExpect(
            jsonPath("$.error.message")
                .value(
                    "Moderation actions are unavailable until the shared mutation gate is implemented"));

    verifyNoInteractions(service);
  }

  @Test
  void applyRejectsCrossTenantScopedAdmin() throws Exception {
    ApplyModerationActionRequest req = new ApplyModerationActionRequest(1L, 2L, 9L, "ban", "");
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("moderator"))));

    mockMvc
        .perform(
            post("/moderation/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void applyRejectsZeroSessionIdBeforeDispatch() throws Exception {
    ApplyModerationActionRequest req = new ApplyModerationActionRequest(1L, 2L, 0L, "ban", "");
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            post("/moderation/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("sessionId must be positive"));

    verifyNoInteractions(service);
  }
}
