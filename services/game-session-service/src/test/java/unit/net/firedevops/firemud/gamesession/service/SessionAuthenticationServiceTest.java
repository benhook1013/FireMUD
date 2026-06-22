package net.firedevops.firemud.gamesession.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class SessionAuthenticationServiceTest {
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final GameplayAdmissionPointerAuthorityService pointerAuthorityService =
      Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
  private final GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
      Mockito.mock(GameplayPresenceLifecycleService.class);
  private final GameSessionProperties properties = new GameSessionProperties();
  private SessionRoutingNormalizationService sessionRoutingNormalizationService;
  private SessionAuthenticationService service;

  @BeforeEach
  void setUp() {
    sessionRoutingNormalizationService =
        new SessionRoutingNormalizationService(sessionContextService, pointerAuthorityService);
    service =
        new SessionAuthenticationService(
            sessionContextService,
            properties,
            sessionRoutingNormalizationService,
            gameplayPresenceLifecycleService);
  }

  @Test
  void resolveSessionContextClearsStaleGameplayBindingButKeepsLogin() {
    SessionContext stale =
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
            1L,
            "SHARED");
    when(sessionContextService.findBySessionId(41L)).thenReturn(Optional.of(stale));
    when(sessionContextService.findByTenantAndSessionId(22L, 41L)).thenReturn(Optional.of(stale));
    when(pointerAuthorityService.listByRuntimeTarget(22L, 1L))
        .thenReturn(List.of(pointer("demo", "production", 22L, 1L, 2L)));

    Optional<SessionContext> resolved = service.resolveSessionContext("41");

    assertTrue(resolved.isPresent());
    assertEquals(123L, resolved.orElseThrow().accountId());
    assertEquals(0L, resolved.orElseThrow().gameInstanceId());
    assertEquals(0L, resolved.orElseThrow().characterId());
    ArgumentCaptor<SessionContext> captor = ArgumentCaptor.forClass(SessionContext.class);
    verify(sessionContextService).save(captor.capture());
    verify(gameplayPresenceLifecycleService).clearGameplayBinding(stale, "STALE_ADMISSION_POINTER");
    assertEquals(123L, captor.getValue().accountId());
    assertEquals(0L, captor.getValue().gameInstanceId());
    assertEquals(0L, captor.getValue().pointerVersion());
  }

  @Test
  void resolveSessionContextClearsGameplayBindingWhenRoutingBundleIsIncomplete() {
    SessionContext incomplete =
        new SessionContext(
            41L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 1L, "room-1", "jwt");
    when(sessionContextService.findBySessionId(41L)).thenReturn(Optional.of(incomplete));
    when(sessionContextService.findByTenantAndSessionId(22L, 41L))
        .thenReturn(Optional.of(incomplete));

    Optional<SessionContext> resolved = service.resolveSessionContext("41");

    assertTrue(resolved.isPresent());
    assertEquals(123L, resolved.orElseThrow().accountId());
    assertEquals(0L, resolved.orElseThrow().gameInstanceId());
    verify(sessionContextService).save(Mockito.any(SessionContext.class));
    verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(incomplete, "STALE_ADMISSION_POINTER");
  }

  @Test
  void resolveSessionContextClearsGameplayBindingWhenRuntimeTargetAuthorityIsAmbiguous() {
    SessionContext ambiguous =
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
    when(sessionContextService.findBySessionId(41L)).thenReturn(Optional.of(ambiguous));
    when(sessionContextService.findByTenantAndSessionId(22L, 41L))
        .thenReturn(Optional.of(ambiguous));
    when(pointerAuthorityService.listByRuntimeTarget(22L, 1L))
        .thenReturn(
            List.of(
                pointer("demo", "production", 22L, 1L, 2L),
                pointer("demo", "staging", 22L, 1L, 3L)));

    Optional<SessionContext> resolved = service.resolveSessionContext("41");

    assertTrue(resolved.isPresent());
    assertEquals(0L, resolved.orElseThrow().gameInstanceId());
    verify(sessionContextService).save(Mockito.any(SessionContext.class));
    verify(gameplayPresenceLifecycleService)
        .clearGameplayBinding(ambiguous, "STALE_ADMISSION_POINTER");
  }

  @Test
  void resolveSessionContextKeepsCurrentGameplayBindingWhenPointerMatches() {
    SessionContext current =
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
    when(sessionContextService.findBySessionId(41L)).thenReturn(Optional.of(current));
    when(sessionContextService.findByTenantAndSessionId(22L, 41L)).thenReturn(Optional.of(current));
    when(pointerAuthorityService.listByRuntimeTarget(22L, 1L))
        .thenReturn(List.of(pointer("demo", "production", 22L, 1L, 2L)));

    Optional<SessionContext> resolved = service.resolveSessionContext("41");

    assertTrue(resolved.isPresent());
    assertSame(current, resolved.orElseThrow());
    verify(sessionContextService, never()).save(Mockito.any(SessionContext.class));
    verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
  }

  @Test
  void resolveUnverifiedSessionContextWithTenantIdNormalizesResolvedContext() {
    SessionContext stale =
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
            1L,
            "SHARED");
    when(sessionContextService.findByTenantAndSessionId(22L, 41L)).thenReturn(Optional.of(stale));
    when(pointerAuthorityService.listByRuntimeTarget(22L, 1L))
        .thenReturn(List.of(pointer("demo", "production", 22L, 1L, 2L)));

    Optional<SessionContext> resolved = service.resolveUnverifiedSessionContext(22L, 41L);

    assertTrue(resolved.isPresent());
    assertEquals(123L, resolved.orElseThrow().accountId());
    assertEquals(0L, resolved.orElseThrow().gameInstanceId());
    verify(sessionContextService).save(Mockito.any(SessionContext.class));
    verify(gameplayPresenceLifecycleService).clearGameplayBinding(stale, "STALE_ADMISSION_POINTER");
  }

  @Test
  void isAuthenticatedReturnsFalseForNonNumericSessionId() {
    assertFalse(service.isAuthenticated("not-a-session"));
  }

  @Test
  void resolveByGameplayIdentityNormalizesResolvedContext() {
    SessionContext stale =
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
            1L,
            "SHARED");
    when(sessionContextService.findByGameplayIdentity(22L, 1L, 7001L))
        .thenReturn(Optional.of(stale));
    when(pointerAuthorityService.listByRuntimeTarget(22L, 1L))
        .thenReturn(List.of(pointer("demo", "production", 22L, 1L, 2L)));

    Optional<SessionContext> resolved = service.resolveByGameplayIdentity(22L, 1L, 7001L);

    assertTrue(resolved.isPresent());
    assertEquals(0L, resolved.orElseThrow().gameInstanceId());
    verify(sessionContextService).save(Mockito.any(SessionContext.class));
    verify(gameplayPresenceLifecycleService).clearGameplayBinding(stale, "STALE_ADMISSION_POINTER");
  }

  @Test
  void resolveByGameplayNameKeepsCurrentContextWhenPointerMatches() {
    SessionContext current =
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
    when(sessionContextService.findByGameplayName(22L, 1L, "Emberline"))
        .thenReturn(Optional.of(current));
    when(pointerAuthorityService.listByRuntimeTarget(22L, 1L))
        .thenReturn(List.of(pointer("demo", "production", 22L, 1L, 2L)));

    Optional<SessionContext> resolved = service.resolveByGameplayName(22L, 1L, "Emberline");

    assertTrue(resolved.isPresent());
    assertSame(current, resolved.orElseThrow());
    verify(sessionContextService, never()).save(Mockito.any(SessionContext.class));
    verify(gameplayPresenceLifecycleService, never())
        .clearGameplayBinding(Mockito.any(), Mockito.anyString());
  }

  private static GameplayAdmissionPointerSnapshot pointer(
      String worldSlug, String realmSlug, long tenantId, long gameInstanceId, long pointerVersion) {
    return new GameplayAdmissionPointerSnapshot(
        worldSlug,
        worldSlug,
        realmSlug,
        realmSlug,
        tenantId,
        gameInstanceId,
        pointerVersion,
        true,
        true,
        false,
        "SHARED",
        "ALLOW_NEW");
  }
}
