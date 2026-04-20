package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.AdmissionPointerControlPlaneEntry;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersResponse;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerResponse;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.AdmissionPointerDto;
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
    when(gameSessionControlPlaneClient.listAdmissionPointerAudit("sandbox", "preview"))
        .thenReturn(
            ListAdmissionPointerAuditResponse.newBuilder()
                .addAudit(pointerEntry("sandbox", "preview", 8L, 11L, 4L))
                .build());

    assertThrows(
        ResponseStatusException.class, () -> service.listPointerAudit("sandbox", "preview"));
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
                        false,
                        "SHARED",
                        "ALLOW_NEW",
                        "cutover",
                        "req-1",
                        3L,
                        "pvu-1")));

    assertEquals(409, ex.getStatusCode().value());
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
        .setOccurredAtMs(1_744_672_000_000L)
        .build();
  }
}
