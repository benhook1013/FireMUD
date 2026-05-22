package net.firedevops.firemud.worldmanagement.repository;

import static net.firedevops.firemud.worldmanagement.jooq.tables.WorldDesignAggregateEpoch.WORLD_DESIGN_AGGREGATE_EPOCH;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.WorldDesignAggregateEpoch;
import net.firedevops.firemud.worldmanagement.jooq.tables.records.WorldDesignAggregateEpochRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class WorldDesignAggregateEpochRepository {
  private final DSLContext dsl;

  public WorldDesignAggregateEpochRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<WorldDesignAggregateEpoch>
      findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(
          Long tenantId, Long versionId, String aggregateType, Long aggregateId) {
    return dsl.selectFrom(WORLD_DESIGN_AGGREGATE_EPOCH)
        .where(
            WORLD_DESIGN_AGGREGATE_EPOCH
                .TENANT_ID
                .eq(tenantId)
                .and(WORLD_DESIGN_AGGREGATE_EPOCH.VERSION_ID.eq(versionId))
                .and(WORLD_DESIGN_AGGREGATE_EPOCH.AGGREGATE_TYPE.eq(aggregateType))
                .and(WORLD_DESIGN_AGGREGATE_EPOCH.AGGREGATE_ID.eq(aggregateId)))
        .fetchOptional(this::toEntity);
  }

  public Optional<WorldDesignAggregateEpoch> findById(Long id) {
    return dsl.selectFrom(WORLD_DESIGN_AGGREGATE_EPOCH)
        .where(WORLD_DESIGN_AGGREGATE_EPOCH.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  public WorldDesignAggregateEpoch save(WorldDesignAggregateEpoch entity) {
    if (entity.getId() == null) {
      WorldDesignAggregateEpochRecord record = dsl.newRecord(WORLD_DESIGN_AGGREGATE_EPOCH);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(WORLD_DESIGN_AGGREGATE_EPOCH)
            .set(WORLD_DESIGN_AGGREGATE_EPOCH.TENANT_ID, entity.getTenantId())
            .set(WORLD_DESIGN_AGGREGATE_EPOCH.VERSION_ID, entity.getVersionId())
            .set(WORLD_DESIGN_AGGREGATE_EPOCH.AGGREGATE_TYPE, entity.getAggregateType())
            .set(WORLD_DESIGN_AGGREGATE_EPOCH.AGGREGATE_ID, entity.getAggregateId())
            .set(WORLD_DESIGN_AGGREGATE_EPOCH.DRAFT_REVISION_EPOCH, entity.getDraftRevisionEpoch())
            .set(WORLD_DESIGN_AGGREGATE_EPOCH.UPDATED_AT, entity.getUpdatedAt())
            .where(WORLD_DESIGN_AGGREGATE_EPOCH.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqWorldManagementRepositorySupport.staleWrite(
          "world_design_aggregate_epoch", entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private void populate(WorldDesignAggregateEpochRecord record, WorldDesignAggregateEpoch entity) {
    record.setTenantId(entity.getTenantId());
    record.setVersionId(entity.getVersionId());
    record.setAggregateType(entity.getAggregateType());
    record.setAggregateId(entity.getAggregateId());
    record.setDraftRevisionEpoch(entity.getDraftRevisionEpoch());
    record.setUpdatedAt(
        entity.getUpdatedAt() == null ? LocalDateTime.now() : entity.getUpdatedAt());
  }

  private WorldDesignAggregateEpoch toEntity(Record record) {
    WorldDesignAggregateEpoch entity = new WorldDesignAggregateEpoch();
    entity.setId(record.get(WORLD_DESIGN_AGGREGATE_EPOCH.ID));
    entity.setTenantId(record.get(WORLD_DESIGN_AGGREGATE_EPOCH.TENANT_ID));
    entity.setVersionId(record.get(WORLD_DESIGN_AGGREGATE_EPOCH.VERSION_ID));
    entity.setAggregateType(record.get(WORLD_DESIGN_AGGREGATE_EPOCH.AGGREGATE_TYPE));
    entity.setAggregateId(record.get(WORLD_DESIGN_AGGREGATE_EPOCH.AGGREGATE_ID));
    entity.setDraftRevisionEpoch(record.get(WORLD_DESIGN_AGGREGATE_EPOCH.DRAFT_REVISION_EPOCH));
    entity.setUpdatedAt(record.get(WORLD_DESIGN_AGGREGATE_EPOCH.UPDATED_AT));
    return entity;
  }
}
