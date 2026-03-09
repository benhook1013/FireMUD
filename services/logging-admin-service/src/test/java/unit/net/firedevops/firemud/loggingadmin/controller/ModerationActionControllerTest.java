package net.firedevops.firemud.loggingadmin.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.loggingadmin.config.AuthConfig;
import net.firedevops.firemud.loggingadmin.dto.ApplyModerationActionRequest;
import net.firedevops.firemud.loggingadmin.dto.ModerationActionDto;
import net.firedevops.firemud.loggingadmin.service.ModerationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ModerationActionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AuthConfig.class)
@TestPropertySource(
    properties = {
      "firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234",
      "firemud.auth.jwt-expiration-ms=3600000"
    })
class ModerationActionControllerTest {

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final JwtUtil jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 3600000);

  @MockitoBean private ModerationService service;

  @Test
  void applyReturnsDto() throws Exception {
    ApplyModerationActionRequest req = new ApplyModerationActionRequest(1L, 2L, "ban", "");
    ModerationActionDto dto =
        new ModerationActionDto(1L, 1L, 2L, "ban", "", java.time.Instant.now(), null);
    when(service.applyAction(req)).thenReturn(dto);

    mockMvc
        .perform(
            post("/moderation/actions")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer "
                        + jwtUtil.generateToken(
                            "user",
                            java.util.Map.of("globalRoles", java.util.List.of("platformAdmin"))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }
}
