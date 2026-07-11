package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.firedevops.firemud.gamesession.entity.PlayerCommandHistoryEntry;
import net.firedevops.firemud.gamesession.repository.PlayerCommandHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable per-player command-history storage with bounded retention by configured maximum. */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repository and clock are internal Spring collaborators.")
public class PlayerCommandHistoryStorageService {
  private final PlayerCommandHistoryRepository repository;
  private final Clock clock;

  public PlayerCommandHistoryStorageService(PlayerCommandHistoryRepository repository) {
    this(repository, Clock.systemUTC());
  }

  PlayerCommandHistoryStorageService(PlayerCommandHistoryRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional
  public void append(
      long tenantId, long gameInstanceId, long characterId, String commandText, int maxEntries) {
    if (commandText == null || maxEntries <= 0) {
      return;
    }

    PlayerCommandHistoryEntry entry = new PlayerCommandHistoryEntry();
    entry.setTenantId(tenantId);
    entry.setGameInstanceId(gameInstanceId);
    entry.setCharacterId(characterId);
    entry.setCommandText(commandText);
    entry.setAcceptedAt(Instant.now(clock));
    repository.save(entry);
    trimToMaxEntries(tenantId, gameInstanceId, characterId, maxEntries);
  }

  public List<String> findRecent(
      long tenantId, long gameInstanceId, long characterId, int maxEntries) {
    if (maxEntries <= 0) {
      return List.of();
    }
    List<PlayerCommandHistoryEntry> entries =
        repository.findByScope(tenantId, gameInstanceId, characterId);
    if (entries.size() <= maxEntries) {
      return entries.stream().map(PlayerCommandHistoryEntry::getCommandText).toList();
    }
    int startIndex = entries.size() - maxEntries;
    return new ArrayList<>(entries.subList(startIndex, entries.size()))
        .stream().map(PlayerCommandHistoryEntry::getCommandText).toList();
  }

  private void trimToMaxEntries(
      long tenantId, long gameInstanceId, long characterId, int maxEntries) {
    List<PlayerCommandHistoryEntry> retained =
        new ArrayList<>(repository.findByScope(tenantId, gameInstanceId, characterId));
    if (retained.size() <= maxEntries) {
      return;
    }

    List<Long> toDelete =
        retained.stream()
            .limit(retained.size() - maxEntries)
            .map(PlayerCommandHistoryEntry::getId)
            .toList();
    repository.deleteByIds(toDelete);
  }
}
