package net.firedevops.firemud.gamesession.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.common.config.FiremudCommandHistoryProperties;
import net.firedevops.firemud.gamesession.config.EffectiveCommandHistorySettingsResolver;
import net.firedevops.firemud.gamesession.repository.PlayerCommandHistoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlayerCommandHistoryRetentionSweepJobTest {
  @Test
  void trimsInactiveScopesToTheirCurrentEffectiveMaximum() {
    PlayerCommandHistoryRepository repository = Mockito.mock(PlayerCommandHistoryRepository.class);
    PlayerCommandHistoryStorageService storageService =
        Mockito.mock(PlayerCommandHistoryStorageService.class);
    EffectiveCommandHistorySettingsResolver settingsResolver =
        Mockito.mock(EffectiveCommandHistorySettingsResolver.class);
    PlayerCommandHistoryRepository.HistoryScope scope =
        new PlayerCommandHistoryRepository.HistoryScope(22L, 7L, 13L);
    when(repository.tryLockRetentionSweep()).thenReturn(true);
    when(repository.findDistinctScopesAfter(null, 500)).thenReturn(List.of(scope));
    when(settingsResolver.commandHistory(22L, 7L))
        .thenReturn(new FiremudCommandHistoryProperties(true, 4));
    PlayerCommandHistoryRetentionSweepJob job =
        new PlayerCommandHistoryRetentionSweepJob(repository, storageService, settingsResolver);

    job.trimInactiveHistory();

    verify(storageService).trimToMaxEntries(22L, 7L, 13L, 4);
  }

  @Test
  void skipsTheSweepWhenAnotherReplicaOwnsTheLease() {
    PlayerCommandHistoryRepository repository = Mockito.mock(PlayerCommandHistoryRepository.class);
    PlayerCommandHistoryStorageService storageService =
        Mockito.mock(PlayerCommandHistoryStorageService.class);
    EffectiveCommandHistorySettingsResolver settingsResolver =
        Mockito.mock(EffectiveCommandHistorySettingsResolver.class);
    when(repository.tryLockRetentionSweep()).thenReturn(false);
    PlayerCommandHistoryRetentionSweepJob job =
        new PlayerCommandHistoryRetentionSweepJob(repository, storageService, settingsResolver);

    job.trimInactiveHistory();

    verify(repository).tryLockRetentionSweep();
    verifyNoMoreInteractions(repository, storageService, settingsResolver);
  }

  @Test
  void continuesAfterOneScopeFails() {
    PlayerCommandHistoryRepository repository = Mockito.mock(PlayerCommandHistoryRepository.class);
    PlayerCommandHistoryStorageService storageService =
        Mockito.mock(PlayerCommandHistoryStorageService.class);
    EffectiveCommandHistorySettingsResolver settingsResolver =
        Mockito.mock(EffectiveCommandHistorySettingsResolver.class);
    PlayerCommandHistoryRepository.HistoryScope failingScope =
        new PlayerCommandHistoryRepository.HistoryScope(22L, 7L, 13L);
    PlayerCommandHistoryRepository.HistoryScope succeedingScope =
        new PlayerCommandHistoryRepository.HistoryScope(22L, 7L, 14L);
    when(repository.tryLockRetentionSweep()).thenReturn(true);
    when(repository.findDistinctScopesAfter(null, 500))
        .thenReturn(List.of(failingScope, succeedingScope));
    when(settingsResolver.commandHistory(22L, 7L))
        .thenReturn(new FiremudCommandHistoryProperties(true, 4));
    Mockito.doThrow(new IllegalStateException("database unavailable"))
        .when(storageService)
        .trimToMaxEntries(22L, 7L, 13L, 4);
    PlayerCommandHistoryRetentionSweepJob job =
        new PlayerCommandHistoryRetentionSweepJob(repository, storageService, settingsResolver);

    job.trimInactiveHistory();

    verify(storageService).trimToMaxEntries(22L, 7L, 13L, 4);
    verify(storageService).trimToMaxEntries(22L, 7L, 14L, 4);
  }
}
