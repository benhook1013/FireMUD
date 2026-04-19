package net.firedevops.firemud.loggingadmin.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.FeatureFlagDto;
import net.firedevops.firemud.loggingadmin.dto.ToggleFeatureFlagRequest;
import net.firedevops.firemud.loggingadmin.service.FeatureFlagService;
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

@WebMvcTest(FeatureFlagController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@WithFiremudPrivilegedHttpAuthTestProperties
class FeatureFlagControllerTest {

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private FeatureFlagService service;
  @Autowired private JwtUtil jwtUtil;

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void toggleReturnsDto() throws Exception {
    ToggleFeatureFlagRequest request = new ToggleFeatureFlagRequest(1L, "demo", true);
    FeatureFlagDto dto = new FeatureFlagDto(1L, 1L, "demo", true);
    when(service.toggleFlag(request)).thenReturn(dto);
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            post("/feature-flags/toggle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.enabled").value(true));
  }

  @Test
  void toggleRejectsCrossTenantScopedAdmin() throws Exception {
    ToggleFeatureFlagRequest request = new ToggleFeatureFlagRequest(1L, "demo", true);
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            post("/feature-flags/toggle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }
}
