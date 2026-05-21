package net.firedevops.firemud.worldmanagement.repository;

import static net.firedevops.firemud.worldmanagement.jooq.tables.RegionInstance.REGION_INSTANCE;
import static net.firedevops.firemud.worldmanagement.jooq.tables.WorldEvent.WORLD_EVENT;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.WorldEvent;
import net.firedevops.firemud.worldmanagement.jooq.tables.records.WorldEventRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class WorldEventRepository {
  private final DSLContext dsl;

  public WorldEventRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<WorldEvent> findByProcessedFalseAndExecuteAtBefore(LocalDateTime time) {
    return dsl.selectFrom(WORLD_EVENT)
        .where(WORLD_EVENT.PROCESSED.isFalse().and(WORLD_EVENT.EXECUTE_AT.le(time)))
        .fetch(this::toEntity);
  }

  public long countByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId) {
    return dsl.fetchCount(
        WORLD_EVENT,
        WORLD_EVENT.TENANT_ID.eq(tenantId).and(WORLD_EVENT.GAME_INSTANCE_ID.eq(gameInstanceId)));
  }

  public void deleteByTenantIdAndGameInstanceId(Long tenantId, Long gameInstanceId) {
    dsl.deleteFrom(WORLD_EVENT)
        .where(
            WORLD_EVENT.TENANT_ID.eq(tenantId).and(WORLD_EVENT.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .execute();
  }

  public List<WorldEvent> findDueEventsForShard(LocalDateTime time, Integer shardId) {
    return dsl.select(WORLD_EVENT.fields())
        .select(REGION_INSTANCE.SHARD_ID)
        .from(WORLD_EVENT)
        .leftJoin(REGION_INSTANCE)
        .on(WORLD_EVENT.REGION_INSTANCE_ID.eq(REGION_INSTANCE.ID))
        .where(
            WORLD_EVENT
                .PROCESSED
                .isFalse()
                .and(WORLD_EVENT.EXECUTE_AT.le(time))
                .and(
                    WORLD_EVENT
                        .REGION_INSTANCE_ID
                        .isNull()
                        .or(REGION_INSTANCE.SHARD_ID.eq(shardId))))
        .fetch(this::toEntity);
  }

  public Optional<WorldEvent> findById(Long id) {
    return dsl.selectFrom(WORLD_EVENT).where(WORLD_EVENT.ID.eq(id)).fetchOptional(this::toEntity);
  }

  public WorldEvent save(WorldEvent entity) {
    if (entity.getId() == null) {
      WorldEventRecord record = dsl.newRecord(WORLD_EVENT);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(WORLD_EVENT)
            .set(WORLD_EVENT.TENANT_ID, entity.getTenantId())
            .set(WORLD_EVENT.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(
                WORLD_EVENT.REGION_INSTANCE_ID,
                entity.getRegionInstance() == null ? null : entity.getRegionInstance().getId())
            .set(WORLD_EVENT.EVENT_TYPE, entity.getEventType())
            .set(WORLD_EVENT.EVENT_DATA, entity.getEventData())
            .set(WORLD_EVENT.EXECUTE_AT, entity.getExecuteAt())
            .set(WORLD_EVENT.PROCESSED, entity.isProcessed())
            .set(WORLD_EVENT.PROCESSED_AT, entity.getProcessedAt())
            .set(WORLD_EVENT.VERSION, entity.getVersion() + 1)
            .where(
                WORLD_EVENT.ID.eq(entity.getId()).and(WORLD_EVENT.VERSION.eq(entity.getVersion())))
            .execute();
    if (updated != 1) {
      throw JooqWorldManagementRepositorySupport.staleWrite("world_event", entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private void populate(WorldEventRecord record, WorldEvent entity) {
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setRegionInstanceId(
        entity.getRegionInstance() == null ? null : entity.getRegionInstance().getId());
    record.setEventType(entity.getEventType());
    record.setEventData(entity.getEventData());
    record.setExecuteAt(entity.getExecuteAt());
    record.setProcessed(entity.isProcessed());
    record.setProcessedAt(entity.getProcessedAt());
    record.setVersion(entity.getVersion());
  }

  private WorldEvent toEntity(Record record) {
    WorldEvent entity = new WorldEvent();
    entity.setId(record.get(WORLD_EVENT.ID));
    entity.setTenantId(record.get(WORLD_EVENT.TENANT_ID));
    entity.setGameInstanceId(record.get(WORLD_EVENT.GAME_INSTANCE_ID));
    entity.setRegionInstance(
        JooqWorldManagementRepositorySupport.partialRegionInstance(
            record.get(WORLD_EVENT.REGION_INSTANCE_ID)));
    entity.setEventType(record.get(WORLD_EVENT.EVENT_TYPE));
    entity.setEventData(record.get(WORLD_EVENT.EVENT_DATA));
    entity.setExecuteAt(record.get(WORLD_EVENT.EXECUTE_AT));
    entity.setProcessed(Boolean.TRUE.equals(record.get(WORLD_EVENT.PROCESSED)));
    entity.setProcessedAt(record.get(WORLD_EVENT.PROCESSED_AT));
    Integer version = record.get(WORLD_EVENT.VERSION);
    entity.setVersion(version == null ? 0 : version);
    return entity;
  }
}
