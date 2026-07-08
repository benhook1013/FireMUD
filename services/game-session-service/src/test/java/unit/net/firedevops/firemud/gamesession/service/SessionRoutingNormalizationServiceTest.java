package net.firedevops.firemud.gamesession.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SessionRoutingNormalizationServiceTest {
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final GameplayAdmissionPointerAuthorityService pointerAuthorityService =
      Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
  private SessionRoutingNormalizationService service;

  @BeforeEach
  void setUp() {
    service =
        new SessionRoutingNormalizationService(sessionContextService, pointerAuthorityService);
  }

  @Test
  void resolveProjectedSessionContextFailsClosedWhenTenantScopedSessionIsMissing() {
    SessionContext rawOnly =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            7001L,
            "Emberline",
            1L,
            "room-1",
            "jwt",
            "en-NZ",
            41L,
            "demo",
            "production",
            2L,
            "SHARED");
    when(sessionContextService.findBySessionId(41L)).thenReturn(Optional.of(rawOnly));
    when(sessionContextService.findByTenantAndSessionId(22L, 41L)).thenReturn(Optional.empty());

    Optional<SessionContext> resolved = service.resolveProjectedSessionContext("41");

    assertTrue(resolved.isEmpty());
    verify(pointerAuthorityService, never())
        .listByRuntimeTarget(Mockito.anyLong(), Mockito.anyLong());
  }

  @Test
  void resolveProjectedSessionContextFailsClosedForNonPositiveSessionId() {
    Optional<SessionContext> zero = service.resolveProjectedSessionContext("0");

    assertTrue(zero.isEmpty());
    verify(sessionContextService, never()).findBySessionId(anyLong());
    verify(pointerAuthorityService, never())
        .listByRuntimeTarget(Mockito.anyLong(), Mockito.anyLong());

    Optional<SessionContext> negative = service.resolveProjectedSessionContext("-1");

    assertTrue(negative.isEmpty());
    verify(sessionContextService, never()).findBySessionId(anyLong());
    verify(pointerAuthorityService, never())
        .listByRuntimeTarget(Mockito.anyLong(), Mockito.anyLong());
  }

  @Test
  void normalizeProjectedContextClearsPartialGameplayShellWhenAdmissionPointerCannotMatch() {
    SessionContext partial =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            7001L,
            "Emberline",
            0L,
            null,
            "jwt",
            "en-NZ",
            41L,
            "demo",
            "production",
            2L,
            null);
    when(pointerAuthorityService.listByRuntimeTarget(22L, 0L)).thenReturn(java.util.List.of());

    SessionContext normalized = service.normalizeProjectedContext(partial);

    assertEquals(0L, normalized.characterId());
    assertEquals(0L, normalized.gameInstanceId());
    assertEquals(null, normalized.roomInstanceId());
    assertEquals(null, normalized.worldSlug());
    assertEquals(null, normalized.realmSlug());
    assertEquals(0L, normalized.pointerVersion());
    verify(pointerAuthorityService).listByRuntimeTarget(22L, 0L);
  }

  @Test
  void normalizeProjectedContextClearsLegacyRuntimeRoomBindingBeforePointerChecks() {
    SessionContext legacy =
        new SessionContext(
            41L,
            22L,
            123L,
            "demo@example.com",
            7001L,
            "Emberline",
            1L,
            "room-1",
            "jwt",
            "en-NZ",
            41L,
            "demo",
            "production",
            2L,
            "SHARED");

    SessionContext normalized = service.normalizeProjectedContext(legacy);

    assertEquals(0L, normalized.characterId());
    assertEquals(0L, normalized.gameInstanceId());
    assertEquals(null, normalized.roomInstanceId());
    assertEquals(null, normalized.worldSlug());
    assertEquals(null, normalized.realmSlug());
    assertEquals(0L, normalized.pointerVersion());
    verify(pointerAuthorityService, never())
        .listByRuntimeTarget(Mockito.anyLong(), Mockito.anyLong());
  }
}
