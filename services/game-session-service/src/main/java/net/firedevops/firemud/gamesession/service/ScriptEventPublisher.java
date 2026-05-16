package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.entity.GameplayCommand;

public interface ScriptEventPublisher {
  void publishCommandEvent(SessionContext context, GameplayCommand command);

  void publishSpawnEvent(SessionContext context, String spawnReason, String scriptEventId);

  void publishRegionTransitionEvents(
      SessionContext previousContext, SessionContext currentContext, String effectId);

  default void publishRegionExitEvent(SessionContext context, String scriptEventId) {
    publishRegionExitEvent(context, scriptEventId, null);
  }

  void publishRegionExitEvent(SessionContext context, String scriptEventId, String exitReason);
}
