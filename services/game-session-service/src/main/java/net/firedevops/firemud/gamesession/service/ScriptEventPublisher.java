package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.entity.GameplayCommand;

public interface ScriptEventPublisher {
  void publishCommandEvent(SessionContext context, GameplayCommand command);

  void publishRegionTransitionEvents(
      SessionContext previousContext, SessionContext currentContext, String effectId);
}
