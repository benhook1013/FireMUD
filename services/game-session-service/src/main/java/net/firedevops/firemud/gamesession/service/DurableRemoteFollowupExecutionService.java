package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.entity.TickEffect;

public interface DurableRemoteFollowupExecutionService {
  DurableRemoteFollowupExecutionResult execute(TickEffect effect);

  record DurableRemoteFollowupExecutionResult(
      String effectStatus, String failureCode, String failureMessage) {}
}
