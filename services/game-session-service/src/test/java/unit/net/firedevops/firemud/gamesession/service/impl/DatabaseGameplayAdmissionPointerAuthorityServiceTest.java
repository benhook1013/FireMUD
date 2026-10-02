package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointerEvent;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.service.AdmissionPointerVersionMismatchException;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerMutation;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class DatabaseGameplayAdmissionPointerAuthorityServiceTest {
  @Mock private GameplayAdmissionPointerRepository pointerRepository;
  @Mock private GameplayAdmissionPointerEventRepository eventRepository;

  private DatabaseGameplayAdmissionPointerAuthorityService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service =
        new DatabaseGameplayAdmissionPointerAuthorityService(pointerRepository, eventRepository);
  }

  @Test
  void upsertPointerRejectsMismatchedExpectedVersion() {
    GameplayAdmissionPointer existing = new GameplayAdmissionPointer();
    existing.setId(11L);
    existing.setPointerVersion(3L);
    existing.setCatalogRevision(4L);
    existing.setStateScope("SHARED");
    when(pointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(1L, "demo", "production"))
        .thenReturn(Optional.of(existing));

    assertThrows(
        AdmissionPointerVersionMismatchException.class,
        () ->
            service.upsertPointer(
                new GameplayAdmissionPointerMutation(
                    "demo",
                    "Demo World",
                    "production",
                    "Live Realm",
                    1L,
                    7L,
                    true,
                    true,
                    false,
                    "SHARED",
                    "ALLOW_NEW",
                    "tester",
                    "cutover",
                    "req-1",
                    2L,
                    4L,
                    null)));
  }

  @Test
  void listPointersByTenantUsesTheScopedRepositoryRead() {
    GameplayAdmissionPointer scopedPointer = existingPointer();
    when(pointerRepository.findAllByTenantIdOrderByWorldSlugAscRealmSlugAsc(1L))
        .thenReturn(List.of(scopedPointer));

    var snapshots = service.listPointersByTenant(1L);

    assertEquals(1, snapshots.size());
    assertEquals(1L, snapshots.getFirst().tenantId());
    verify(pointerRepository).findAllByTenantIdOrderByWorldSlugAscRealmSlugAsc(1L);
    verify(pointerRepository, never()).findAllByOrderByWorldSlugAscRealmSlugAsc();
  }

  @Test
  void listPointersByTenantRejectsUnknownTenant() {
    assertThrows(IllegalArgumentException.class, () -> service.listPointersByTenant(0L));
    verifyNoInteractions(pointerRepository);
  }

  @Test
  void upsertPointerRejectsUnknownStateScope() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.upsertPointer(
                new GameplayAdmissionPointerMutation(
                    "demo",
                    "Demo World",
                    "production",
                    "Live Realm",
                    1L,
                    7L,
                    true,
                    true,
                    false,
                    "UNKNOWN",
                    "ALLOW_NEW",
                    "tester",
                    "cutover",
                    "req-invalid-scope",
                    null,
                    null,
                    null)));
  }

  @Test
  void upsertPointerAllowsCreateWhenExpectedVersionIsZero() {
    when(pointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(1L, "demo", "production"))
        .thenReturn(Optional.empty());
    when(pointerRepository.save(any(GameplayAdmissionPointer.class)))
        .thenAnswer(
            invocation -> {
              GameplayAdmissionPointer pointer = invocation.getArgument(0);
              pointer.setId(11L);
              return pointer;
            });
    when(eventRepository.save(any(GameplayAdmissionPointerEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    GameplayAdmissionPointerSnapshot snapshot =
        service.upsertPointer(
            new GameplayAdmissionPointerMutation(
                "demo",
                "Demo World",
                "production",
                "Live Realm",
                1L,
                7L,
                true,
                true,
                false,
                "SHARED",
                "ALLOW_NEW",
                "tester",
                "cutover",
                "req-2",
                0L,
                0L,
                "pvu-1"));

    ArgumentCaptor<GameplayAdmissionPointer> pointerCaptor =
        ArgumentCaptor.forClass(GameplayAdmissionPointer.class);
    verify(pointerRepository).save(pointerCaptor.capture());
    assertEquals(1L, pointerCaptor.getValue().getPointerVersion());
    assertEquals(1L, snapshot.pointerVersion());
    verify(eventRepository).save(any(GameplayAdmissionPointerEvent.class));
  }

  @Test
  void upsertPointerAdvancesCatalogRevisionWithoutAdvancingPointerVersion() {
    GameplayAdmissionPointer existing = existingPointer();
    when(pointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(1L, "demo", "production"))
        .thenReturn(Optional.of(existing));
    when(pointerRepository.updateExisting(any(GameplayAdmissionPointer.class), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    GameplayAdmissionPointerSnapshot snapshot =
        service.upsertPointer(
            new GameplayAdmissionPointerMutation(
                "demo",
                "Renamed Demo World",
                "production",
                "Live Realm",
                1L,
                7L,
                true,
                true,
                false,
                "SHARED",
                "ALLOW_NEW",
                "tester",
                "display update",
                "req-catalog-only",
                1L,
                1L,
                null));

    assertEquals(1L, snapshot.pointerVersion());
    assertEquals(2L, snapshot.catalogRevision());
    verify(pointerRepository).updateExisting(any(GameplayAdmissionPointer.class), eq(1L), eq(1L));
    ArgumentCaptor<GameplayAdmissionPointerEvent> eventCaptor =
        ArgumentCaptor.forClass(GameplayAdmissionPointerEvent.class);
    verify(eventRepository).save(eventCaptor.capture());
    assertEquals(1L, eventCaptor.getValue().getPointerVersion());
    assertEquals(2L, eventCaptor.getValue().getCatalogRevision());
    assertEquals(existing.getRealmId(), eventCaptor.getValue().getRealmId());
    assertEquals(
        existing.getPlayableStateNamespaceId(),
        eventCaptor.getValue().getPlayableStateNamespaceId());
  }

  @Test
  void upsertPointerRuntimeTargetChangeAdvancesPointerVersionWithoutCatalogRevision() {
    GameplayAdmissionPointer existing = existingPointer();
    when(pointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(1L, "demo", "production"))
        .thenReturn(Optional.of(existing));
    when(pointerRepository.updateExisting(any(GameplayAdmissionPointer.class), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    GameplayAdmissionPointerSnapshot snapshot =
        service.upsertPointer(
            new GameplayAdmissionPointerMutation(
                "demo",
                "Demo World",
                "production",
                "Live Realm",
                1L,
                8L,
                true,
                true,
                false,
                "SHARED",
                "ALLOW_NEW",
                "tester",
                "runtime target change",
                "req-runtime-target",
                1L,
                1L,
                null));

    assertEquals(2L, snapshot.pointerVersion());
    assertEquals(1L, snapshot.catalogRevision());
    verify(pointerRepository).updateExisting(any(GameplayAdmissionPointer.class), eq(1L), eq(1L));
    verify(eventRepository).save(any(GameplayAdmissionPointerEvent.class));
  }

  @Test
  void upsertPointerExactNoOpDoesNotAdvanceOrPersistVersions() {
    GameplayAdmissionPointer existing = existingPointer();
    when(pointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(1L, "demo", "production"))
        .thenReturn(Optional.of(existing));

    GameplayAdmissionPointerSnapshot snapshot =
        service.upsertPointer(
            new GameplayAdmissionPointerMutation(
                "demo",
                "Demo World",
                "production",
                "Live Realm",
                1L,
                7L,
                true,
                true,
                false,
                "SHARED",
                "ALLOW_NEW",
                "tester",
                "repeat",
                "req-no-op",
                1L,
                1L,
                null));

    assertEquals(1L, snapshot.pointerVersion());
    assertEquals(1L, snapshot.catalogRevision());
    verify(pointerRepository, never()).save(any(GameplayAdmissionPointer.class));
    verifyNoInteractions(eventRepository);
  }

  @Test
  void findPointerByTenantAndRealmSlugDelegatesToRepository() {
    GameplayAdmissionPointer existing = new GameplayAdmissionPointer();
    existing.setWorldSlug("demo");
    existing.setWorldDisplayName("Demo World");
    existing.setRealmSlug("production");
    existing.setRealmDisplayName("Live Realm");
    existing.setTenantId(7L);
    existing.setGameInstanceId(44L);
    existing.setPointerVersion(17L);
    when(pointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(7L, "demo", "production"))
        .thenReturn(Optional.of(existing));

    GameplayAdmissionPointerSnapshot snapshot =
        service.findPointer(7L, "demo", "production").orElseThrow();

    assertEquals("demo", snapshot.worldSlug());
    assertEquals(44L, snapshot.gameInstanceId());
    verify(pointerRepository).findByTenantIdAndWorldSlugAndRealmSlug(7L, "demo", "production");
  }

  @Test
  void listPointerAuditPreservesCatalogIdentityAndHistoricalAbsence() {
    UUID realmId = UUID.fromString("3ce19e6a-a63f-46f4-8e25-b105694c79e9");
    UUID namespaceId = UUID.fromString("f673a1e6-648d-4ac3-8f3d-4b7cc4380f2a");
    GameplayAdmissionPointerEvent current = new GameplayAdmissionPointerEvent();
    populateAuditEvent(current, 3L, 4L, realmId, namespaceId);
    GameplayAdmissionPointerEvent historical = new GameplayAdmissionPointerEvent();
    populateAuditEvent(historical, 2L, null, null, null);
    when(eventRepository.findByTenantIdAndWorldSlugAndRealmSlugOrderByOccurredAtDesc(
            1L, "demo", "production"))
        .thenReturn(java.util.List.of(current, historical));

    var audit = service.listPointerAudit(1L, "demo", "production");

    assertEquals(2, audit.size());
    assertEquals(4L, audit.getFirst().catalogRevision());
    assertEquals(realmId, audit.getFirst().realmId());
    assertEquals(namespaceId, audit.getFirst().playableStateNamespaceId());
    assertNull(audit.get(1).catalogRevision());
    assertNull(audit.get(1).realmId());
    assertNull(audit.get(1).playableStateNamespaceId());
  }

  @Test
  void catalogPolicyRevisionAdvancesIndependentlyFromPointerVersion() {
    java.util.concurrent.atomic.AtomicReference<GameplayAdmissionPointer> currentPointer =
        new java.util.concurrent.atomic.AtomicReference<>();
    when(pointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(7L, "demo", "production"))
        .thenAnswer(invocation -> Optional.ofNullable(currentPointer.get()));
    when(pointerRepository.save(any(GameplayAdmissionPointer.class)))
        .thenAnswer(
            invocation -> {
              GameplayAdmissionPointer pointer = invocation.getArgument(0);
              if (pointer.getId() == null) {
                pointer.setId(11L);
              }
              currentPointer.set(pointer);
              return pointer;
            });
    when(pointerRepository.updateExisting(any(GameplayAdmissionPointer.class), any(), any()))
        .thenAnswer(
            invocation -> {
              GameplayAdmissionPointer pointer = invocation.getArgument(0);
              currentPointer.set(pointer);
              return pointer;
            });
    when(eventRepository.save(any(GameplayAdmissionPointerEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    GameplayAdmissionPointerSnapshot created =
        service.upsertPointer(pointerMutation(44L, true, 0L, 0L));
    GameplayAdmissionPointerSnapshot policyChanged =
        service.upsertPointer(pointerMutation(44L, false, 1L, 1L));
    GameplayAdmissionPointerSnapshot routeChanged =
        service.upsertPointer(pointerMutation(45L, false, 1L, 2L));

    assertEquals(1L, created.catalogRevision());
    assertEquals(1L, created.pointerVersion());
    assertEquals(2L, policyChanged.catalogRevision());
    assertEquals(1L, policyChanged.pointerVersion());
    assertEquals(2L, routeChanged.catalogRevision());
    assertEquals(2L, routeChanged.pointerVersion());
  }

  @Test
  void concurrentSameTransitionWinnerPreventsDuplicateAuditForStaleExpectedRevisions() {
    GameplayAdmissionPointer winnerRead = existingPointerForTenant(7L);
    GameplayAdmissionPointer staleRead = existingPointerForTenant(7L);
    when(pointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(7L, "demo", "production"))
        .thenReturn(Optional.of(winnerRead), Optional.of(staleRead));
    java.util.concurrent.atomic.AtomicReference<GameplayAdmissionPointer> current =
        new java.util.concurrent.atomic.AtomicReference<>(existingPointerForTenant(7L));
    when(pointerRepository.updateExisting(any(GameplayAdmissionPointer.class), any(), any()))
        .thenAnswer(
            invocation -> {
              GameplayAdmissionPointer requested = invocation.getArgument(0);
              Long expectedPointerVersion = invocation.getArgument(1);
              Long expectedCatalogRevision = invocation.getArgument(2);
              GameplayAdmissionPointer committed = current.get();
              if (!java.util.Objects.equals(committed.getPointerVersion(), expectedPointerVersion)
                  || !java.util.Objects.equals(
                      committed.getCatalogRevision(), expectedCatalogRevision)) {
                throw new AdmissionPointerVersionMismatchException(
                    "Admission pointer changed before the requested version could be committed");
              }
              current.set(requested);
              return requested;
            });
    java.util.List<GameplayAdmissionPointerEvent> events = new java.util.ArrayList<>();
    when(eventRepository.save(any(GameplayAdmissionPointerEvent.class)))
        .thenAnswer(
            invocation -> {
              GameplayAdmissionPointerEvent event = invocation.getArgument(0);
              events.add(event);
              return event;
            });

    GameplayAdmissionPointerSnapshot winner =
        service.upsertPointer(pointerMutation(45L, false, 1L, 1L, "concurrent-winner"));

    assertEquals(2L, winner.pointerVersion());
    assertEquals(2L, winner.catalogRevision());
    assertThrows(
        AdmissionPointerVersionMismatchException.class,
        () -> service.upsertPointer(pointerMutation(45L, false, 1L, 1L, "concurrent-stale")));
    assertEquals(1, events.size());
    assertEquals("concurrent-winner", events.getFirst().getControlPlaneRequestId());
    verify(pointerRepository, times(2))
        .updateExisting(any(GameplayAdmissionPointer.class), eq(1L), eq(1L));
  }

  @Test
  void existingPointerMutationRequiresMatchingCatalogRevisionEvenForNoOp() {
    GameplayAdmissionPointer existing = existingPointer();
    existing.setTenantId(7L);
    when(pointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(7L, "demo", "production"))
        .thenReturn(Optional.of(existing));

    assertThrows(
        AdmissionPointerVersionMismatchException.class,
        () -> service.upsertPointer(pointerMutation(7L, true, 1L, 2L)));
    assertThrows(
        AdmissionPointerVersionMismatchException.class,
        () -> service.upsertPointer(pointerMutation(7L, true, 1L, null)));
    assertThrows(
        AdmissionPointerVersionMismatchException.class,
        () -> service.upsertPointer(pointerMutation(7L, true, null, 1L)));

    verify(pointerRepository, never()).save(any(GameplayAdmissionPointer.class));
    verifyNoInteractions(eventRepository);
  }

  @Test
  void newPointerMutationRejectsPositiveInitialCatalogRevision() {
    assertThrows(
        AdmissionPointerVersionMismatchException.class,
        () -> service.upsertPointer(pointerMutation(44L, true, 0L, 1L)));

    verify(pointerRepository, never()).save(any(GameplayAdmissionPointer.class));
    verifyNoInteractions(eventRepository);
  }

  private static GameplayAdmissionPointerMutation pointerMutation(
      long gameInstanceId,
      boolean publicProductionRealm,
      Long expectedPointerVersion,
      Long expectedCatalogRevision) {
    return pointerMutation(
        gameInstanceId,
        publicProductionRealm,
        expectedPointerVersion,
        expectedCatalogRevision,
        "catalog-revision-test-" + expectedPointerVersion);
  }

  private static GameplayAdmissionPointerMutation pointerMutation(
      long gameInstanceId,
      boolean publicProductionRealm,
      Long expectedPointerVersion,
      Long expectedCatalogRevision,
      String requestId) {
    return new GameplayAdmissionPointerMutation(
        "demo",
        "Demo World",
        "production",
        "Live Realm",
        7L,
        gameInstanceId,
        true,
        publicProductionRealm,
        false,
        "SHARED",
        "ALLOW_NEW",
        "test",
        "catalog revision test",
        requestId,
        expectedPointerVersion,
        expectedCatalogRevision,
        null);
  }

  private static void populateAuditEvent(
      GameplayAdmissionPointerEvent event,
      long pointerVersion,
      Long catalogRevision,
      UUID realmId,
      UUID namespaceId) {
    event.setWorldSlug("demo");
    event.setWorldDisplayName("Demo World");
    event.setRealmSlug("production");
    event.setRealmDisplayName("Live Realm");
    event.setTenantId(1L);
    event.setGameInstanceId(7L);
    event.setPointerVersion(pointerVersion);
    event.setCatalogRevision(catalogRevision);
    event.setRealmId(realmId);
    event.setPlayableStateNamespaceId(namespaceId);
    event.setVisible(true);
    event.setPublicProductionRealm(true);
    event.setRequiresCharacterSelection(false);
    event.setStateScope("SHARED");
    event.setCharacterCreationPolicy("ALLOW_NEW");
    event.setActorPrincipal("tester");
    event.setReason("historical pointer event");
    event.setControlPlaneRequestId("audit-event-" + pointerVersion);
    event.setOccurredAt(java.time.Instant.parse("2026-10-02T00:00:00Z"));
  }

  private static GameplayAdmissionPointer existingPointer() {
    GameplayAdmissionPointer pointer = new GameplayAdmissionPointer();
    pointer.setId(11L);
    pointer.setWorldSlug("demo");
    pointer.setWorldDisplayName("Demo World");
    pointer.setRealmSlug("production");
    pointer.setRealmDisplayName("Live Realm");
    pointer.setTenantId(1L);
    pointer.setGameInstanceId(7L);
    pointer.setPointerVersion(1L);
    pointer.setCatalogRevision(1L);
    pointer.setRealmId(UUID.fromString("3ce19e6a-a63f-46f4-8e25-b105694c79e9"));
    pointer.setPlayableStateNamespaceId(UUID.fromString("f673a1e6-648d-4ac3-8f3d-4b7cc4380f2a"));
    pointer.setVisible(true);
    pointer.setPublicProductionRealm(true);
    pointer.setRequiresCharacterSelection(false);
    pointer.setStateScope("SHARED");
    pointer.setCharacterCreationPolicy("ALLOW_NEW");
    return pointer;
  }

  private static GameplayAdmissionPointer existingPointerForTenant(long tenantId) {
    GameplayAdmissionPointer pointer = existingPointer();
    pointer.setTenantId(tenantId);
    return pointer;
  }
}
