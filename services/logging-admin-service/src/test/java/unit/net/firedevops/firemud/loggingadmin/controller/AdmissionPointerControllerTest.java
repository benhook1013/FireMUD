package net.firedevops.firemud.loggingadmin.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.AdmissionPointerDto;
import net.firedevops.firemud.loggingadmin.dto.SetAdmissionPointerRequest;
import net.firedevops.firemud.loggingadmin.service.AdmissionPointerService;
import net.firedevops.firemud.test.WithFiremudJwtTestProperties;
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

@WebMvcTest(AdmissionPointerController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({CommonSecurityAutoConfiguration.class, CommonSecurityServletAutoConfiguration.class})
@WithFiremudJwtTestProperties
class AdmissionPointerControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private AdmissionPointerService admissionPointerService;

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void listPointersReturnsVisibleEntries() throws Exception {
    when(admissionPointerService.listPointers())
        .thenReturn(
            List.of(
                new AdmissionPointerDto(
                    "demo",
                    "Demo World",
                    "production",
                    "Live Realm",
                    2L,
                    7L,
                    3L,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    "42",
                    "cutover",
                    "req-1",
                    Instant.parse("2026-04-18T00:00:00Z"))));
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/admin/admission-pointers").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].worldSlug").value("demo"))
        .andExpect(jsonPath("$.data[0].tenantId").value(2));
  }

  @Test
  void setPointerRejectsCrossTenantScopedAdmin() throws Exception {
    SetAdmissionPointerRequest request =
        new SetAdmissionPointerRequest(
            "demo",
            "Demo World",
            "production",
            "Live Realm",
            2L,
            7L,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            "cutover",
            "req-1",
            3L);
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            post("/admin/admission-pointers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void auditReturnsEntries() throws Exception {
    when(admissionPointerService.listPointerAudit("demo", "production"))
        .thenReturn(
            List.of(
                new AdmissionPointerDto(
                    "demo",
                    "Demo World",
                    "production",
                    "Live Realm",
                    2L,
                    7L,
                    3L,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    "42",
                    "cutover",
                    "req-1",
                    Instant.parse("2026-04-18T00:00:00Z"))));
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/admin/admission-pointers/demo/production/audit")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].pointerVersion").value(3));
  }
}
