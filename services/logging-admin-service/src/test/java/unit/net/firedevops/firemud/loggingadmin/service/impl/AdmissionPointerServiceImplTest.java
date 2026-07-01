package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.AdmissionPointerControlPlaneEntry;
import net.firedevops.firemud.gamesession.v1.CutoverCompatibilityResult;
import net.firedevops.firemud.gamesession.v1.CutoverParticipantResult;
import net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverResponse;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.gamesession.v1.GetPreparedVersionUpgradeResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersResponse;
import net.firedevops.firemud.gamesession.v1.PrepareVersionUpgradeResponse;
import net.firedevops.firemud.gamesession.v1.PreparedVersionUpgrade;
import net.firedevops.firemud.gamesession.v1.ScriptPatchPublicationLink;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerResponse;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.AdmissionPointerDto;
import net.firedevops.firemud.loggingadmin.dto.ExecutePreparedVersionCutoverRequest;
import net.firedevops.firemud.loggingadmin.dto.GameInstanceRuntimeStateDto;
import net.firedevops.firemud.loggingadmin.dto.PrepareVersionUpgradeRequest;
import net.firedevops.firemud.loggingadmin.dto.PreparedVersionUpgradeDto;
import net.firedevops.firemud.loggingadmin.dto.SetAdmissionPointerRequest;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.server.ResponseStatusException;

class AdmissionPointerServiceImplTest {
  @Mock private GameSessionControlPlaneClient gameSessionControlPlaneClient;

