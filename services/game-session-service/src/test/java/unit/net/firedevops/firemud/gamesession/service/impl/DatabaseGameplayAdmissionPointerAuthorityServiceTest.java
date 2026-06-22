package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    when(pointerRepository.findByWorldSlugAndRealmSlug("demo", "production"))
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
                    null)));
  }

  @Test
  void upsertPointerAllowsCreateWhenExpectedVersionIsZero() {
    when(pointerRepository.findByWorldSlugAndRealmSlug("demo", "production"))
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
                "pvu-1"));

    ArgumentCaptor<GameplayAdmissionPointer> pointerCaptor =
        ArgumentCaptor.forClass(GameplayAdmissionPointer.class);
    verify(pointerRepository).save(pointerCaptor.capture());
    assertEquals(1L, pointerCaptor.getValue().getPointerVersion());
    assertEquals(1L, snapshot.pointerVersion());
    verify(eventRepository).save(any(GameplayAdmissionPointerEvent.class));
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
}
