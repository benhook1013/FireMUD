package net.firedevops.firemud.gamesession.service.impl;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceService;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DefaultGameplayPresenceLifecycleServiceTest {
  private final GameplayPresenceService gameplayPresenceService =
      Mockito.mock(GameplayPresenceService.class);
  private final AccountRecentPresenceService accountRecentPresenceService =
      Mockito.mock(AccountRecentPresenceService.class);
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final ScriptEventPublisher scriptEventPublisher =
      Mockito.mock(ScriptEventPublisher.class);
  private final DefaultGameplayPresenceLifecycleService service =
      new DefaultGameplayPresenceLifecycleService(
          gameplayPresenceService,
          accountRecentPresenceService,
          sessionContextService,
          scriptEventPublisher);

  @Test
  void registerConnectedUpdatesLiveAndRecentPresence() {
    SessionContext context =
        new SessionContext(41L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 1L, "1021", "");

    service.registerConnected(context);

    verify(gameplayPresenceService).registerConnected(context);
    verify(accountRecentPresenceService).recordConnected(context);
  }

  @Test
  void recordActivityUpdatesLiveAndRecentPresence() {
    service.recordActivity(41L, true);

    verify(gameplayPresenceService).recordCommandActivity(41L, true);
    verify(accountRecentPresenceService).recordActivity(41L);
  }

  @Test
  void recordDisconnectedWritesRecentPresenceBeforeRemovingLivePresence() {
    whenGameplayContextPresent(
        new SessionContext(41L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 1L, "1021", ""));

    service.recordDisconnected(41L, AccountRecentPresenceDisposition.TRANSPORT_LOSS);

    org.mockito.InOrder inOrder =
        Mockito.inOrder(
            scriptEventPublisher, accountRecentPresenceService, gameplayPresenceService);
    inOrder
        .verify(scriptEventPublisher)
        .publishRegionExitEvent(
            Mockito.any(SessionContext.class),
            Mockito.eq("disconnect:transport_loss:41:1:7001"),
            Mockito.eq("TRANSPORT_LOSS"));
    inOrder
        .verify(accountRecentPresenceService)
        .recordDisconnect(41L, AccountRecentPresenceDisposition.TRANSPORT_LOSS);
    inOrder.verify(gameplayPresenceService).removeBySessionId(41L);
  }

  @Test
  void recordDisconnectedPreservesCanonicalLogoutEventId() {
    SessionContext context =
        new SessionContext(41L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 1L, "1021", "");
    whenGameplayContextPresent(context);

    service.recordDisconnected(41L, AccountRecentPresenceDisposition.LOGOUT);

    verify(scriptEventPublisher).publishRegionExitEvent(context, "logout:41:1:7001", "LOGOUT");
  }

  @Test
  void recordDisconnectedSkipsLifecycleEventWithoutGameplayRegionBinding() {
    whenGameplayContextPresent(
        new SessionContext(41L, 22L, 0L, "demo@example.com", 7001L, "Emberline", 0L, "", ""));

    service.recordDisconnected(41L, AccountRecentPresenceDisposition.TRANSPORT_LOSS);

    verify(scriptEventPublisher, never())
        .publishRegionExitEvent(Mockito.any(SessionContext.class), anyString(), anyString());
    verify(accountRecentPresenceService)
        .recordDisconnect(41L, AccountRecentPresenceDisposition.TRANSPORT_LOSS);
    verify(gameplayPresenceService).removeBySessionId(41L);
  }

  private void whenGameplayContextPresent(SessionContext context) {
    Mockito.when(sessionContextService.findBySessionId(context.sessionId()))
        .thenReturn(Optional.of(context));
  }
}