  @InjectMocks private AdmissionPointerServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
  }

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void listPointersFiltersToAccessibleTenant() {
    SessionContext.setContext("7", List.of(), Map.of("2", List.of("tenantAdmin")));
    when(gameSessionControlPlaneClient.listAdmissionPointers())
        .thenReturn(
            ListAdmissionPointersResponse.newBuilder()
                .addPointers(pointerEntry("demo", "production", 2L, 7L, 3L))
                .addPointers(pointerEntry("sandbox", "preview", 8L, 11L, 1L))
                .build());

    List<AdmissionPointerDto> result = service.listPointers();

    assertEquals(1, result.size());
    assertEquals("demo", result.get(0).worldSlug());
    assertEquals(2L, result.get(0).tenantId());
  }

  @Test
  void listPointerAuditRequiresAccessibleTenant() {
    SessionContext.setContext("7", List.of(), Map.of("2", List.of("tenantAdmin")));

    assertThrows(
        ResponseStatusException.class, () -> service.listPointerAudit(8L, "sandbox", "preview"));
    org.mockito.Mockito.verifyNoInteractions(gameSessionControlPlaneClient);
  }

  @Test
  void listPointerAuditUsesCanonicalTenantKey() {
    SessionContext.setContext("7", List.of(), Map.of("2", List.of("tenantAdmin")));
    when(gameSessionControlPlaneClient.listAdmissionPointerAudit(2L, "sandbox", "preview"))
        .thenReturn(
            ListAdmissionPointerAuditResponse.newBuilder()
                .addAudit(pointerEntry("sandbox", "preview", 2L, 11L, 4L))
                .build());

    List<AdmissionPointerDto> result = service.listPointerAudit(2L, "sandbox", "preview");

    assertEquals(1, result.size());
    assertEquals(2L, result.get(0).tenantId());
  }

  @Test
  void listPointerAuditRejectsMismatchedControlPlaneTenant() {
    SessionContext.setContext("7", List.of(), Map.of("2", List.of("tenantAdmin")));
    when(gameSessionControlPlaneClient.listAdmissionPointerAudit(2L, "sandbox", "preview"))
        .thenReturn(
            ListAdmissionPointerAuditResponse.newBuilder()
                .addAudit(pointerEntry("sandbox", "preview", 8L, 11L, 4L))
                .build());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> service.listPointerAudit(2L, "sandbox", "preview"));

    assertEquals(500, ex.getStatusCode().value());
  }

  @Test
  void setPointerUsesSessionAccountIdAsActorPrincipal() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.setAdmissionPointer(any()))
        .thenReturn(
            SetAdmissionPointerResponse.newBuilder()
                .setPointer(pointerEntry("demo", "production", 2L, 7L, 4L))
                .build());

    AdmissionPointerDto result =
        service.setPointer(
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
                "pvu-1"));

    assertEquals(4L, result.pointerVersion());
    verify(gameSessionControlPlaneClient)
        .setAdmissionPointer(
            org.mockito.ArgumentMatchers.argThat(
                request ->
                    request.getActorPrincipal().equals("42")
                        && request.getExpectedPointerVersion() == 3L
                        && request.getPreparedVersionUpgradeId().equals("pvu-1")));
  }

  @Test
  void setPointerMapsVersionMismatchToConflict() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.setAdmissionPointer(any()))
        .thenReturn(
            SetAdmissionPointerResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("POINTER_VERSION_MISMATCH")
                        .setMessage("expected_pointer_version mismatch")
                        .build())
                .build());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.setPointer(
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
                        "pvu-1")));

    assertEquals(409, ex.getStatusCode().value());
  }

  @Test
  void executePreparedVersionCutoverUsesSessionAccountIdAsActorPrincipal() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.executePreparedVersionCutover(any()))
        .thenReturn(
            ExecutePreparedVersionCutoverResponse.newBuilder()
                .setPointer(pointerEntry("demo", "production", 2L, 7L, 4L))
                .build());

    AdmissionPointerDto result =
        service.executePreparedVersionCutover(
            new ExecutePreparedVersionCutoverRequest(
                "demo", "production", 2L, 7L, "pvu-1", "cutover", "req-1", 3L));

    assertEquals(4L, result.pointerVersion());
    verify(gameSessionControlPlaneClient)
        .executePreparedVersionCutover(
            org.mockito.ArgumentMatchers.argThat(
                request ->
                    request.getActorPrincipal().equals("42")
                        && request.getPreparedVersionUpgradeId().equals("pvu-1")
                        && request.getExpectedPointerVersion() == 3L));
  }

  @Test
  void getRuntimeStateReturnsCanonicalRuntimeState() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState(2L, 7L))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("2")
                        .setGameInstanceId("7")
                        .setRuntimeVersionId("runtime-v7")
                        .setPinnedScriptPatchVersion("patch-2")
                        .setLaunchDescriptorId("ld-9")
                        .setStatus("RUNNING")
                        .setVersionId("11")
                        .setReleaseBundleId("19")
                        .setVersionStateEpoch(77L)
                        .setScriptPatchPinnedAtMs(
                            Instant.parse("2026-04-22T00:00:00Z").toEpochMilli())
                        .setScriptPatchPinnedBy("operator-1")
                        .setScriptPatchPinnedReason("roll-forward")
                        .setScriptPatchPinnedControlPlaneRequestId("req-77")
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_SHARED)
                        .setWorldSlug("demo")
                        .setRealmSlug("production")
                        .setPointerVersion(11L)
                        .setPublication(
                            ScriptPatchPublicationLink.newBuilder()
                                .setScriptPatchVersion("patch-2")
                                .setVersionId(17L)
                                .setBaseVersionId(7L)
                                .setPublicationState(
                                    net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                                        .VERSION_LIFECYCLE_STATE_PUBLISHED)
                                .setLastChangedAtMs(
                                    Instant.parse("2026-04-22T00:00:01Z").toEpochMilli())
                                .build())
                        .setRegionId("region-7")
                        .setRegionEpoch(22L)
                        .addCurrentAdmissionPointers(
                            pointerEntry("demo", "production", 2L, 7L, 11L))
                        .build())
                .build());

    GameInstanceRuntimeStateDto result = service.getRuntimeState(2L, 7L);

    assertEquals(2L, result.tenantId());
    assertEquals(7L, result.gameInstanceId());
    assertEquals("demo", result.worldSlug());
    assertEquals("production", result.currentAdmissionPointers().getFirst().realmSlug());
    assertEquals("VERSION_LIFECYCLE_STATE_PUBLISHED", result.publication().publicationState());
  }

  @Test
  void getRuntimeStateRejectsMismatchedControlPlaneTenant() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState(2L, 7L))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder().setTenantId("8").setGameInstanceId("7"))
                .build());

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> service.getRuntimeState(2L, 7L));

    assertEquals(500, ex.getStatusCode().value());
  }

  @Test
  void prepareVersionUpgradeReturnsDurablePreparation() {
    SessionContext.setContext("42", List.of("platformAdmin"), Map.of());
    when(gameSessionControlPlaneClient.prepareVersionUpgrade(any()))
        .thenReturn(
            PrepareVersionUpgradeResponse.newBuilder()
                .setPreparation(preparedUpgrade("pvu-1", 2L))
                .build());

    PreparedVersionUpgradeDto result =
        service.prepareVersionUpgrade(new PrepareVersionUpgradeRequest(2L, 7L, 9L, "req-prepare"));

    assertEquals("pvu-1", result.preparationId());
    assertEquals(2L, result.tenantId());
    assertEquals("COMPATIBLE", result.result());
    verify(gameSessionControlPlaneClient)
        .prepareVersionUpgrade(
            org.mockito.ArgumentMatchers.argThat(
                request ->
                    request.getTenantId().equals("2")
                        && request.getSourceGameInstanceId().equals("7")
                        && request.getTargetVersionId().equals("9")
                        && request.getControlPlaneRequestId().equals("req-prepare")));
  }

  @Test
  void getPreparedVersionUpgradeRequiresAccessibleTenant() {
    SessionContext.setContext("7", List.of(), Map.of("2", List.of("tenantAdmin")));
    when(gameSessionControlPlaneClient.getPreparedVersionUpgrade(any()))
        .thenReturn(
            GetPreparedVersionUpgradeResponse.newBuilder()
                .setPreparation(preparedUpgrade("pvu-1", 8L))
                .build());

    assertThrows(
        ResponseStatusException.class, () -> service.getPreparedVersionUpgrade(8L, "pvu-1"));
  }

  private AdmissionPointerControlPlaneEntry pointerEntry(
      String worldSlug, String realmSlug, long tenantId, long gameInstanceId, long pointerVersion) {
    return AdmissionPointerControlPlaneEntry.newBuilder()
        .setWorldSlug(worldSlug)
        .setWorldDisplayName("World " + worldSlug)
        .setRealmSlug(realmSlug)
        .setRealmDisplayName("Realm " + realmSlug)
        .setTenantId(Long.toString(tenantId))
        .setGameInstanceId(Long.toString(gameInstanceId))
        .setPointerVersion(pointerVersion)
        .setVisible(true)
        .setRequiresCharacterSelection(false)
        .setStateScope("SHARED")
        .setCharacterCreationPolicy("ALLOW_NEW")
        .setActorPrincipal("tester")
        .setReason("cutover")
        .setControlPlaneRequestId("req-1")
        .setPreparedVersionUpgradeId("pvu-1")
        .setOccurredAtMs(1_744_672_000_000L)
        .build();
  }

  private PreparedVersionUpgrade preparedUpgrade(String preparationId, long tenantId) {
    return PreparedVersionUpgrade.newBuilder()
        .setPreparationId(preparationId)
        .setTenantId(Long.toString(tenantId))
        .setSourceGameInstanceId("7")
        .setSourceVersionId("6")
        .setTargetVersionId("9")
        .setTargetLaunchDescriptorId("77")
        .setRemapSetId("remap-1")
        .setResult(CutoverCompatibilityResult.CUTOVER_COMPATIBILITY_RESULT_COMPATIBLE)
        .addReasons("checked")
        .addCheckedParticipants("entity")
        .setCheckedAtMs(1_774_672_000_000L)
        .addParticipantResults(
            CutoverParticipantResult.newBuilder()
                .setParticipant("entity")
                .setResult(CutoverCompatibilityResult.CUTOVER_COMPATIBILITY_RESULT_COMPATIBLE)
                .addStateClassesChecked("S3")
                .addCheckedFamilies("room_ground_inventory")
                .build())
        .setControlPlaneRequestId("req-prepare")
        .setExecutedTargetGameInstanceId("55")
        .setExecutedPointerVersion(4L)
        .setExecutedAtMs(1_774_672_001_000L)
        .setExecutionControlPlaneRequestId("req-cutover")
        .build();
  }
}
