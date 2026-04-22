package net.firedevops.firemud.gamesession.service;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;

public interface DurableGameplayReplayService {
  Optional<ReplayRecord> find(long tenantId, long sessionId, String effectId);

  void save(
      long tenantId,
      long sessionId,
      String effectId,
      boolean accepted,
      String failureCode,
      String failureMessage,
      List<PlayerOutput> actorOutputs);

  record ReplayRecord(
      boolean accepted,
      String failureCode,
      String failureMessage,
      List<PlayerOutput> actorOutputs) {
    public ReplayRecord {
      actorOutputs = actorOutputs == null ? List.of() : List.copyOf(actorOutputs);
    }
  }
}
