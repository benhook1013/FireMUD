package net.firedevops.firemud.loggingadmin.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.loggingadmin.config.AuthConfig;
import net.firedevops.firemud.loggingadmin.dto.QueryLogsRequest;
import net.firedevops.firemud.loggingadmin.service.LogQueryService;
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

@WebMvcTest(LogQueryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AuthConfig.class)
@TestPropertySource(
    properties = {
      "firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234",
      "firemud.auth.jwt-expiration-ms=3600000"
    })
class LogQueryControllerTest {

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final JwtUtil jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 3600000);

  @MockitoBean private LogQueryService service;

  @Test
  void queryReturnsEntries() throws Exception {
    QueryLogsRequest request = new QueryLogsRequest(1L, "msg");
    when(service.queryLogs(request)).thenReturn(List.of("hello"));

    mockMvc
        .perform(
            get("/logs")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer "
                        + jwtUtil.generateToken(
                            "user",
                            java.util.Map.of("globalRoles", java.util.List.of("platformAdmin"))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0]").value("hello"));
  }
}
