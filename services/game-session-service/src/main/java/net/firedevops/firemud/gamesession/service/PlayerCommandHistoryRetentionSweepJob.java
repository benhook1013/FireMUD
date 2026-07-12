package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import net.firedevops.firemud.gamesession.config.EffectiveCommandHistorySettingsResolver;
import net.firedevops.firemud.gamesession.repository.PlayerCommandHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Gradually enforces lowered command-history caps even for inactive characters. */
@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repository and storage service are framework-managed collaborators.")
public class PlayerCommandHistoryRetentionSweepJob {
  private static final Logger LOG =
      LoggerFactory.getLogger(PlayerCommandHistoryRetentionSweepJob.class);
  private static final int BATCH_SIZE = 500;

  private final PlayerCommandHistoryRepository repository;
  private final PlayerCommandHistoryStorageService storageService;
  private final EffectiveCommandHistorySettingsResolver settingsResolver;
  private PlayerCommandHistoryRepository.HistoryScope cursor;

  public PlayerCommandHistoryRetentionSweepJob(
      PlayerCommandHistoryRepository repository,
      PlayerCommandHistoryStorageService storageService,
      EffectiveCommandHistorySettingsResolver settingsResolver) {
    this.repository = repository;
    this.storageService = storageService;
    this.settingsResolver = settingsResolver;
  }

  @Scheduled(fixedDelayString = "${firemud.command-history.retention-sweep-ms:60000}")
  @Transactional
  public void trimInactiveHistory() {
    if (!repository.tryLockRetentionSweep()) {
      return;
    }
    List<PlayerCommandHistoryRepository.HistoryScope> scopes =
        repository.findDistinctScopesAfter(cursor, BATCH_SIZE);
    if (scopes.isEmpty()) {
      cursor = null;
      return;
    }
    for (PlayerCommandHistoryRepository.HistoryScope scope : scopes) {
      try {
        int maxEntries =
            settingsResolver.commandHistory(scope.tenantId(), scope.gameInstanceId()).maxEntries();
        storageService.trimToMaxEntries(
            scope.tenantId(), scope.gameInstanceId(), scope.characterId(), maxEntries);
      } catch (RuntimeException ex) {
        LOG.warn(
            "Player command-history retention sweep failed tenantId={} gameInstanceId={} characterId={}",
            scope.tenantId(),
            scope.gameInstanceId(),
            scope.characterId(),
            ex);
      }
    }
    cursor = scopes.getLast();
  }
}
