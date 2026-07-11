package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.PlayerCommandHistory.PLAYER_COMMAND_HISTORY;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.gamesession.entity.PlayerCommandHistoryEntry;
import net.firedevops.firemud.gamesession.jooq.tables.records.PlayerCommandHistoryRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** Persists accepted command-history rows for a specific player context. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class PlayerCommandHistoryRepository {
  private final DSLContext dsl;

  public PlayerCommandHistoryRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public void save(PlayerCommandHistoryEntry entry) {
    if (entry == null) {
      return;
    }
    PlayerCommandHistoryRecord record = dsl.newRecord(PLAYER_COMMAND_HISTORY);
    populate(record, entry);
    record.store();
    entry.setId(record.getId());
  }

  public List<PlayerCommandHistoryEntry> findByScope(
      long tenantId, long gameInstanceId, long characterId) {
    return dsl.selectFrom(PLAYER_COMMAND_HISTORY)
        .where(
            PLAYER_COMMAND_HISTORY
                .TENANT_ID
                .eq(tenantId)
                .and(PLAYER_COMMAND_HISTORY.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(PLAYER_COMMAND_HISTORY.CHARACTER_ID.eq(characterId)))
        .orderBy(PLAYER_COMMAND_HISTORY.ACCEPTED_AT.asc(), PLAYER_COMMAND_HISTORY.ID.asc())
        .fetch(this::toEntity);
  }

  public void deleteByIds(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    dsl.deleteFrom(PLAYER_COMMAND_HISTORY).where(PLAYER_COMMAND_HISTORY.ID.in(ids)).execute();
  }

  public void deleteByScope(long tenantId, long gameInstanceId, long characterId) {
    dsl.deleteFrom(PLAYER_COMMAND_HISTORY)
        .where(
            PLAYER_COMMAND_HISTORY
                .TENANT_ID
                .eq(tenantId)
                .and(PLAYER_COMMAND_HISTORY.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(PLAYER_COMMAND_HISTORY.CHARACTER_ID.eq(characterId)))
        .execute();
  }

  private void populate(PlayerCommandHistoryRecord record, PlayerCommandHistoryEntry entry) {
    record.setTenantId(entry.getTenantId());
    record.setGameInstanceId(entry.getGameInstanceId());
    record.setCharacterId(entry.getCharacterId());
    record.setCommandText(entry.getCommandText());
    record.setAcceptedAt(toLocalDateTime(entry.getAcceptedAt()));
  }

  private PlayerCommandHistoryEntry toEntity(Record record) {
    PlayerCommandHistoryEntry entry = new PlayerCommandHistoryEntry();
    entry.setId(record.get(PLAYER_COMMAND_HISTORY.ID));
    entry.setTenantId(record.get(PLAYER_COMMAND_HISTORY.TENANT_ID));
    entry.setGameInstanceId(record.get(PLAYER_COMMAND_HISTORY.GAME_INSTANCE_ID));
    entry.setCharacterId(record.get(PLAYER_COMMAND_HISTORY.CHARACTER_ID));
    entry.setCommandText(record.get(PLAYER_COMMAND_HISTORY.COMMAND_TEXT));
    entry.setAcceptedAt(toInstant(record.get(PLAYER_COMMAND_HISTORY.ACCEPTED_AT)));
    return entry;
  }
}
