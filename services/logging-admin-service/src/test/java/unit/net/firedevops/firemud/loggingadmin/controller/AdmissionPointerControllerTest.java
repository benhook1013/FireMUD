package net.firedevops.firemud.loggingadmin.controller;

import static org.mockito.Mockito.verifyNoInteractions;
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
import net.firedevops.firemud.loggingadmin.dto.ExecutePreparedVersionCutoverRequest;
import net.firedevops.firemud.loggingadmin.dto.GameInstanceRuntimeStateDto;
import net.firedevops.firemud.loggingadmin.dto.InstanceCutoverCompatibilityDto;
import net.firedevops.firemud.loggingadmin.dto.PrepareVersionUpgradeRequest;
import net.firedevops.firemud.loggingadmin.dto.PreparedVersionUpgradeDto;
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
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    "42",
                    "cutover",
                    "req-1",
                    "pvu-1",
                    Instant.parse("2026-04-18T00:00:00Z"))));
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(get("/admission-pointers").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
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
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            "cutover",
            "req-1",
            3L,
            null);
    SessionContext.setContext("user", List.of(), Map.of("8", List.of("tenantAdmin")));
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            post("/admission-pointers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void auditReturnsEntries() throws Exception {
    when(admissionPointerService.listPointerAudit(2L, "demo", "production"))
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
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    "42",
                    "cutover",
                    "req-1",
                    "pvu-1",
                    Instant.parse("2026-04-18T00:00:00Z"))));
    SessionContext.setContext("user", List.of("platformAdmin"), Map.of());
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/admission-pointers/2/demo/production/audit")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].pointerVersion").value(3));
  }

  @Test
  void auditRejectsCrossTenantScopedAdmin() throws Exception {
    SessionContext.setContext("user", List.of(), Map.of("8", List.of("tenantAdmin")));
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            get("/admission-pointers/2/demo/production/audit")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void auditRejectsMalformedTenantIdBeforeDispatch() throws Exception {
    SessionContext.setContext("user", List.of("platformAdmin"), Map.of());
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/admission-pointers/not-a-number/demo/production/audit")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("tenantId must be numeric"));

    verifyNoInteractions(admissionPointerService);
  }

  @Test
  void getRuntimeStateReturnsCanonicalRuntimeState() throws Exception {
    when(admissionPointerService.getRuntimeState(2L, 7L)).thenReturn(runtimeStateDto());
    SessionContext.setContext("user", List.of("platformAdmin"), Map.of());
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/admission-pointers/runtime-state/2/7")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tenantId").value(2))
        .andExpect(jsonPath("$.data.gameInstanceId").value(7))
        .andExpect(jsonPath("$.data.worldSlug").value("demo"))
        .andExpect(jsonPath("$.data.currentAdmissionPointers[0].realmSlug").value("production"));
  }

  @Test
  void getRuntimeStateRejectsCrossTenantScopedAdmin() throws Exception {
    SessionContext.setContext("user", List.of(), Map.of("8", List.of("tenantAdmin")));
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            get("/admission-pointers/runtime-state/2/7")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void getRuntimeStateRejectsZeroGameInstanceIdBeforeDispatch() throws Exception {
    SessionContext.setContext("user", List.of("platformAdmin"), Map.of());
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/admission-pointers/runtime-state/2/0")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("gameInstanceId must be positive"));

    verifyNoInteractions(admissionPointerService);
  }

  @Test
  void executePreparedVersionCutoverRejectsCrossTenantScopedAdmin() throws Exception {
    ExecutePreparedVersionCutoverRequest request =
        new ExecutePreparedVersionCutoverRequest(
            "demo", "production", 2L, 7L, "pvu-1", "cutover", "req-1", 3L);
    SessionContext.setContext("user", List.of(), Map.of("8", List.of("tenantAdmin")));
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            post("/admission-pointers/cutover")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void prepareVersionUpgradeReturnsPreparation() throws Exception {
    SessionContext.setContext("user", List.of("platformAdmin"), Map.of());
    when(admissionPointerService.prepareVersionUpgrade(
            new PrepareVersionUpgradeRequest(2L, 7L, 9L, "req-prepare")))
        .thenReturn(preparedUpgrade());
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            post("/admission-pointers/version-upgrades")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new PrepareVersionUpgradeRequest(2L, 7L, 9L, "req-prepare")))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.preparationId").value("pvu-1"))
        .andExpect(jsonPath("$.data.executedTargetGameInstanceId").value(55));
  }

  @Test
  void getPreparedVersionUpgradeRejectsCrossTenantScopedAdmin() throws Exception {
    SessionContext.setContext("user", List.of(), Map.of("8", List.of("tenantAdmin")));
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            get("/admission-pointers/version-upgrades/2/pvu-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void validateInstanceCutoverCompatibilityReturnsBoundedCompatibilityProof() throws Exception {
    when(admissionPointerService.validateInstanceCutoverCompatibility(2L, 7L, 9L))
        .thenReturn(cutoverCompatibility());
    SessionContext.setContext("user", List.of("platformAdmin"), Map.of());
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/admission-pointers/version-upgrades/2/7/compatibility/9")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.result").value("COMPATIBLE"))
        .andExpect(jsonPath("$.data.remapSetId").value("remap-1"))
        .andExpect(jsonPath("$.data.participantResults[0].participant").value("entity"));
  }

  @Test
  void validateInstanceCutoverCompatibilityRejectsCrossTenantScopedAdmin() throws Exception {
    SessionContext.setContext("user", List.of(), Map.of("8", List.of("tenantAdmin")));
    String token =
        jwtUtil.generateToken("user", Map.of("scopedRoles", Map.of("8", List.of("tenantAdmin"))));

    mockMvc
        .perform(
            get("/admission-pointers/version-upgrades/2/7/compatibility/9")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void validateInstanceCutoverCompatibilityRejectsMalformedTargetVersionIdBeforeDispatch()
      throws Exception {
    SessionContext.setContext("user", List.of("platformAdmin"), Map.of());
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(
            get("/admission-pointers/version-upgrades/2/7/compatibility/not-a-number")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.error.message").value("targetVersionId must be numeric"));

    verifyNoInteractions(admissionPointerService);
  }

  private PreparedVersionUpgradeDto preparedUpgrade() {
    return new PreparedVersionUpgradeDto(
        "pvu-1",
        2L,
        7L,
        6L,
        9L,
        77L,
        "remap-1",
        "COMPATIBLE",
        List.of("checked"),
        List.of("entity"),
        Instant.parse("2026-04-18T00:00:00Z"),
        List.of(
            new PreparedVersionUpgradeDto.CutoverParticipantResultDto(
                "entity",
                "COMPATIBLE",
                List.of(),
                List.of("S3"),
                List.of("room_ground_inventory"),
                false)),
        "req-prepare",
        55L,
        4L,
        Instant.parse("2026-04-18T00:00:01Z"),
        "req-cutover");
  }

  private InstanceCutoverCompatibilityDto cutoverCompatibility() {
    return new InstanceCutoverCompatibilityDto(
        "COMPATIBLE",
        List.of("checked"),
        List.of("entity"),
        Instant.parse("2026-04-18T00:00:00Z"),
        "remap-1",
        List.of(
            new InstanceCutoverCompatibilityDto.CutoverParticipantResultDto(
                "entity",
                "COMPATIBLE",
                List.of("clean"),
                List.of("S3"),
                List.of("room_ground_inventory"),
                false)));
  }

  private GameInstanceRuntimeStateDto runtimeStateDto() {
    return new GameInstanceRuntimeStateDto(
        2L,
        7L,
        "runtime-v7",
        "patch-2",
        "ld-9",
        "RUNNING",
        11L,
        19L,
        77L,
        Instant.parse("2026-04-22T00:00:00Z"),
        "operator-1",
        "roll-forward",
        "req-77",
        "PLAYABLE_STATE_SCOPE_SHARED",
        "demo",
        "production",
        11L,
        new GameInstanceRuntimeStateDto.ScriptPatchPublicationLinkDto(
            "patch-2",
            17L,
            7L,
            "VERSION_LIFECYCLE_STATE_PUBLISHED",
            Instant.parse("2026-04-22T00:00:01Z"),
            "",
            ""),
        "region-7",
        22L,
        List.of(
            new AdmissionPointerDto(
                "demo",
                "Demo World",
                "production",
                "Live Realm",
                2L,
                7L,
                11L,
                true,
                true,
                false,
                "SHARED",
                "ALLOW_NEW",
                "",
                "",
                "",
                null,
                null)));
  }
}
