package net.firedevops.firemud.gamesession.service.impl;

import static org.mockito.Mockito.verify;

import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceService;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DefaultGameplayPresenceLifecycleServiceTest {
  private final GameplayPresenceService gameplayPresenceService =
      Mockito.mock(GameplayPresenceService.class);
  private final AccountRecentPresenceService accountRecentPresenceService =
      Mockito.mock(AccountRecentPresenceService.class);
  private final DefaultGameplayPresenceLifecycleService service =
      new DefaultGameplayPresenceLifecycleService(
          gameplayPresenceService, accountRecentPresenceService);

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
    service.recordDisconnected(41L, AccountRecentPresenceDisposition.TRANSPORT_LOSS);

    org.mockito.InOrder inOrder =
        Mockito.inOrder(accountRecentPresenceService, gameplayPresenceService);
    inOrder
        .verify(accountRecentPresenceService)
        .recordDisconnect(41L, AccountRecentPresenceDisposition.TRANSPORT_LOSS);
    inOrder.verify(gameplayPresenceService).removeBySessionId(41L);
  }
}
