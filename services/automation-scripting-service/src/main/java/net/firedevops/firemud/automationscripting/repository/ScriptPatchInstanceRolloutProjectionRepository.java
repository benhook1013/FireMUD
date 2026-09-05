package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptPatchInstanceRolloutProjections.SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.blankToNull;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchInstanceRolloutProjection;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptPatchInstanceRolloutProjectionsRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ScriptPatchInstanceRolloutProjectionRepository {
  private final DSLContext dsl;

  public ScriptPatchInstanceRolloutProjectionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<ScriptPatchInstanceRolloutProjection>
      findByTenantIdAndGameInstanceIdAndScriptPatchVersion(
          String tenantId, String gameInstanceId, String scriptPatchVersion) {
    return dsl.selectFrom(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS)
        .where(
            SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(
                    SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.SCRIPT_PATCH_VERSION.eq(
                        scriptPatchVersion)))
        .fetchOptional(this::toEntity);
  }

  public Optional<ScriptPatchInstanceRolloutProjection>
      findByTenantIdAndGameInstanceIdAndScriptPatchVersionAndScriptPinEpoch(
          String tenantId, String gameInstanceId, String scriptPatchVersion, long scriptPinEpoch) {
    return dsl.selectFrom(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS)
        .where(
            SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(
                    SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.SCRIPT_PATCH_VERSION.eq(
                        scriptPatchVersion))
                .and(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.SCRIPT_PIN_EPOCH.eq(scriptPinEpoch)))
        .fetchOptional(this::toEntity);
  }

  public List<ScriptPatchInstanceRolloutProjection>
      findByTenantIdOrderByLastChangedAtDescGameInstanceIdAscScriptPatchVersionAsc(
          String tenantId) {
    return dsl.selectFrom(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS)
        .where(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.TENANT_ID.eq(tenantId))
        .orderBy(
            SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.LAST_CHANGED_AT.desc(),
            SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.GAME_INSTANCE_ID.asc(),
            SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.SCRIPT_PATCH_VERSION.asc())
        .fetch(this::toEntity);
  }

  public List<ScriptPatchInstanceRolloutProjection>
      findByTenantIdAndGameInstanceIdOrderByLastChangedAtDescScriptPatchVersionAsc(
          String tenantId, String gameInstanceId) {
    return dsl.selectFrom(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS)
        .where(
            SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .orderBy(
            SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.LAST_CHANGED_AT.desc(),
            SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.SCRIPT_PATCH_VERSION.asc())
        .fetch(this::toEntity);
  }

  public List<ScriptPatchInstanceRolloutProjection> findByTenantIdAndGameInstanceId(
      String tenantId, String gameInstanceId) {
    return dsl.selectFrom(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS)
        .where(
            SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .fetch(this::toEntity);
  }

  public ScriptPatchInstanceRolloutProjection save(ScriptPatchInstanceRolloutProjection entity) {
    if (entity.getId() == null) {
      ScriptPatchInstanceRolloutProjectionsRecord record =
          dsl.newRecord(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int nextRowVersion = entity.getRowVersion() + 1;
    int updated =
        dsl.update(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS)
            .set(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.TENANT_ID, entity.getTenantId())
            .set(
                SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.GAME_INSTANCE_ID,
                entity.getGameInstanceId())
            .set(
                SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.SCRIPT_PATCH_VERSION,
                entity.getScriptPatchVersion())
            .set(
                SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.SCRIPT_PIN_EPOCH,
                entity.getScriptPinEpoch())
            .set(
                SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.LAST_OBSERVED_CONTROL_PLANE_REQUEST_ID,
                blankToNull(entity.getLastObservedControlPlaneRequestId()))
            .set(
                SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.ROLLOUT_STATUS, entity.getRolloutStatus())
            .set(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.STATUS_REASON, entity.getStatusReason())
            .set(
                SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.LAST_CHANGED_AT,
                toLocalDateTime(entity.getLastChangedAt()))
            .set(
                SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.PROJECTION_REFRESHED_AT,
                toLocalDateTime(entity.getProjectionRefreshedAt()))
            .set(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.ROW_VERSION, nextRowVersion)
            .where(
                SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS
                    .ID
                    .eq(entity.getId())
                    .and(
                        SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.ROW_VERSION.eq(
                            entity.getRowVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite(
          "script_patch_instance_rollout_projections", entity.getId());
    }
    entity.setRowVersion(nextRowVersion);
    return findById(entity.getId()).orElseThrow();
  }

  public void delete(ScriptPatchInstanceRolloutProjection entity) {
    if (entity == null || entity.getId() == null) {
      return;
    }
    dsl.deleteFrom(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS)
        .where(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.ID.eq(entity.getId()))
        .execute();
  }

  private Optional<ScriptPatchInstanceRolloutProjection> findById(Long id) {
    return dsl.selectFrom(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS)
        .where(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(
      ScriptPatchInstanceRolloutProjectionsRecord record,
      ScriptPatchInstanceRolloutProjection entity) {
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setScriptPinEpoch(entity.getScriptPinEpoch());
    record.setLastObservedControlPlaneRequestId(
        blankToNull(entity.getLastObservedControlPlaneRequestId()));
    record.setRolloutStatus(entity.getRolloutStatus());
    record.setStatusReason(entity.getStatusReason());
    record.setLastChangedAt(toLocalDateTime(entity.getLastChangedAt()));
    record.setProjectionRefreshedAt(toLocalDateTime(entity.getProjectionRefreshedAt()));
    record.setRowVersion(entity.getRowVersion());
  }

  private ScriptPatchInstanceRolloutProjection toEntity(Record record) {
    ScriptPatchInstanceRolloutProjection entity = new ScriptPatchInstanceRolloutProjection();
    entity.setId(record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.ID));
    entity.setTenantId(record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.TENANT_ID));
    entity.setGameInstanceId(
        record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.GAME_INSTANCE_ID));
    entity.setScriptPatchVersion(
        record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.SCRIPT_PATCH_VERSION));
    Long scriptPinEpoch = record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.SCRIPT_PIN_EPOCH);
    entity.setScriptPinEpoch(scriptPinEpoch == null ? 0L : scriptPinEpoch);
    entity.setLastObservedControlPlaneRequestId(
        blankToNull(
            record.get(
                SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.LAST_OBSERVED_CONTROL_PLANE_REQUEST_ID)));
    entity.setRolloutStatus(record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.ROLLOUT_STATUS));
    entity.setStatusReason(record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.STATUS_REASON));
    entity.setLastChangedAt(
        toInstant(record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.LAST_CHANGED_AT)));
    entity.setProjectionRefreshedAt(
        toInstant(record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.PROJECTION_REFRESHED_AT)));
    Integer rowVersion = record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_PROJECTIONS.ROW_VERSION);
    entity.setRowVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
