package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toOffsetDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.ResumeTranscriptEntry.RESUME_TRANSCRIPT_ENTRY;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.gamesession.entity.ResumeTranscriptEntry;
import net.firedevops.firemud.gamesession.jooq.tables.records.ResumeTranscriptEntryRecord;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.SQLDialect;
import org.jooq.Sequence;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Persists the source-of-truth bounded reconnect transcript. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ResumeTranscriptEntryRepository {
  private static final Sequence<Long> TRANSCRIPT_ID_SEQUENCE =
      DSL.sequence(DSL.name("resume_transcript_entry_id_seq"), Long.class);
  private final DSLContext dsl;

  public ResumeTranscriptEntryRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public void saveAll(Collection<ResumeTranscriptEntry> entries) {
    if (entries == null || entries.isEmpty()) {
      return;
    }
    List<ResumeTranscriptEntryRecord> records = new ArrayList<>(entries.size());
    for (ResumeTranscriptEntry entry : entries) {
      ResumeTranscriptEntryRecord record = dsl.newRecord(RESUME_TRANSCRIPT_ENTRY);
      populate(record, entry);
      records.add(record);
    }
    dsl.batchInsert(records).execute();
  }

  /** Assigns the sequence-backed token used for durable transcript ordering before batch insert. */
  public void assignOrderingTokens(Collection<ResumeTranscriptEntry> entries) {
    if (entries == null || entries.isEmpty()) {
      return;
    }
    List<ResumeTranscriptEntry> unassigned =
        entries.stream().filter(entry -> entry.getId() == null).toList();
    if (unassigned.isEmpty()) {
      return;
    }
    List<Long> ids = dsl.nextvals(TRANSCRIPT_ID_SEQUENCE, unassigned.size());
    for (int index = 0; index < unassigned.size(); index++) {
      unassigned.get(index).setId(ids.get(index));
    }
  }

  /** Serializes transcript mutations for one scope without retaining a lock-row permanently. */
  public void lockScope(long tenantId, long gameInstanceId, long characterId) {
    if (dsl.dialect().family() != SQLDialect.POSTGRES) {
      return;
    }
    dsl.execute(
        "select pg_advisory_xact_lock(?)", scopeLockKey(tenantId, gameInstanceId, characterId));
  }

  public List<ResumeTranscriptEntry> findByScope(
      long tenantId, long gameInstanceId, long characterId) {
    return dsl.selectFrom(RESUME_TRANSCRIPT_ENTRY)
        .where(
            RESUME_TRANSCRIPT_ENTRY
                .TENANT_ID
                .eq(tenantId)
                .and(RESUME_TRANSCRIPT_ENTRY.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(RESUME_TRANSCRIPT_ENTRY.CHARACTER_ID.eq(characterId)))
        .orderBy(RESUME_TRANSCRIPT_ENTRY.ID.asc())
        .fetch(this::toEntity);
  }

  /** Returns only entries whose immutable expiry has not passed at the supplied cutoff. */
  public List<ResumeTranscriptEntry> findActiveByScope(
      long tenantId, long gameInstanceId, long characterId, Instant cutoff) {
    return dsl.selectFrom(RESUME_TRANSCRIPT_ENTRY)
        .where(
            RESUME_TRANSCRIPT_ENTRY
                .TENANT_ID
                .eq(tenantId)
                .and(RESUME_TRANSCRIPT_ENTRY.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(RESUME_TRANSCRIPT_ENTRY.CHARACTER_ID.eq(characterId))
                .and(
                    RESUME_TRANSCRIPT_ENTRY
                        .EXPIRES_AT
                        .isNull()
                        .or(RESUME_TRANSCRIPT_ENTRY.EXPIRES_AT.gt(toOffsetDateTime(cutoff)))))
        .orderBy(RESUME_TRANSCRIPT_ENTRY.ID.asc())
        .fetch(this::toEntity);
  }

  public int deleteExpired(long tenantId, long gameInstanceId, long characterId, Instant cutoff) {
    return dsl.deleteFrom(RESUME_TRANSCRIPT_ENTRY)
        .where(
            RESUME_TRANSCRIPT_ENTRY
                .TENANT_ID
                .eq(tenantId)
                .and(RESUME_TRANSCRIPT_ENTRY.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(RESUME_TRANSCRIPT_ENTRY.CHARACTER_ID.eq(characterId))
                .and(RESUME_TRANSCRIPT_ENTRY.EXPIRES_AT.le(toOffsetDateTime(cutoff))))
        .execute();
  }

  /** Refreshes the one inactivity expiry shared by every retained entry in a transcript scope. */
  public int updateExpiryByScope(
      long tenantId, long gameInstanceId, long characterId, Instant expiresAt) {
    return dsl.update(RESUME_TRANSCRIPT_ENTRY)
        .set(RESUME_TRANSCRIPT_ENTRY.EXPIRES_AT, toOffsetDateTime(expiresAt))
        .where(
            RESUME_TRANSCRIPT_ENTRY
                .TENANT_ID
                .eq(tenantId)
                .and(RESUME_TRANSCRIPT_ENTRY.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(RESUME_TRANSCRIPT_ENTRY.CHARACTER_ID.eq(characterId)))
        .execute();
  }

  /** Deletes one bounded batch of globally expired transcripts, independent of later reconnects. */
  public int deleteExpiredBefore(Instant cutoff, int batchSize) {
    if (batchSize <= 0) {
      return 0;
    }
    return dsl.deleteFrom(RESUME_TRANSCRIPT_ENTRY)
        .where(
            RESUME_TRANSCRIPT_ENTRY.ID.in(
                dsl.select(RESUME_TRANSCRIPT_ENTRY.ID)
                    .from(RESUME_TRANSCRIPT_ENTRY)
                    .where(RESUME_TRANSCRIPT_ENTRY.EXPIRES_AT.le(toOffsetDateTime(cutoff)))
                    .orderBy(
                        RESUME_TRANSCRIPT_ENTRY.EXPIRES_AT.asc(), RESUME_TRANSCRIPT_ENTRY.ID.asc())
                    .limit(batchSize)))
        .execute();
  }

  public void deleteByIds(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    dsl.deleteFrom(RESUME_TRANSCRIPT_ENTRY).where(RESUME_TRANSCRIPT_ENTRY.ID.in(ids)).execute();
  }

  /** Persists canonical byte reaccounting for entries written before the current bound model. */
  public void updateByteSizes(Collection<ResumeTranscriptEntry> entries) {
    if (entries == null || entries.isEmpty()) {
      return;
    }
    List<Query> updates =
        entries.stream()
            .filter(entry -> entry.getId() != null)
            .<Query>map(
                entry ->
                    dsl.update(RESUME_TRANSCRIPT_ENTRY)
                        .set(RESUME_TRANSCRIPT_ENTRY.BYTE_SIZE, entry.getByteSize())
                        .where(RESUME_TRANSCRIPT_ENTRY.ID.eq(entry.getId())))
            .toList();
    if (!updates.isEmpty()) {
      dsl.batch(updates).execute();
    }
  }

  public void deleteByScope(long tenantId, long gameInstanceId, long characterId) {
    dsl.deleteFrom(RESUME_TRANSCRIPT_ENTRY)
        .where(
            RESUME_TRANSCRIPT_ENTRY
                .TENANT_ID
                .eq(tenantId)
                .and(RESUME_TRANSCRIPT_ENTRY.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(RESUME_TRANSCRIPT_ENTRY.CHARACTER_ID.eq(characterId)))
        .execute();
  }

  private void populate(ResumeTranscriptEntryRecord record, ResumeTranscriptEntry entry) {
    if (entry.getId() != null) {
      record.setId(entry.getId());
    }
    record.setTenantId(entry.getTenantId());
    record.setGameInstanceId(entry.getGameInstanceId());
    record.setCharacterId(entry.getCharacterId());
    record.setProtocolText(entry.getProtocolText());
    record.setLineCount(entry.getLineCount());
    record.setByteSize(entry.getByteSize());
    record.setAppendedAt(toOffsetDateTime(entry.getAppendedAt()));
    record.setExpiresAt(toOffsetDateTime(entry.getExpiresAt()));
    record.setOutputKind(entry.getOutputKind());
    record.setReplayPolicy(entry.getReplayPolicy());
    record.setBriefRenderPolicy(entry.getBriefRenderPolicy());
    record.setPayloadType(entry.getPayloadType());
    record.setPayloadJson(entry.getPayloadJson());
  }

  private ResumeTranscriptEntry toEntity(ResumeTranscriptEntryRecord record) {
    ResumeTranscriptEntry entry = new ResumeTranscriptEntry();
    entry.setId(record.getId());
    entry.setTenantId(record.getTenantId());
    entry.setGameInstanceId(record.getGameInstanceId());
    entry.setCharacterId(record.getCharacterId());
    entry.setProtocolText(record.getProtocolText());
    entry.setLineCount(record.getLineCount());
    entry.setByteSize(record.getByteSize());
    entry.setAppendedAt(toInstant(record.getAppendedAt()));
    entry.setExpiresAt(toInstant(record.getExpiresAt()));
    entry.setOutputKind(record.getOutputKind());
    entry.setReplayPolicy(record.getReplayPolicy());
    entry.setBriefRenderPolicy(record.getBriefRenderPolicy());
    entry.setPayloadType(record.getPayloadType());
    entry.setPayloadJson(record.getPayloadJson());
    return entry;
  }

  private long scopeLockKey(long tenantId, long gameInstanceId, long characterId) {
    long key = tenantId;
    key = 31L * key + gameInstanceId;
    return 31L * key + characterId;
  }
}
