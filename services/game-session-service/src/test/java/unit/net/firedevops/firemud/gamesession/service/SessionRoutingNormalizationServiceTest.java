package net.firedevops.firemud.gamesession.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
}
