package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toOffsetDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.PlayerCommandHistory.PLAYER_COMMAND_HISTORY;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.gamesession.entity.PlayerCommandHistoryEntry;
import net.firedevops.firemud.gamesession.jooq.tables.records.PlayerCommandHistoryRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.springframework.stereotype.Repository;

/** Persists accepted command-history rows for a specific player context. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class PlayerCommandHistoryRepository {
  private static final long RETENTION_SWEEP_LOCK_KEY = 0x5043485357454550L;

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

  /**
   * Serializes command-history mutations for one scope without retaining a lock-row permanently.
   */
  public void lockScope(long tenantId, long gameInstanceId, long characterId) {
    if (dsl.dialect().family() != SQLDialect.POSTGRES) {
      return;
    }
    dsl.execute(
        "select pg_advisory_xact_lock(?)", scopeLockKey(tenantId, gameInstanceId, characterId));
  }

  /** Acquires the cross-replica retention-sweep lease for the current transaction when possible. */
  public boolean tryLockRetentionSweep() {
    if (dsl.dialect().family() != SQLDialect.POSTGRES) {
      return true;
    }
    Record record =
        dsl.fetchOne("select pg_try_advisory_xact_lock(?) as locked", RETENTION_SWEEP_LOCK_KEY);
    return record != null && Boolean.TRUE.equals(record.get("locked", Boolean.class));
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

  /** Returns one deterministic page of scopes for background retention enforcement. */
  public List<HistoryScope> findDistinctScopesAfter(HistoryScope after, int batchSize) {
    if (batchSize <= 0) {
      return List.of();
    }
    org.jooq.Condition condition = PLAYER_COMMAND_HISTORY.ID.isNotNull();
    if (after != null) {
      condition =
          PLAYER_COMMAND_HISTORY
              .TENANT_ID
              .gt(after.tenantId())
              .or(
                  PLAYER_COMMAND_HISTORY
                      .TENANT_ID
                      .eq(after.tenantId())
                      .and(PLAYER_COMMAND_HISTORY.GAME_INSTANCE_ID.gt(after.gameInstanceId())))
              .or(
                  PLAYER_COMMAND_HISTORY
                      .TENANT_ID
                      .eq(after.tenantId())
                      .and(PLAYER_COMMAND_HISTORY.GAME_INSTANCE_ID.eq(after.gameInstanceId()))
                      .and(PLAYER_COMMAND_HISTORY.CHARACTER_ID.gt(after.characterId())));
    }
    return dsl.selectDistinct(
            PLAYER_COMMAND_HISTORY.TENANT_ID,
            PLAYER_COMMAND_HISTORY.GAME_INSTANCE_ID,
            PLAYER_COMMAND_HISTORY.CHARACTER_ID)
        .from(PLAYER_COMMAND_HISTORY)
        .where(condition)
        .orderBy(
            PLAYER_COMMAND_HISTORY.TENANT_ID.asc(),
            PLAYER_COMMAND_HISTORY.GAME_INSTANCE_ID.asc(),
            PLAYER_COMMAND_HISTORY.CHARACTER_ID.asc())
        .limit(batchSize)
        .fetch(
            record ->
                new HistoryScope(
                    record.get(PLAYER_COMMAND_HISTORY.TENANT_ID),
                    record.get(PLAYER_COMMAND_HISTORY.GAME_INSTANCE_ID),
                    record.get(PLAYER_COMMAND_HISTORY.CHARACTER_ID)));
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
    record.setAcceptedAt(toOffsetDateTime(entry.getAcceptedAt()));
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

  private long scopeLockKey(long tenantId, long gameInstanceId, long characterId) {
    long key = tenantId;
    key = 31L * key + gameInstanceId;
    return 31L * key + characterId;
  }

  public record HistoryScope(long tenantId, long gameInstanceId, long characterId) {}
}
