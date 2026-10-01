package net.firedevops.firemud.loggingadmin.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.GlobalExceptionHandler;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.AdmissionPointerDto;
import net.firedevops.firemud.loggingadmin.dto.GameInstanceRuntimeStateDto;
import net.firedevops.firemud.loggingadmin.dto.InstanceCutoverCompatibilityDto;
import net.firedevops.firemud.loggingadmin.service.AdmissionPointerService;
import net.firedevops.firemud.test.WithFiremudJwtTestProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.Yaml;

@WebMvcTest(AdmissionPointerController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
  CommonSecurityAutoConfiguration.class,
  CommonSecurityServletAutoConfiguration.class,
  GlobalExceptionHandler.class
})
@WithFiremudJwtTestProperties
class AdmissionPointerControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;

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
                    5L,
                    UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
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
        .andExpect(jsonPath("$.data[0].tenantId").value(2))
        .andExpect(jsonPath("$.data[0].catalogRevision").value(5))
        .andExpect(jsonPath("$.data[0].realmId").value("11111111-1111-1111-1111-111111111111"))
        .andExpect(
            jsonPath("$.data[0].playableStateNamespaceId")
                .value("22222222-2222-2222-2222-222222222222"));
  }

  @Test
  void listPointersMapsUnavailableCurrentAuthorityToServiceUnavailable() throws Exception {
    when(admissionPointerService.listPointers())
        .thenThrow(
            new ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "current pointer authority unavailable"));
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));

    mockMvc
        .perform(get("/admission-pointers").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
        .andExpect(jsonPath("$.error.message").value("current pointer authority unavailable"));
  }

  @Test
  void openApiDocumentsAdmissionPointerRead503Responses() throws Exception {
    Map<?, ?> document;
    try (var input = getClass().getResourceAsStream("/openapi.yaml")) {
      assertNotNull(input);
      document = new Yaml().load(input);
    }

    Map<?, ?> paths = (Map<?, ?>) document.get("paths");
    for (String path :
        List.of(
            "/admission-pointers",
            "/admission-pointers/runtime-state/{tenantId}/{gameInstanceId}")) {
      Map<?, ?> admissionPointers = (Map<?, ?>) paths.get(path);
      Map<?, ?> getOperation = (Map<?, ?>) admissionPointers.get("get");
      Map<?, ?> responses = (Map<?, ?>) getOperation.get("responses");
      Map<?, ?> success = (Map<?, ?>) responses.get("200");
      Map<?, ?> unavailable = (Map<?, ?>) responses.get("503");
      assertNotNull(unavailable, path + " must document the unavailable response");
      Map<?, ?> successContent = (Map<?, ?>) success.get("content");
      Map<?, ?> unavailableContent = (Map<?, ?>) unavailable.get("content");
      Map<?, ?> successJson = (Map<?, ?>) successContent.get("application/json");
      Map<?, ?> unavailableJson = (Map<?, ?>) unavailableContent.get("application/json");
      Map<?, ?> successSchema = (Map<?, ?>) successJson.get("schema");
      Map<?, ?> unavailableSchema = (Map<?, ?>) unavailableJson.get("schema");

      assertEquals("#/components/schemas/ApiResponseError", unavailableSchema.get("$ref"));
      assertEquals("object", successSchema.get("type"));
    }
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
                    5L,
                    UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
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
        .andExpect(jsonPath("$.data[0].pointerVersion").value(3))
        .andExpect(jsonPath("$.data[0].catalogRevision").value(5))
        .andExpect(jsonPath("$.data[0].realmId").value("11111111-1111-1111-1111-111111111111"))
        .andExpect(
            jsonPath("$.data[0].playableStateNamespaceId")
                .value("22222222-2222-2222-2222-222222222222"));
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
                7L,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
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
