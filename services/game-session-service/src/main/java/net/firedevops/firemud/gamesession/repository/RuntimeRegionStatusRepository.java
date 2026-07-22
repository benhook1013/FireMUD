package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.RuntimeRegionStatus.RUNTIME_REGION_STATUS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.jooq.tables.records.RuntimeRegionStatusRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class RuntimeRegionStatusRepository {
  private final DSLContext dsl;

  public RuntimeRegionStatusRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<RuntimeRegionStatus> findByTenantIdAndGameInstanceId(
      Long tenantId, Long gameInstanceId) {
    return dsl.selectFrom(RUNTIME_REGION_STATUS)
        .where(
            RUNTIME_REGION_STATUS
                .TENANT_ID
                .eq(tenantId)
                .and(RUNTIME_REGION_STATUS.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .fetchOptional(this::toEntity);
  }

  public Optional<RuntimeRegionStatus> findByTenantIdAndRegionId(Long tenantId, String regionId) {
    return dsl.selectFrom(RUNTIME_REGION_STATUS)
        .where(
            RUNTIME_REGION_STATUS
                .TENANT_ID
                .eq(tenantId)
                .and(RUNTIME_REGION_STATUS.REGION_ID.eq(regionId)))
        .fetchOptional(this::toEntity);
  }

  public RuntimeRegionStatus save(RuntimeRegionStatus entity) {
    if (entity.getId() == null) {
      RuntimeRegionStatusRecord record = dsl.newRecord(RUNTIME_REGION_STATUS);
      populate(record, entity);
      Optional<RuntimeRegionStatus> inserted =
          dsl.insertInto(RUNTIME_REGION_STATUS)
              .set(record)
              .onConflict(RUNTIME_REGION_STATUS.TENANT_ID, RUNTIME_REGION_STATUS.GAME_INSTANCE_ID)
              .doNothing()
              .returning()
              .fetchOptional(this::toEntity);
      return inserted.orElseGet(
          () ->
              findByTenantIdAndGameInstanceId(entity.getTenantId(), entity.getGameInstanceId())
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              ("Natural-key conflict did not yield runtime_region_status for "
                                      + "tenantId=%d gameInstanceId=%d")
                                  .formatted(entity.getTenantId(), entity.getGameInstanceId()))));
    }
    int updated =
        dsl.update(RUNTIME_REGION_STATUS)
            .set(RUNTIME_REGION_STATUS.TENANT_ID, entity.getTenantId())
            .set(RUNTIME_REGION_STATUS.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(RUNTIME_REGION_STATUS.REGION_ID, entity.getRegionId())
            .set(RUNTIME_REGION_STATUS.REGION_EPOCH, entity.getRegionEpoch())
            .set(RUNTIME_REGION_STATUS.EXECUTOR_FENCE, entity.getExecutorFence())
            .set(RUNTIME_REGION_STATUS.OWNER_SERVICE, entity.getOwnerService())
            .set(RUNTIME_REGION_STATUS.OWNER_INSTANCE_ID, entity.getOwnerInstanceId())
            .set(RUNTIME_REGION_STATUS.PAUSED, entity.isPaused())
            .set(
                RUNTIME_REGION_STATUS.LAST_COMMITTED_TICK_BATCH_ID,
                entity.getLastCommittedTickBatchId())
            .set(RUNTIME_REGION_STATUS.LAST_COMMITTED_TICK_ID, entity.getLastCommittedTickId())
            .set(RUNTIME_REGION_STATUS.UPDATED_AT, toLocalDateTime(entity.getUpdatedAt()))
            .where(RUNTIME_REGION_STATUS.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException(
          "Failed to update runtime_region_status id=" + entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<RuntimeRegionStatus> findById(Long id) {
    return dsl.selectFrom(RUNTIME_REGION_STATUS)
        .where(RUNTIME_REGION_STATUS.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(RuntimeRegionStatusRecord record, RuntimeRegionStatus entity) {
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setRegionId(entity.getRegionId());
    record.setRegionEpoch(entity.getRegionEpoch());
    record.setExecutorFence(entity.getExecutorFence());
    record.setOwnerService(entity.getOwnerService());
    record.setOwnerInstanceId(entity.getOwnerInstanceId());
    record.setPaused(entity.isPaused());
    record.setLastCommittedTickBatchId(entity.getLastCommittedTickBatchId());
    record.setLastCommittedTickId(entity.getLastCommittedTickId());
    record.setUpdatedAt(toLocalDateTime(entity.getUpdatedAt()));
  }

  private RuntimeRegionStatus toEntity(Record record) {
    RuntimeRegionStatus entity = new RuntimeRegionStatus();
    entity.setId(record.get(RUNTIME_REGION_STATUS.ID));
    entity.setTenantId(record.get(RUNTIME_REGION_STATUS.TENANT_ID));
    entity.setGameInstanceId(record.get(RUNTIME_REGION_STATUS.GAME_INSTANCE_ID));
    entity.setRegionId(record.get(RUNTIME_REGION_STATUS.REGION_ID));
    entity.setRegionEpoch(record.get(RUNTIME_REGION_STATUS.REGION_EPOCH));
    entity.setExecutorFence(record.get(RUNTIME_REGION_STATUS.EXECUTOR_FENCE));
    entity.setOwnerService(record.get(RUNTIME_REGION_STATUS.OWNER_SERVICE));
    entity.setOwnerInstanceId(record.get(RUNTIME_REGION_STATUS.OWNER_INSTANCE_ID));
    entity.setPaused(Boolean.TRUE.equals(record.get(RUNTIME_REGION_STATUS.PAUSED)));
    entity.setLastCommittedTickBatchId(
        record.get(RUNTIME_REGION_STATUS.LAST_COMMITTED_TICK_BATCH_ID));
    entity.setLastCommittedTickId(record.get(RUNTIME_REGION_STATUS.LAST_COMMITTED_TICK_ID));
    entity.setUpdatedAt(toInstant(record.get(RUNTIME_REGION_STATUS.UPDATED_AT)));
    return entity;
  }
}
