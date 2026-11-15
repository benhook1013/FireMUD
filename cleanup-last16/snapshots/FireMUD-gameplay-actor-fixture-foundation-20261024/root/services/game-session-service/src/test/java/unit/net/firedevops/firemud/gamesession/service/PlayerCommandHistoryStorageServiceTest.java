package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import net.firedevops.firemud.gamesession.entity.PlayerCommandHistoryEntry;
import net.firedevops.firemud.gamesession.repository.PlayerCommandHistoryRepository;
import net.firedevops.firemud.gamesession.test.GameplayHistoryTestFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PlayerCommandHistoryStorageServiceTest {
  private static final long TENANT_ID = 22L;
  private static final long GAME_INSTANCE_ID = 7L;
  private static final PlayerCommandHistoryRepository.HistoryScope SCOPE =
      GameplayHistoryTestFixtures.retainedPlayerScope();
  private static final Instant ACCEPTED_AT = Instant.parse("2026-07-12T01:00:00Z");

  private final PlayerCommandHistoryRepository repository =
      Mockito.mock(PlayerCommandHistoryRepository.class);
  private final Clock clock = Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC);
  private final PlayerCommandHistoryStorageService service =
      new PlayerCommandHistoryStorageService(repository, clock);

  @Test
  void appendPersistsAndTrimsToConfiguredMaximum() {
    when(repository.countByScope(TENANT_ID, GAME_INSTANCE_ID, SCOPE.characterId())).thenReturn(3);

    service.append(TENANT_ID, GAME_INSTANCE_ID, SCOPE.characterId(), "say hi", 1);

    verify(repository).lockScope(TENANT_ID, GAME_INSTANCE_ID, SCOPE.characterId());
    ArgumentCaptor<PlayerCommandHistoryEntry> entryCaptor = ArgumentCaptor.captor();
    verify(repository).save(entryCaptor.capture());
    PlayerCommandHistoryEntry saved = entryCaptor.getValue();
    assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
    assertThat(saved.getGameInstanceId()).isEqualTo(GAME_INSTANCE_ID);
    assertThat(saved.getCharacterId()).isEqualTo(SCOPE.characterId());
    assertThat(saved.getCommandText()).isEqualTo("say hi");
    assertThat(saved.getAcceptedAt()).isEqualTo(ACCEPTED_AT);
    verify(repository).deleteOldestByScope(TENANT_ID, GAME_INSTANCE_ID, SCOPE.characterId(), 2);
  }

  @Test
  void findRecentReturnsNewestEntriesInOldestToNewestOrder() {
    when(repository.findByScope(TENANT_ID, GAME_INSTANCE_ID, SCOPE.characterId()))
        .thenReturn(
            List.of(
                historyEntry(1L, "old"), historyEntry(2L, "middle"), historyEntry(3L, "newest")));

    assertThat(service.findRecent(TENANT_ID, GAME_INSTANCE_ID, SCOPE.characterId(), 2))
        .containsExactly("middle", "newest");
    verify(repository).lockScope(TENANT_ID, GAME_INSTANCE_ID, SCOPE.characterId());
    verify(repository, never()).deleteByIds(Mockito.anyCollection());
  }

  @Test
  void appendSkipsPersistenceForNullCommandTextOrNonPositiveMaximum() {
    service.append(TENANT_ID, GAME_INSTANCE_ID, SCOPE.characterId(), null, 5);
    service.append(TENANT_ID, GAME_INSTANCE_ID, SCOPE.characterId(), "say hi", 0);

    Mockito.verifyNoInteractions(repository);
  }

  @Test
  void findRecentReturnsEmptyWithoutReadingForNonPositiveMaximum() {
    assertThat(service.findRecent(TENANT_ID, GAME_INSTANCE_ID, SCOPE.characterId(), 0)).isEmpty();

    Mockito.verifyNoInteractions(repository);
  }

  private PlayerCommandHistoryEntry existingEntry(Long id) {
    PlayerCommandHistoryEntry entry = GameplayHistoryTestFixtures.retainedCommand(null, null);
    entry.setId(id);
    return entry;
  }

  private PlayerCommandHistoryEntry historyEntry(Long id, String commandText) {
    PlayerCommandHistoryEntry entry = existingEntry(id);
    entry.setCommandText(commandText);
    return entry;
  }
}
