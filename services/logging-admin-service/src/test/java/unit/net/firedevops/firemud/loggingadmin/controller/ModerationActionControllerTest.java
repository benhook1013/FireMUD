package net.firedevops.firemud.loggingadmin.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
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

  private static final String ACCOUNT_ID = "b8d093f7-cb70-40ed-9fac-3c82d4bf28f1";

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
    ApplyModerationActionRequest req =
        new ApplyModerationActionRequest(1L, "b8d093f7-cb70-40ed-9fac-3c82d4bf28f1", 9L, "ban", "");
    String token =
        jwtUtil.generateToken(
            ACCOUNT_ID, Map.of("accountId", ACCOUNT_ID, "globalRoles", List.of("platformAdmin")));

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
    ApplyModerationActionRequest req =
        new ApplyModerationActionRequest(1L, "b8d093f7-cb70-40ed-9fac-3c82d4bf28f1", 9L, "ban", "");
    String token =
        jwtUtil.generateToken(
            ACCOUNT_ID,
            Map.of("accountId", ACCOUNT_ID, "scopedRoles", Map.of("8", List.of("moderator"))));

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
    ApplyModerationActionRequest req =
        new ApplyModerationActionRequest(1L, "b8d093f7-cb70-40ed-9fac-3c82d4bf28f1", 0L, "ban", "");
    String token =
        jwtUtil.generateToken(
            ACCOUNT_ID, Map.of("accountId", ACCOUNT_ID, "globalRoles", List.of("platformAdmin")));

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

  @Test
  void applyRejectsInvalidAccountUuidDuringJsonDeserialization() throws Exception {
    String token =
        jwtUtil.generateToken(
            ACCOUNT_ID, Map.of("accountId", ACCOUNT_ID, "globalRoles", List.of("platformAdmin")));
    Map<String, Object> request =
        new HashMap<>(
            Map.of(
                "tenantId",
                1L,
                "accountId",
                ACCOUNT_ID,
                "sessionId",
                9L,
                "action",
                "ban",
                "reason",
                ""));
    Object[] invalidAccountIds = {
      123L, "not-a-uuid", "00000000-0000-0000-0000-000000000000", ACCOUNT_ID.toUpperCase(), null
    };

    for (Object invalidAccountId : invalidAccountIds) {
      request.put("accountId", invalidAccountId);
      mockMvc
          .perform(
              post("/moderation/actions")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request))
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.status").value("ERROR"))
          .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
          .andExpect(jsonPath("$.error.message").value("Request body is malformed"));
    }

    verifyNoInteractions(service);
  }
}
