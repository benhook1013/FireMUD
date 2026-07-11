package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import net.firedevops.firemud.gamesession.entity.PlayerCommandHistoryEntry;
import net.firedevops.firemud.gamesession.repository.PlayerCommandHistoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PlayerCommandHistoryStorageServiceTest {
  private static final long TENANT_ID = 22L;
  private static final long GAME_INSTANCE_ID = 7L;
  private static final long CHARACTER_ID = 13L;
  private static final Instant ACCEPTED_AT = Instant.parse("2026-07-12T01:00:00Z");

  private final PlayerCommandHistoryRepository repository =
      Mockito.mock(PlayerCommandHistoryRepository.class);
  private final Clock clock = Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC);
  private final PlayerCommandHistoryStorageService service =
      new PlayerCommandHistoryStorageService(repository, clock);

  @Test
  void appendPersistsAndTrimsToConfiguredMaximum() {
    when(repository.findByScope(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID))
        .thenReturn(List.of(existingEntry(1L), existingEntry(2L)));

    service.append(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID, "say hi", 1);

    ArgumentCaptor<PlayerCommandHistoryEntry> entryCaptor = ArgumentCaptor.captor();
    verify(repository).save(entryCaptor.capture());
    PlayerCommandHistoryEntry saved = entryCaptor.getValue();
    assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
    assertThat(saved.getGameInstanceId()).isEqualTo(GAME_INSTANCE_ID);
    assertThat(saved.getCharacterId()).isEqualTo(CHARACTER_ID);
    assertThat(saved.getCommandText()).isEqualTo("say hi");
    assertThat(saved.getAcceptedAt()).isEqualTo(ACCEPTED_AT);
    verify(repository).deleteByIds(List.of(1L));
  }

  @Test
  void findRecentReturnsNewestEntriesInOldestToNewestOrder() {
    when(repository.findByScope(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID))
        .thenReturn(
            List.of(
                historyEntry(1L, "old"), historyEntry(2L, "middle"), historyEntry(3L, "newest")));

    assertThat(service.findRecent(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID, 2))
        .containsExactly("middle", "newest");
  }

  private PlayerCommandHistoryEntry existingEntry(Long id) {
    PlayerCommandHistoryEntry entry = new PlayerCommandHistoryEntry();
    entry.setId(id);
    entry.setTenantId(TENANT_ID);
    entry.setGameInstanceId(GAME_INSTANCE_ID);
    entry.setCharacterId(CHARACTER_ID);
    return entry;
  }

  private PlayerCommandHistoryEntry historyEntry(Long id, String commandText) {
    PlayerCommandHistoryEntry entry = existingEntry(id);
    entry.setCommandText(commandText);
    return entry;
  }
}
