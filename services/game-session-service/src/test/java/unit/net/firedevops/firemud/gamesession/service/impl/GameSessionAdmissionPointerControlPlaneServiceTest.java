package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointerEvent;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuditEntry;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService.PointerAuditKey;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import org.junit.jupiter.api.Test;

class GameSessionAdmissionPointerControlPlaneServiceTest {
  @Test
  void listQueriesTenantScopeBeforeBatchingExactLatestAuditKeys() {
    GameplayAdmissionPointerAuthorityService authorityService =
        mock(GameplayAdmissionPointerAuthorityService.class);
    GameplayAdmissionPointerSnapshot tenantA = pointer(1L, "demo");
    GameplayAdmissionPointerSnapshot tenantB = pointer(2L, "demo");
    PointerAuditKey tenantBKey = new PointerAuditKey(2L, "demo", "production");
    when(authorityService.listPointersForTenants(List.of(2L)))
        .thenReturn(List.of(tenantA, tenantB));
    when(authorityService.findLatestPointerAudits(List.of(tenantBKey)))
        .thenReturn(Map.of(tenantBKey, audit(tenantB)));
    GameSessionAdmissionPointerControlPlaneService controlPlaneService =
        new GameSessionAdmissionPointerControlPlaneService(authorityService);

    var response = controlPlaneService.listAdmissionPointers(List.of(2L));

    assertEquals(1, response.getPointersCount());
    assertEquals("2", response.getPointers(0).getTenantId());
    verify(authorityService).listPointersForTenants(List.of(2L));
    verify(authorityService, never()).listPointers();
    verify(authorityService).findLatestPointerAudits(List.of(tenantBKey));
    verify(authorityService, never()).findLatestPointerAudit(anyLong(), anyString(), anyString());
  }

  @Test
  void listBatchesMixedTenantAuditKeysWithSameWorldSlug() {
    GameplayAdmissionPointerAuthorityService authorityService =
        mock(GameplayAdmissionPointerAuthorityService.class);
    GameplayAdmissionPointerSnapshot tenantA = pointer(1L, "demo");
    GameplayAdmissionPointerSnapshot tenantB = pointer(2L, "demo");
    PointerAuditKey tenantAKey = new PointerAuditKey(1L, "demo", "production");
    PointerAuditKey tenantBKey = new PointerAuditKey(2L, "demo", "production");
    when(authorityService.listPointers()).thenReturn(List.of(tenantA, tenantB));
    when(authorityService.findLatestPointerAudits(List.of(tenantAKey, tenantBKey)))
        .thenReturn(Map.of(tenantAKey, audit(tenantA), tenantBKey, audit(tenantB)));
    GameSessionAdmissionPointerControlPlaneService controlPlaneService =
        new GameSessionAdmissionPointerControlPlaneService(authorityService);

    var response = controlPlaneService.listAdmissionPointers(List.of());

    assertEquals(2, response.getPointersCount());
    assertEquals("1", response.getPointers(0).getTenantId());
    assertEquals("2", response.getPointers(1).getTenantId());
    verify(authorityService).findLatestPointerAudits(List.of(tenantAKey, tenantBKey));
    verify(authorityService, never()).findLatestPointerAudit(anyLong(), anyString(), anyString());
  }

  @Test
  void listFailsClosedWhenBatchAuditIsMissingOrDoesNotMatchCurrentPointer() {
    GameplayAdmissionPointerAuthorityService authorityService =
        mock(GameplayAdmissionPointerAuthorityService.class);
    GameplayAdmissionPointerSnapshot currentPointer = pointer(2L, "tenant-b");
    PointerAuditKey key = new PointerAuditKey(2L, "tenant-b", "production");
    when(authorityService.listPointers()).thenReturn(List.of(currentPointer));
    GameSessionAdmissionPointerControlPlaneService controlPlaneService =
        new GameSessionAdmissionPointerControlPlaneService(authorityService);

    when(authorityService.findLatestPointerAudits(List.of(key))).thenReturn(Map.of());
    assertThrows(
        AdmissionPointerAuditUnavailableException.class,
        () -> controlPlaneService.listAdmissionPointers(List.of()));

    when(authorityService.findLatestPointerAudits(List.of(key)))
        .thenReturn(Map.of(key, audit(currentPointer, currentPointer.pointerVersion() + 1L)));
    assertThrows(
        AdmissionPointerAuditUnavailableException.class,
        () -> controlPlaneService.listAdmissionPointers(List.of()));
    verify(authorityService, never()).findLatestPointerAudit(anyLong(), anyString(), anyString());
  }

