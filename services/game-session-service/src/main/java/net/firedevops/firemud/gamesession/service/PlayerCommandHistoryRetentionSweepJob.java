package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
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
  // Bound one forward pass so newly created high-sort scopes cannot starve older scopes forever.
  private static final int MAX_BATCHES_PER_PASS = 20;

  private final PlayerCommandHistoryRepository repository;
  private final PlayerCommandHistoryStorageService storageService;
  private final EffectiveCommandHistorySettingsResolver settingsResolver;

  public PlayerCommandHistoryRetentionSweepJob(
      PlayerCommandHistoryRepository repository,
      PlayerCommandHistoryStorageService storageService,
      EffectiveCommandHistorySettingsResolver settingsResolver) {
    this.repository = repository;
    this.storageService = storageService;
    this.settingsResolver = settingsResolver;
  }

  @Scheduled(
      fixedDelayString = "${firemud.command-history.retention-sweep-ms:60000}",
      scheduler = "commandHistoryRetentionScheduler")
  @Transactional
  public void trimInactiveHistory() {
    if (!repository.tryLockRetentionSweep()) {
      return;
    }
    PlayerCommandHistoryRepository.RetentionSweepState sweepState =
        repository.retentionSweepState();
    PlayerCommandHistoryRepository.HistoryScope cursor = sweepState.cursor();
    List<PlayerCommandHistoryRepository.HistoryScope> scopes =
        repository.findDistinctScopesAfter(cursor, BATCH_SIZE);
    if (scopes.isEmpty()) {
      repository.saveRetentionSweepState(
          new PlayerCommandHistoryRepository.RetentionSweepState(null, 0));
      return;
    }
    Map<TenantGameScope, Integer> maxEntriesByScope = new java.util.HashMap<>();
    for (PlayerCommandHistoryRepository.HistoryScope scope : scopes) {
      try {
        TenantGameScope settingsScope =
            new TenantGameScope(scope.tenantId(), scope.gameInstanceId());
        int maxEntries =
            maxEntriesByScope.computeIfAbsent(
                settingsScope,
                ignored ->
                    settingsResolver
                        .commandHistory(scope.tenantId(), scope.gameInstanceId())
                        .maxEntries());
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
    int nextBatchCount = sweepState.batchesSinceWrap() + 1;
    if (nextBatchCount >= MAX_BATCHES_PER_PASS) {
      repository.saveRetentionSweepState(
          new PlayerCommandHistoryRepository.RetentionSweepState(null, 0));
      return;
    }
    repository.saveRetentionSweepState(
        new PlayerCommandHistoryRepository.RetentionSweepState(scopes.getLast(), nextBatchCount));
  }

  private record TenantGameScope(long tenantId, long gameInstanceId) {}
}
