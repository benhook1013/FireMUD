package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toOffsetDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.ResumeTranscriptEntry.RESUME_TRANSCRIPT_ENTRY;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.gamesession.entity.ResumeTranscriptEntry;
import net.firedevops.firemud.gamesession.jooq.tables.records.ResumeTranscriptEntryRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Persists the source-of-truth bounded reconnect transcript. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ResumeTranscriptEntryRepository {
  private final DSLContext dsl;

  public ResumeTranscriptEntryRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public void saveAll(Collection<ResumeTranscriptEntry> entries) {
    if (entries == null || entries.isEmpty()) {
      return;
    }
    for (ResumeTranscriptEntry entry : entries) {
      ResumeTranscriptEntryRecord record = dsl.newRecord(RESUME_TRANSCRIPT_ENTRY);
      populate(record, entry);
      record.store();
      entry.setId(record.getId());
    }
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
        .orderBy(RESUME_TRANSCRIPT_ENTRY.APPENDED_AT.asc(), RESUME_TRANSCRIPT_ENTRY.ID.asc())
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
}