  @Test
  void listRejectsRetainedPreV7AuditWithUnknownRevisionAndIdentity() {
    GameplayAdmissionPointerRepository pointerRepository =
        mock(GameplayAdmissionPointerRepository.class);
    GameplayAdmissionPointerEventRepository eventRepository =
        mock(GameplayAdmissionPointerEventRepository.class);

    UUID realmId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID namespaceId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    GameplayAdmissionPointer currentPointer = new GameplayAdmissionPointer();
    currentPointer.setWorldSlug("demo");
    currentPointer.setWorldDisplayName("Demo World");
    currentPointer.setRealmSlug("production");
    currentPointer.setRealmDisplayName("Live Realm");
    currentPointer.setTenantId(1L);
    currentPointer.setGameInstanceId(7L);
    currentPointer.setPointerVersion(1L);
    currentPointer.setCatalogRevision(1L);
    currentPointer.setRealmId(realmId);
    currentPointer.setPlayableStateNamespaceId(namespaceId);
    currentPointer.setVisible(true);
    currentPointer.setPublicProductionRealm(true);
    currentPointer.setRequiresCharacterSelection(false);
    currentPointer.setStateScope("SHARED");
    currentPointer.setCharacterCreationPolicy("ALLOW_NEW");

    GameplayAdmissionPointerEvent retainedPreV7Event = new GameplayAdmissionPointerEvent();
    retainedPreV7Event.setWorldSlug("demo");
    retainedPreV7Event.setWorldDisplayName("Demo World");
    retainedPreV7Event.setRealmSlug("production");
    retainedPreV7Event.setRealmDisplayName("Live Realm");
    retainedPreV7Event.setTenantId(1L);
    retainedPreV7Event.setGameInstanceId(7L);
    retainedPreV7Event.setPointerVersion(1L);
    retainedPreV7Event.setVisible(true);
    retainedPreV7Event.setPublicProductionRealm(true);
    retainedPreV7Event.setRequiresCharacterSelection(false);
    retainedPreV7Event.setStateScope("SHARED");
    retainedPreV7Event.setCharacterCreationPolicy("ALLOW_NEW");
    retainedPreV7Event.setActorPrincipal("legacy-operator");
    retainedPreV7Event.setReason("retained pre-V7 event");
    retainedPreV7Event.setControlPlaneRequestId("legacy-request");
    retainedPreV7Event.setOccurredAt(Instant.parse("2026-09-29T00:00:00Z"));
    // Pre-V7 history has no evidence for these fields; it must not be upgraded by inference.

    when(pointerRepository.findAllByOrderByWorldSlugAscRealmSlugAsc())
        .thenReturn(List.of(currentPointer));
    when(eventRepository.findLatestByPointerKeys(
            List.of(new PointerAuditKey(1L, "demo", "production"))))
        .thenReturn(List.of(retainedPreV7Event));

    DatabaseGameplayAdmissionPointerAuthorityService authorityService =
        new DatabaseGameplayAdmissionPointerAuthorityService(pointerRepository, eventRepository);
    GameSessionAdmissionPointerControlPlaneService controlPlaneService =
        new GameSessionAdmissionPointerControlPlaneService(authorityService);

    assertThrows(
        AdmissionPointerAuditUnavailableException.class,
        () -> controlPlaneService.listAdmissionPointers(List.of()));
  }

  private static GameplayAdmissionPointerSnapshot pointer(long tenantId, String worldSlug) {
    UUID realmId = UUID.nameUUIDFromBytes(("realm-" + tenantId).getBytes(StandardCharsets.UTF_8));
    UUID namespaceId =
        UUID.nameUUIDFromBytes(("namespace-" + tenantId).getBytes(StandardCharsets.UTF_8));
    return new GameplayAdmissionPointerSnapshot(
        worldSlug,
        "Tenant World",
        "production",
        "Live Realm",
        tenantId,
        tenantId + 10L,
        3L,
        true,
        true,
        false,
        "SHARED",
        "ALLOW_NEW",
        6L,
        realmId,
        namespaceId);
  }

  private static GameplayAdmissionPointerAuditEntry audit(
      GameplayAdmissionPointerSnapshot pointer) {
    return audit(pointer, pointer.pointerVersion());
  }

  private static GameplayAdmissionPointerAuditEntry audit(
      GameplayAdmissionPointerSnapshot pointer, long pointerVersion) {
    return new GameplayAdmissionPointerAuditEntry(
        pointer.worldSlug(),
        pointer.realmSlug(),
        pointer.worldDisplayName(),
        pointer.realmDisplayName(),
        pointer.tenantId(),
        pointer.gameInstanceId(),
        pointerVersion,
        pointer.catalogRevision(),
        pointer.realmId(),
        pointer.playableStateNamespaceId(),
        pointer.visible(),
        pointer.publicProductionRealm(),
        pointer.requiresCharacterSelection(),
        pointer.stateScope(),
        pointer.characterCreationPolicy(),
        "tester",
        "test audit",
        "request-" + pointer.tenantId(),
        null,
        Instant.parse("2026-09-29T00:00:00Z"));
  }
}
