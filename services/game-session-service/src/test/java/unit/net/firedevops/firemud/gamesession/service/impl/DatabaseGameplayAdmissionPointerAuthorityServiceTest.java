package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
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
                    1L,
                    null)));
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
  void upsertPointerAllowsCreateWhenExpectedVersionPairIsZero() {
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
    assertEquals(1L, snapshot.catalogRevision());
    ArgumentCaptor<GameplayAdmissionPointerEvent> eventCaptor =
        ArgumentCaptor.forClass(GameplayAdmissionPointerEvent.class);
    verify(eventRepository).save(eventCaptor.capture());
    assertEquals(1L, eventCaptor.getValue().getCatalogRevision());
  }

  @Test
  void upsertPointerAdvancesCatalogRevisionWithoutAdvancingPointerVersion() {
    GameplayAdmissionPointer existing = existingPointer();
    when(pointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(1L, "demo", "production"))
        .thenReturn(Optional.of(existing));
    when(pointerRepository.save(any(GameplayAdmissionPointer.class)))
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
    verify(pointerRepository).save(any(GameplayAdmissionPointer.class));
    ArgumentCaptor<GameplayAdmissionPointerEvent> eventCaptor =
        ArgumentCaptor.forClass(GameplayAdmissionPointerEvent.class);
    verify(eventRepository).save(eventCaptor.capture());
    assertEquals(2L, eventCaptor.getValue().getCatalogRevision());
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
  void upsertPointerRejectsStaleCatalogRevisionEvenWhenPointerVersionMatches() {
    GameplayAdmissionPointer existing = existingPointer();
    existing.setCatalogRevision(2L);
    when(pointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(1L, "demo", "production"))
        .thenReturn(Optional.of(existing));

    assertThrows(
        AdmissionPointerVersionMismatchException.class,
        () ->
            service.upsertPointer(
                new GameplayAdmissionPointerMutation(
                    "demo",
                    "Stale Demo World",
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
                    "stale catalog edit",
                    "req-stale-catalog",
                    1L,
                    1L,
                    null)));

    verify(pointerRepository, never()).save(any(GameplayAdmissionPointer.class));
    verifyNoInteractions(eventRepository);
  }

  @Test
  void upsertPointerRejectsExistingMutationWithoutExpectedPair() {
    GameplayAdmissionPointer existing = existingPointer();
    when(pointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(1L, "demo", "production"))
        .thenReturn(Optional.of(existing));

    assertThrows(
        AdmissionPointerVersionMismatchException.class,
        () ->
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
                    "missing precondition",
                    "req-missing-pair",
                    null,
                    null,
                    null)));

    verify(pointerRepository, never()).save(any(GameplayAdmissionPointer.class));
    verifyNoInteractions(eventRepository);
  }

  @Test
  void listPointerAuditExposesPersistedCatalogRevision() {
    GameplayAdmissionPointerEvent event = new GameplayAdmissionPointerEvent();
    event.setWorldSlug("demo");
    event.setRealmSlug("production");
    event.setWorldDisplayName("Renamed Demo World");
    event.setRealmDisplayName("Live Realm");
    event.setTenantId(1L);
    event.setGameInstanceId(7L);
    event.setPointerVersion(1L);
    event.setCatalogRevision(2L);
    event.setStateScope("SHARED");
    event.setCharacterCreationPolicy("ALLOW_NEW");
    event.setOccurredAt(java.time.Instant.parse("2026-09-29T00:00:00Z"));
    when(eventRepository.findByTenantIdAndWorldSlugAndRealmSlugOrderByOccurredAtDesc(
            1L, "demo", "production"))
        .thenReturn(List.of(event));

    assertEquals(
        2L,
        service.listPointerAudit(1L, "demo", "production").getFirst().catalogRevision());
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
    pointer.setVisible(true);
    pointer.setPublicProductionRealm(true);
    pointer.setRequiresCharacterSelection(false);
    pointer.setStateScope("SHARED");
    pointer.setCharacterCreationPolicy("ALLOW_NEW");
    return pointer;
  }
}
