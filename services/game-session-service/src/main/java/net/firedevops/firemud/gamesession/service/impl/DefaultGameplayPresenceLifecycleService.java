package net.firedevops.firemud.gamesession.service.impl;

import java.util.Objects;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceService;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public final class DefaultGameplayPresenceLifecycleService
    implements GameplayPresenceLifecycleService {
  private final GameplayPresenceService gameplayPresenceService;
  private final AccountRecentPresenceService accountRecentPresenceService;
  private final SessionContextService sessionContextService;
  private final ScriptEventPublisher scriptEventPublisher;

  public DefaultGameplayPresenceLifecycleService(
      GameplayPresenceService gameplayPresenceService,
      AccountRecentPresenceService accountRecentPresenceService,
      SessionContextService sessionContextService,
      ScriptEventPublisher scriptEventPublisher) {
    this.gameplayPresenceService =
        Objects.requireNonNull(gameplayPresenceService, "gameplayPresenceService must not be null");
    this.accountRecentPresenceService =
        Objects.requireNonNull(
            accountRecentPresenceService, "accountRecentPresenceService must not be null");
    this.sessionContextService =
        Objects.requireNonNull(sessionContextService, "sessionContextService must not be null");
    this.scriptEventPublisher =
        Objects.requireNonNull(scriptEventPublisher, "scriptEventPublisher must not be null");
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
  public void clearGameplayBinding(SessionContext context, String clearReason) {
    if (context == null) {
      return;
    }
    if (hasGameplayRegionBinding(context)) {
      scriptEventPublisher.publishRegionExitEvent(
          context, clearedBindingEventId(context, clearReason), clearReason);
    }
    gameplayPresenceService.removeBySessionId(context.sessionId());
  }

  @Override
  public void recordDisconnected(long sessionId, AccountRecentPresenceDisposition disposition) {
    sessionContextService
        .findBySessionId(sessionId)
        .filter(this::hasGameplayRegionBinding)
        .ifPresent(
            context ->
                scriptEventPublisher.publishRegionExitEvent(
                    context, disconnectEventId(context, disposition), disposition.name()));
    accountRecentPresenceService.recordDisconnect(sessionId, disposition);
    gameplayPresenceService.removeBySessionId(sessionId);
  }

  private boolean hasGameplayRegionBinding(SessionContext context) {
    return context.gameInstanceId() > 0
        && context.characterId() > 0
        && StringUtils.hasText(context.roomInstanceId());
  }

  private static String disconnectEventId(
      SessionContext context, AccountRecentPresenceDisposition disposition) {
    if (disposition == AccountRecentPresenceDisposition.LOGOUT) {
      return "logout:"
          + context.sessionId()
          + ":"
          + context.gameInstanceId()
          + ":"
          + context.characterId();
    }
    return "disconnect:"
        + disposition.name().toLowerCase(java.util.Locale.ROOT)
        + ":"
        + context.sessionId()
        + ":"
        + context.gameInstanceId()
        + ":"
        + context.characterId();
  }

  private static String clearedBindingEventId(SessionContext context, String clearReason) {
    String normalizedReason =
        clearReason == null || clearReason.isBlank()
            ? "cleared"
            : clearReason.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
    return "clear:"
        + normalizedReason
        + ":"
        + context.sessionId()
        + ":"
        + context.gameInstanceId()
        + ":"
        + context.characterId();
  }
}
