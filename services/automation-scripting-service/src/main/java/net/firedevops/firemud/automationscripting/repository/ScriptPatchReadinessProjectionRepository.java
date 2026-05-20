package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptPatchReadinessProjections.SCRIPT_PATCH_READINESS_PROJECTIONS;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toOffsetDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchReadinessProjection;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptPatchReadinessProjectionsRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ScriptPatchReadinessProjectionRepository {
  private final DSLContext dsl;

  public ScriptPatchReadinessProjectionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<ScriptPatchReadinessProjection> findByTenantIdAndScriptPatchVersion(
      String tenantId, String scriptPatchVersion) {
    return dsl.selectFrom(SCRIPT_PATCH_READINESS_PROJECTIONS)
        .where(
            SCRIPT_PATCH_READINESS_PROJECTIONS
                .TENANT_ID
                .eq(tenantId)
                .and(
                    SCRIPT_PATCH_READINESS_PROJECTIONS.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion)))
        .fetchOptional(this::toEntity);
  }

  public List<ScriptPatchReadinessProjection> findByTenantIdOrderByLastChangedAtDesc(
      String tenantId) {
    return dsl.selectFrom(SCRIPT_PATCH_READINESS_PROJECTIONS)
        .where(SCRIPT_PATCH_READINESS_PROJECTIONS.TENANT_ID.eq(tenantId))
        .orderBy(SCRIPT_PATCH_READINESS_PROJECTIONS.LAST_CHANGED_AT.desc())
        .fetch(this::toEntity);
  }

  public List<ScriptPatchReadinessProjection>
      findByTenantIdAndReadinessStatusInOrderByLastChangedAtAsc(
          String tenantId, Collection<String> statuses) {
    return dsl.selectFrom(SCRIPT_PATCH_READINESS_PROJECTIONS)
        .where(
            SCRIPT_PATCH_READINESS_PROJECTIONS
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_PATCH_READINESS_PROJECTIONS.READINESS_STATUS.in(statuses)))
        .orderBy(SCRIPT_PATCH_READINESS_PROJECTIONS.LAST_CHANGED_AT.asc())
        .fetch(this::toEntity);
  }

  public ScriptPatchReadinessProjection save(ScriptPatchReadinessProjection entity) {
    if (entity.getId() == null) {
      ScriptPatchReadinessProjectionsRecord record =
          dsl.newRecord(SCRIPT_PATCH_READINESS_PROJECTIONS);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int nextRowVersion = entity.getRowVersion() + 1;
    int updated =
        dsl.update(SCRIPT_PATCH_READINESS_PROJECTIONS)
            .set(SCRIPT_PATCH_READINESS_PROJECTIONS.TENANT_ID, entity.getTenantId())
            .set(
                SCRIPT_PATCH_READINESS_PROJECTIONS.SCRIPT_PATCH_VERSION,
                entity.getScriptPatchVersion())
            .set(SCRIPT_PATCH_READINESS_PROJECTIONS.READINESS_STATUS, entity.getReadinessStatus())
            .set(SCRIPT_PATCH_READINESS_PROJECTIONS.STATUS_REASON, entity.getStatusReason())
            .set(
                SCRIPT_PATCH_READINESS_PROJECTIONS.SUPERSEDED_BY_SCRIPT_PATCH_VERSION,
                entity.getSupersededByScriptPatchVersion())
            .set(
                SCRIPT_PATCH_READINESS_PROJECTIONS.LAST_CHANGED_AT,
                toOffsetDateTime(entity.getLastChangedAt()))
            .set(SCRIPT_PATCH_READINESS_PROJECTIONS.ROW_VERSION, nextRowVersion)
            .where(
                SCRIPT_PATCH_READINESS_PROJECTIONS
                    .ID
                    .eq(entity.getId())
                    .and(SCRIPT_PATCH_READINESS_PROJECTIONS.ROW_VERSION.eq(entity.getRowVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite(
          "script_patch_readiness_projections", entity.getId());
    }
    entity.setRowVersion(nextRowVersion);
    return findById(entity.getId()).orElseThrow();
  }

  public List<ScriptPatchReadinessProjection> saveAll(
      Collection<ScriptPatchReadinessProjection> entities) {
    if (entities == null || entities.isEmpty()) {
      return List.of();
    }
    return entities.stream().map(this::save).toList();
  }

  private Optional<ScriptPatchReadinessProjection> findById(Long id) {
    return dsl.selectFrom(SCRIPT_PATCH_READINESS_PROJECTIONS)
        .where(SCRIPT_PATCH_READINESS_PROJECTIONS.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(
      ScriptPatchReadinessProjectionsRecord record, ScriptPatchReadinessProjection entity) {
    record.setTenantId(entity.getTenantId());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setReadinessStatus(entity.getReadinessStatus());
    record.setStatusReason(entity.getStatusReason());
    record.setSupersededByScriptPatchVersion(entity.getSupersededByScriptPatchVersion());
    record.setLastChangedAt(toOffsetDateTime(entity.getLastChangedAt()));
    record.setRowVersion(entity.getRowVersion());
  }

  private ScriptPatchReadinessProjection toEntity(Record record) {
    ScriptPatchReadinessProjection entity = new ScriptPatchReadinessProjection();
    entity.setId(record.get(SCRIPT_PATCH_READINESS_PROJECTIONS.ID));
    entity.setTenantId(record.get(SCRIPT_PATCH_READINESS_PROJECTIONS.TENANT_ID));
    entity.setScriptPatchVersion(
        record.get(SCRIPT_PATCH_READINESS_PROJECTIONS.SCRIPT_PATCH_VERSION));
    entity.setReadinessStatus(record.get(SCRIPT_PATCH_READINESS_PROJECTIONS.READINESS_STATUS));
    entity.setStatusReason(record.get(SCRIPT_PATCH_READINESS_PROJECTIONS.STATUS_REASON));
    entity.setSupersededByScriptPatchVersion(
        record.get(SCRIPT_PATCH_READINESS_PROJECTIONS.SUPERSEDED_BY_SCRIPT_PATCH_VERSION));
    entity.setLastChangedAt(
        toInstant(record.get(SCRIPT_PATCH_READINESS_PROJECTIONS.LAST_CHANGED_AT)));
    Integer rowVersion = record.get(SCRIPT_PATCH_READINESS_PROJECTIONS.ROW_VERSION);
    entity.setRowVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
