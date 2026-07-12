package net.firedevops.firemud.gamesession.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.firedevops.firemud.gamesession.entity.PlayerCommandHistoryEntry;
import net.firedevops.firemud.gamesession.repository.PlayerCommandHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable per-player command-history storage with bounded retention by configured maximum. */
@Service
public class PlayerCommandHistoryStorageService {
  private final PlayerCommandHistoryRepository repository;
  private final Clock clock;

  @Autowired
  public PlayerCommandHistoryStorageService(PlayerCommandHistoryRepository repository) {
    this(repository, Clock.systemUTC());
  }

  PlayerCommandHistoryStorageService(PlayerCommandHistoryRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void append(
      long tenantId, long gameInstanceId, long characterId, String commandText, int maxEntries) {
    if (commandText == null || maxEntries <= 0) {
      return;
    }

    repository.lockScope(tenantId, gameInstanceId, characterId);
    PlayerCommandHistoryEntry entry = new PlayerCommandHistoryEntry();
    entry.setTenantId(tenantId);
    entry.setGameInstanceId(gameInstanceId);
    entry.setCharacterId(characterId);
    entry.setCommandText(commandText);
    entry.setAcceptedAt(Instant.now(clock));
    repository.save(entry);
    trimLocked(tenantId, gameInstanceId, characterId, maxEntries);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void trimToMaxEntries(
      long tenantId, long gameInstanceId, long characterId, int maxEntries) {
    if (maxEntries <= 0) {
      return;
    }
    repository.lockScope(tenantId, gameInstanceId, characterId);
    trimLocked(tenantId, gameInstanceId, characterId, maxEntries);
  }

  @Transactional
  public List<String> findRecent(
      long tenantId, long gameInstanceId, long characterId, int maxEntries) {
    if (maxEntries <= 0) {
      return List.of();
    }
    repository.lockScope(tenantId, gameInstanceId, characterId);
    List<PlayerCommandHistoryEntry> entries =
        repository.findByScope(tenantId, gameInstanceId, characterId);
    if (entries.size() <= maxEntries) {
      return entries.stream().map(PlayerCommandHistoryEntry::getCommandText).toList();
    }
    repository.deleteByIds(
        entries.stream()
            .limit(entries.size() - maxEntries)
            .map(PlayerCommandHistoryEntry::getId)
            .toList());
    int startIndex = entries.size() - maxEntries;
    return new ArrayList<>(entries.subList(startIndex, entries.size()))
        .stream().map(PlayerCommandHistoryEntry::getCommandText).toList();
  }

  private void trimLocked(long tenantId, long gameInstanceId, long characterId, int maxEntries) {
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
