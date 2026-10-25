package net.firedevops.firemud.gamesession.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.common.config.FiremudCommandHistoryProperties;
import net.firedevops.firemud.gamesession.config.EffectiveCommandHistorySettingsResolver;
import net.firedevops.firemud.gamesession.repository.PlayerCommandHistoryRepository;
import net.firedevops.firemud.gamesession.test.GameplayHistoryTestFixtures;
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
        GameplayHistoryTestFixtures.retainedPlayerScope();
    when(repository.tryLockRetentionSweep()).thenReturn(true);
    when(repository.retentionSweepState())
        .thenReturn(new PlayerCommandHistoryRepository.RetentionSweepState(null, 0));
    when(repository.findDistinctScopesAfter(null, 500)).thenReturn(List.of(scope));
    when(settingsResolver.commandHistory(22L, 7L))
        .thenReturn(new FiremudCommandHistoryProperties(4));
    PlayerCommandHistoryRetentionSweepJob job =
        new PlayerCommandHistoryRetentionSweepJob(repository, storageService, settingsResolver);

    job.trimInactiveHistory();

    verify(storageService)
        .trimToMaxEntries(
            22L, 7L, GameplayHistoryTestFixtures.retainedPlayerScope().characterId(), 4);
    verify(repository)
        .saveRetentionSweepState(new PlayerCommandHistoryRepository.RetentionSweepState(scope, 1));
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
        GameplayHistoryTestFixtures.retainedPlayerScope();
    PlayerCommandHistoryRepository.HistoryScope succeedingScope =
        GameplayHistoryTestFixtures.nextRetainedPlayerScope();
    when(repository.tryLockRetentionSweep()).thenReturn(true);
    when(repository.retentionSweepState())
        .thenReturn(new PlayerCommandHistoryRepository.RetentionSweepState(null, 0));
    when(repository.findDistinctScopesAfter(null, 500))
        .thenReturn(List.of(failingScope, succeedingScope));
    when(settingsResolver.commandHistory(22L, 7L))
        .thenReturn(new FiremudCommandHistoryProperties(4));
    Mockito.doThrow(new IllegalStateException("database unavailable"))
        .when(storageService)
        .trimToMaxEntries(
            22L, 7L, GameplayHistoryTestFixtures.retainedPlayerScope().characterId(), 4);
    PlayerCommandHistoryRetentionSweepJob job =
        new PlayerCommandHistoryRetentionSweepJob(repository, storageService, settingsResolver);

    job.trimInactiveHistory();

    verify(storageService)
        .trimToMaxEntries(
            22L, 7L, GameplayHistoryTestFixtures.retainedPlayerScope().characterId(), 4);
    verify(storageService)
        .trimToMaxEntries(
            22L, 7L, GameplayHistoryTestFixtures.nextRetainedPlayerScope().characterId(), 4);
  }

  @Test
  void reusesSettingsWithinOneTenantGameBatchAndAdvancesTheDurableCursor() {
    PlayerCommandHistoryRepository repository = Mockito.mock(PlayerCommandHistoryRepository.class);
    PlayerCommandHistoryStorageService storageService =
        Mockito.mock(PlayerCommandHistoryStorageService.class);
    EffectiveCommandHistorySettingsResolver settingsResolver =
        Mockito.mock(EffectiveCommandHistorySettingsResolver.class);
    PlayerCommandHistoryRepository.HistoryScope cursor =
        GameplayHistoryTestFixtures.retentionCursor();
    PlayerCommandHistoryRepository.HistoryScope first =
        GameplayHistoryTestFixtures.retainedPlayerScope();
    PlayerCommandHistoryRepository.HistoryScope second =
        GameplayHistoryTestFixtures.nextRetainedPlayerScope();
    when(repository.tryLockRetentionSweep()).thenReturn(true);
    when(repository.retentionSweepState())
        .thenReturn(new PlayerCommandHistoryRepository.RetentionSweepState(cursor, 3));
    when(repository.findDistinctScopesAfter(cursor, 500)).thenReturn(List.of(first, second));
    when(settingsResolver.commandHistory(22L, 7L))
        .thenReturn(new FiremudCommandHistoryProperties(4));
    PlayerCommandHistoryRetentionSweepJob job =
        new PlayerCommandHistoryRetentionSweepJob(repository, storageService, settingsResolver);

    job.trimInactiveHistory();

    verify(settingsResolver).commandHistory(22L, 7L);
    verify(storageService)
        .trimToMaxEntries(
            22L, 7L, GameplayHistoryTestFixtures.retainedPlayerScope().characterId(), 4);
    verify(storageService)
        .trimToMaxEntries(
            22L, 7L, GameplayHistoryTestFixtures.nextRetainedPlayerScope().characterId(), 4);
    verify(repository)
        .saveRetentionSweepState(new PlayerCommandHistoryRepository.RetentionSweepState(second, 4));
  }

  @Test
  void clearsTheDurableCursorAfterReachingTheEndOfTheSweep() {
    PlayerCommandHistoryRepository repository = Mockito.mock(PlayerCommandHistoryRepository.class);
    PlayerCommandHistoryStorageService storageService =
        Mockito.mock(PlayerCommandHistoryStorageService.class);
    EffectiveCommandHistorySettingsResolver settingsResolver =
        Mockito.mock(EffectiveCommandHistorySettingsResolver.class);
    PlayerCommandHistoryRetentionSweepJob job =
        new PlayerCommandHistoryRetentionSweepJob(repository, storageService, settingsResolver);
    when(repository.tryLockRetentionSweep()).thenReturn(true);
    when(repository.retentionSweepState())
        .thenReturn(
            new PlayerCommandHistoryRepository.RetentionSweepState(
                GameplayHistoryTestFixtures.retainedPlayerScope(), 3));
    when(repository.findDistinctScopesAfter(Mockito.any(), Mockito.anyInt())).thenReturn(List.of());

    job.trimInactiveHistory();

    verify(repository)
        .saveRetentionSweepState(new PlayerCommandHistoryRepository.RetentionSweepState(null, 0));
    verifyNoMoreInteractions(storageService, settingsResolver);
  }

  @Test
  void wrapsTheDurableSweepStateAfterTheBoundedForwardPass() {
    PlayerCommandHistoryRepository repository = Mockito.mock(PlayerCommandHistoryRepository.class);
    PlayerCommandHistoryStorageService storageService =
        Mockito.mock(PlayerCommandHistoryStorageService.class);
    EffectiveCommandHistorySettingsResolver settingsResolver =
        Mockito.mock(EffectiveCommandHistorySettingsResolver.class);
    PlayerCommandHistoryRepository.HistoryScope scope =
        GameplayHistoryTestFixtures.retainedPlayerScope();
    when(repository.tryLockRetentionSweep()).thenReturn(true);
    when(repository.retentionSweepState())
        .thenReturn(new PlayerCommandHistoryRepository.RetentionSweepState(scope, 19));
    when(repository.findDistinctScopesAfter(scope, 500)).thenReturn(List.of(scope));
    when(settingsResolver.commandHistory(22L, 7L))
        .thenReturn(new FiremudCommandHistoryProperties(4));
    PlayerCommandHistoryRetentionSweepJob job =
        new PlayerCommandHistoryRetentionSweepJob(repository, storageService, settingsResolver);

    job.trimInactiveHistory();

    verify(repository)
        .saveRetentionSweepState(new PlayerCommandHistoryRepository.RetentionSweepState(null, 0));
  }
}
