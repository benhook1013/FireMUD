package net.firedevops.firemud.worldmanagement.repository;

import static net.firedevops.firemud.worldmanagement.jooq.tables.WorldDesignScopeEpoch.WORLD_DESIGN_SCOPE_EPOCH;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.WorldDesignScopeEpoch;
import net.firedevops.firemud.worldmanagement.jooq.tables.records.WorldDesignScopeEpochRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class WorldDesignScopeEpochRepository {
  private final DSLContext dsl;

  public WorldDesignScopeEpochRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<WorldDesignScopeEpoch> findByTenantIdAndVersionIdAndScopeTypeAndScopeId(
      Long tenantId, Long versionId, String scopeType, String scopeId) {
    return dsl.selectFrom(WORLD_DESIGN_SCOPE_EPOCH)
        .where(
            WORLD_DESIGN_SCOPE_EPOCH
                .TENANT_ID
                .eq(tenantId)
                .and(WORLD_DESIGN_SCOPE_EPOCH.VERSION_ID.eq(versionId))
                .and(WORLD_DESIGN_SCOPE_EPOCH.SCOPE_TYPE.eq(scopeType))
                .and(WORLD_DESIGN_SCOPE_EPOCH.SCOPE_ID.eq(scopeId)))
        .fetchOptional(this::toEntity);
  }

  public Optional<WorldDesignScopeEpoch> findById(Long id) {
    return dsl.selectFrom(WORLD_DESIGN_SCOPE_EPOCH)
        .where(WORLD_DESIGN_SCOPE_EPOCH.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  public WorldDesignScopeEpoch save(WorldDesignScopeEpoch entity) {
    if (entity.getId() == null) {
      WorldDesignScopeEpochRecord record = dsl.newRecord(WORLD_DESIGN_SCOPE_EPOCH);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(WORLD_DESIGN_SCOPE_EPOCH)
            .set(WORLD_DESIGN_SCOPE_EPOCH.TENANT_ID, entity.getTenantId())
            .set(WORLD_DESIGN_SCOPE_EPOCH.VERSION_ID, entity.getVersionId())
            .set(WORLD_DESIGN_SCOPE_EPOCH.SCOPE_TYPE, entity.getScopeType())
            .set(WORLD_DESIGN_SCOPE_EPOCH.SCOPE_ID, entity.getScopeId())
            .set(
                WORLD_DESIGN_SCOPE_EPOCH.DRAFT_SCOPE_REVISION_EPOCH,
                entity.getDraftScopeRevisionEpoch())
            .set(WORLD_DESIGN_SCOPE_EPOCH.UPDATED_AT, entity.getUpdatedAt())
            .where(WORLD_DESIGN_SCOPE_EPOCH.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw JooqWorldManagementRepositorySupport.staleWrite(
          "world_design_scope_epoch", entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private void populate(WorldDesignScopeEpochRecord record, WorldDesignScopeEpoch entity) {
    record.setTenantId(entity.getTenantId());
    record.setVersionId(entity.getVersionId());
    record.setScopeType(entity.getScopeType());
    record.setScopeId(entity.getScopeId());
    record.setDraftScopeRevisionEpoch(entity.getDraftScopeRevisionEpoch());
    record.setUpdatedAt(
        entity.getUpdatedAt() == null ? LocalDateTime.now() : entity.getUpdatedAt());
  }

  private WorldDesignScopeEpoch toEntity(Record record) {
    WorldDesignScopeEpoch entity = new WorldDesignScopeEpoch();
    entity.setId(record.get(WORLD_DESIGN_SCOPE_EPOCH.ID));
    entity.setTenantId(record.get(WORLD_DESIGN_SCOPE_EPOCH.TENANT_ID));
    entity.setVersionId(record.get(WORLD_DESIGN_SCOPE_EPOCH.VERSION_ID));
    entity.setScopeType(record.get(WORLD_DESIGN_SCOPE_EPOCH.SCOPE_TYPE));
    entity.setScopeId(record.get(WORLD_DESIGN_SCOPE_EPOCH.SCOPE_ID));
    entity.setDraftScopeRevisionEpoch(
        record.get(WORLD_DESIGN_SCOPE_EPOCH.DRAFT_SCOPE_REVISION_EPOCH));
    entity.setUpdatedAt(record.get(WORLD_DESIGN_SCOPE_EPOCH.UPDATED_AT));
    return entity;
  }
}
