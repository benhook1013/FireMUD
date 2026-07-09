package net.firedevops.firemud.loggingadmin.controller;

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
import net.firedevops.firemud.loggingadmin.dto.QueryLogsRequest;
import net.firedevops.firemud.loggingadmin.service.LogQueryService;
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

@WebMvcTest(LogQueryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
  CommonSecurityAutoConfiguration.class,
  CommonSecurityServletAutoConfiguration.class,
  GlobalExceptionHandler.class
})
@WithFiremudPrivilegedHttpAuthTestProperties
class LogQueryControllerTest {

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private LogQueryService service;
  @Autowired private JwtUtil jwtUtil;

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void queryReturnsEntries() throws Exception {
    QueryLogsRequest request = new QueryLogsRequest(1L, "msg");
    when(service.queryLogs(request)).thenReturn(List.of("hello"));
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            post("/logs/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0]").value("hello"));
  }

  @Test
  void queryRejectsCrossTenantScopedAdmin() throws Exception {
    QueryLogsRequest request = new QueryLogsRequest(1L, "msg");
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            post("/logs/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void queryRejectsZeroTenantIdBeforeDispatch() throws Exception {
    QueryLogsRequest request = new QueryLogsRequest(0L, "msg");
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            post("/logs/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be positive"));

    verifyNoInteractions(service);
  }
}
