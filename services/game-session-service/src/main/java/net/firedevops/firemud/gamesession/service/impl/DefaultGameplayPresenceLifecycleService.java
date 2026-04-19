package net.firedevops.firemud.gamesession.service.impl;

import java.util.Objects;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceService;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Service;

@Service
public final class DefaultGameplayPresenceLifecycleService
    implements GameplayPresenceLifecycleService {
  private final GameplayPresenceService gameplayPresenceService;
  private final AccountRecentPresenceService accountRecentPresenceService;

  public DefaultGameplayPresenceLifecycleService(
      GameplayPresenceService gameplayPresenceService,
      AccountRecentPresenceService accountRecentPresenceService) {
    this.gameplayPresenceService =
        Objects.requireNonNull(gameplayPresenceService, "gameplayPresenceService must not be null");
    this.accountRecentPresenceService =
        Objects.requireNonNull(
            accountRecentPresenceService, "accountRecentPresenceService must not be null");
  }

  @Override
  public void registerConnected(SessionContext context) {
    gameplayPresenceService.registerConnected(context);
    accountRecentPresenceService.recordConnected(context);
  }

  @Override
  public void recordActivity(long sessionId, boolean meaningfulGameplayActivity) {
    gameplayPresenceService.recordCommandActivity(sessionId, meaningfulGameplayActivity);
    accountRecentPresenceService.recordActivity(sessionId);
  }

  @Override
  public void recordDisconnected(long sessionId, AccountRecentPresenceDisposition disposition) {
    accountRecentPresenceService.recordDisconnect(sessionId, disposition);
    gameplayPresenceService.removeBySessionId(sessionId);
  }
}
