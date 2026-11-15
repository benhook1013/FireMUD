package net.firedevops.firemud.gamesession.test;

import java.time.Instant;
import net.firedevops.firemud.gamesession.entity.PlayerCommandHistoryEntry;
import net.firedevops.firemud.gamesession.repository.PlayerCommandHistoryRepository.HistoryScope;

/** The retained-history scope shared by storage, sweep, and physical repository scenarios. */
public final class GameplayHistoryTestFixtures {
  private GameplayHistoryTestFixtures() {}

  public static HistoryScope retainedPlayerScope() {
    return new HistoryScope(22L, 7L, 13L);
  }

  public static HistoryScope nextRetainedPlayerScope() {
    return new HistoryScope(22L, 7L, 14L);
  }

  public static HistoryScope retentionCursor() {
    return new HistoryScope(21L, 7L, 12L);
  }

  public static PlayerCommandHistoryEntry retainedCommand(String text, Instant acceptedAt) {
    HistoryScope scope = retainedPlayerScope();
    PlayerCommandHistoryEntry entry = new PlayerCommandHistoryEntry();
    entry.setTenantId(scope.tenantId());
    entry.setGameInstanceId(scope.gameInstanceId());
    entry.setCharacterId(scope.characterId());
    entry.setCommandText(text);
    entry.setAcceptedAt(acceptedAt);
    return entry;
  }
}
