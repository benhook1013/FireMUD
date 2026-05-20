package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.TickBatch.TICK_BATCH;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.TickBatch;
import net.firedevops.firemud.gamesession.jooq.tables.records.TickBatchRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class TickBatchRepository {
  private final DSLContext dsl;

  public TickBatchRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<TickBatch> findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
      Long tenantId, Long gameInstanceId, String status) {
    return dsl.selectFrom(TICK_BATCH)
        .where(
            TICK_BATCH
                .TENANT_ID
                .eq(tenantId)
                .and(TICK_BATCH.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(TICK_BATCH.STATUS.eq(status)))
        .orderBy(TICK_BATCH.STAGED_AT.desc(), TICK_BATCH.ID.desc())
        .limit(1)
        .fetchOptional(this::toEntity);
  }

  public List<TickBatch> findByTenantIdAndGameInstanceIdAndStatusOrderByCompletedAtAsc(
      Long tenantId, Long gameInstanceId, String status) {
    return dsl.selectFrom(TICK_BATCH)
        .where(
            TICK_BATCH
                .TENANT_ID
                .eq(tenantId)
                .and(TICK_BATCH.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(TICK_BATCH.STATUS.eq(status)))
        .orderBy(TICK_BATCH.COMPLETED_AT.asc().nullsFirst(), TICK_BATCH.ID.asc())
        .fetch(this::toEntity);
  }

  public TickBatch save(TickBatch entity) {
    if (entity.getId() == null) {
      TickBatchRecord record = dsl.newRecord(TICK_BATCH);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(TICK_BATCH)
            .set(TICK_BATCH.TICK_BATCH_ID, entity.getTickBatchId())
            .set(TICK_BATCH.TENANT_ID, entity.getTenantId())
            .set(TICK_BATCH.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(TICK_BATCH.REGION_ID, entity.getRegionId())
            .set(TICK_BATCH.REGION_EPOCH, entity.getRegionEpoch())
            .set(TICK_BATCH.EXECUTOR_FENCE, entity.getExecutorFence())
            .set(TICK_BATCH.BATCH_SOURCE, entity.getBatchSource())
            .set(TICK_BATCH.STATUS, entity.getStatus())
            .set(TICK_BATCH.REQUIRES_SOLO_TICK, entity.isRequiresSoloTick())
            .set(TICK_BATCH.COMMAND_COUNT, entity.getCommandCount())
            .set(TICK_BATCH.EXPECTED_EFFECT_COUNT, entity.getExpectedEffectCount())
            .set(TICK_BATCH.SELECTED_WORK_MANIFEST_DIGEST, entity.getSelectedWorkManifestDigest())
            .set(TICK_BATCH.SELECTED_WORK_MANIFEST_JSON, entity.getSelectedWorkManifestJson())
            .set(TICK_BATCH.STAGED_AT, toLocalDateTime(entity.getStagedAt()))
            .set(TICK_BATCH.COMPLETED_AT, toLocalDateTime(entity.getCompletedAt()))
            .set(TICK_BATCH.FAILURE_CODE, entity.getFailureCode())
            .set(TICK_BATCH.FAILURE_MESSAGE, entity.getFailureMessage())
            .where(TICK_BATCH.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("Failed to update tick_batch id=" + entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<TickBatch> findById(Long id) {
    return dsl.selectFrom(TICK_BATCH).where(TICK_BATCH.ID.eq(id)).fetchOptional(this::toEntity);
  }

  private void populate(TickBatchRecord record, TickBatch entity) {
    record.setTickBatchId(entity.getTickBatchId());
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setRegionId(entity.getRegionId());
    record.setRegionEpoch(entity.getRegionEpoch());
    record.setExecutorFence(entity.getExecutorFence());
    record.setBatchSource(entity.getBatchSource());
    record.setStatus(entity.getStatus());
    record.setRequiresSoloTick(entity.isRequiresSoloTick());
    record.setCommandCount(entity.getCommandCount());
    record.setExpectedEffectCount(entity.getExpectedEffectCount());
    record.setSelectedWorkManifestDigest(entity.getSelectedWorkManifestDigest());
    record.setSelectedWorkManifestJson(entity.getSelectedWorkManifestJson());
    record.setStagedAt(toLocalDateTime(entity.getStagedAt()));
    record.setCompletedAt(toLocalDateTime(entity.getCompletedAt()));
    record.setFailureCode(entity.getFailureCode());
    record.setFailureMessage(entity.getFailureMessage());
  }

  private TickBatch toEntity(Record record) {
    TickBatch entity = new TickBatch();
    entity.setId(record.get(TICK_BATCH.ID));
    entity.setTickBatchId(record.get(TICK_BATCH.TICK_BATCH_ID));
    entity.setTenantId(record.get(TICK_BATCH.TENANT_ID));
    entity.setGameInstanceId(record.get(TICK_BATCH.GAME_INSTANCE_ID));
    entity.setRegionId(record.get(TICK_BATCH.REGION_ID));
    entity.setRegionEpoch(record.get(TICK_BATCH.REGION_EPOCH));
    entity.setExecutorFence(record.get(TICK_BATCH.EXECUTOR_FENCE));
    entity.setBatchSource(record.get(TICK_BATCH.BATCH_SOURCE));
    entity.setStatus(record.get(TICK_BATCH.STATUS));
    entity.setRequiresSoloTick(Boolean.TRUE.equals(record.get(TICK_BATCH.REQUIRES_SOLO_TICK)));
    entity.setCommandCount(record.get(TICK_BATCH.COMMAND_COUNT));
    entity.setExpectedEffectCount(record.get(TICK_BATCH.EXPECTED_EFFECT_COUNT));
    entity.setSelectedWorkManifestDigest(record.get(TICK_BATCH.SELECTED_WORK_MANIFEST_DIGEST));
    entity.setSelectedWorkManifestJson(record.get(TICK_BATCH.SELECTED_WORK_MANIFEST_JSON));
    entity.setStagedAt(toInstant(record.get(TICK_BATCH.STAGED_AT)));
    entity.setCompletedAt(toInstant(record.get(TICK_BATCH.COMPLETED_AT)));
    entity.setFailureCode(record.get(TICK_BATCH.FAILURE_CODE));
    entity.setFailureMessage(record.get(TICK_BATCH.FAILURE_MESSAGE));
    return entity;
  }
}
