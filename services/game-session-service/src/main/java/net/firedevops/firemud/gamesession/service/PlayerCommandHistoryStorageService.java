package net.firedevops.firemud.gamesession.service;

import java.time.Clock;
import java.time.Instant;
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

  /**
   * Records history independently of the gameplay transaction. History is best-effort observability
   * and must not roll back an otherwise accepted player command when its storage is unavailable.
   */
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
    int startIndex = Math.max(0, entries.size() - maxEntries);
    return entries.subList(startIndex, entries.size()).stream()
        .map(PlayerCommandHistoryEntry::getCommandText)
        .toList();
  }

  private void trimLocked(long tenantId, long gameInstanceId, long characterId, int maxEntries) {
    int excess = repository.countByScope(tenantId, gameInstanceId, characterId) - maxEntries;
    if (excess <= 0) {
      return;
    }
    repository.deleteOldestByScope(tenantId, gameInstanceId, characterId, excess);
  }
}
