package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptPatchPinProjections.SCRIPT_PATCH_PIN_PROJECTIONS;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchPinProjection;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptPatchPinProjectionsRecord;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ScriptPatchPinProjectionRepository {
  private static final Field<Long> SCRIPT_PIN_EPOCH = field(name("script_pin_epoch"), Long.class);
  private final DSLContext dsl;

  public ScriptPatchPinProjectionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<ScriptPatchPinProjection> findByTenantIdAndGameInstanceId(
      String tenantId, String gameInstanceId) {
    return dsl.select(SCRIPT_PATCH_PIN_PROJECTIONS.fields()).select(SCRIPT_PIN_EPOCH)
        .from(SCRIPT_PATCH_PIN_PROJECTIONS)
        .where(
            SCRIPT_PATCH_PIN_PROJECTIONS
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_PATCH_PIN_PROJECTIONS.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .fetchOptional(this::toEntity);
  }

  public List<ScriptPatchPinProjection> findByTenantIdAndObservedPinnedScriptPatchVersion(
      String tenantId, String observedPinnedScriptPatchVersion) {
    return dsl.select(SCRIPT_PATCH_PIN_PROJECTIONS.fields()).select(SCRIPT_PIN_EPOCH)
        .from(SCRIPT_PATCH_PIN_PROJECTIONS)
        .where(
            SCRIPT_PATCH_PIN_PROJECTIONS
                .TENANT_ID
                .eq(tenantId)
                .and(
                    SCRIPT_PATCH_PIN_PROJECTIONS.OBSERVED_PINNED_SCRIPT_PATCH_VERSION.eq(
                        observedPinnedScriptPatchVersion)))
        .fetch(this::toEntity);
  }

  public ScriptPatchPinProjection save(ScriptPatchPinProjection entity) {
    if (entity.getId() == null) {
      ScriptPatchPinProjectionsRecord record = dsl.newRecord(SCRIPT_PATCH_PIN_PROJECTIONS);
      populate(record, entity);
      Long id =
          dsl.insertInto(SCRIPT_PATCH_PIN_PROJECTIONS)
              .set(record)
              .set(SCRIPT_PIN_EPOCH, entity.getScriptPinEpoch())
              .returning(SCRIPT_PATCH_PIN_PROJECTIONS.ID)
              .fetchOne(SCRIPT_PATCH_PIN_PROJECTIONS.ID);
      if (id == null) {
        throw new IllegalStateException("Saved script patch projection did not return an id");
      }
      record.setId(id);
      return findById(record.getId()).orElseThrow();
    }
    int nextRowVersion = entity.getRowVersion() + 1;
    int updated =
        dsl.update(SCRIPT_PATCH_PIN_PROJECTIONS)
            .set(SCRIPT_PATCH_PIN_PROJECTIONS.TENANT_ID, entity.getTenantId())
            .set(SCRIPT_PATCH_PIN_PROJECTIONS.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(
                SCRIPT_PATCH_PIN_PROJECTIONS.OBSERVED_PINNED_SCRIPT_PATCH_VERSION,
                entity.getObservedPinnedScriptPatchVersion())
            .set(SCRIPT_PATCH_PIN_PROJECTIONS.PLAYABLE_STATE_SCOPE, entity.getPlayableStateScope())
            .set(SCRIPT_PATCH_PIN_PROJECTIONS.WORLD_SLUG, entity.getWorldSlug())
            .set(SCRIPT_PATCH_PIN_PROJECTIONS.REALM_SLUG, entity.getRealmSlug())
            .set(SCRIPT_PATCH_PIN_PROJECTIONS.POINTER_VERSION, entity.getPointerVersion())
            .set(SCRIPT_PIN_EPOCH, entity.getScriptPinEpoch())
            .set(SCRIPT_PATCH_PIN_PROJECTIONS.RUNTIME_REGION_ID, entity.getRuntimeRegionId())
            .set(SCRIPT_PATCH_PIN_PROJECTIONS.RUNTIME_REGION_EPOCH, entity.getRuntimeRegionEpoch())
            .set(
                SCRIPT_PATCH_PIN_PROJECTIONS.LAST_OBSERVED_CONTROL_PLANE_REQUEST_ID,
                entity.getLastObservedControlPlaneRequestId())
            .set(SCRIPT_PATCH_PIN_PROJECTIONS.OBSERVED_AT, toLocalDateTime(entity.getObservedAt()))
            .set(
                SCRIPT_PATCH_PIN_PROJECTIONS.PROJECTION_REFRESHED_AT,
                toLocalDateTime(entity.getProjectionRefreshedAt()))
            .set(SCRIPT_PATCH_PIN_PROJECTIONS.ROW_VERSION, nextRowVersion)
            .where(
                SCRIPT_PATCH_PIN_PROJECTIONS
                    .ID
                    .eq(entity.getId())
                    .and(SCRIPT_PATCH_PIN_PROJECTIONS.ROW_VERSION.eq(entity.getRowVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite(
          "script_patch_pin_projections", entity.getId());
    }
    entity.setRowVersion(nextRowVersion);
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<ScriptPatchPinProjection> findById(Long id) {
    return dsl.select(SCRIPT_PATCH_PIN_PROJECTIONS.fields()).select(SCRIPT_PIN_EPOCH)
        .from(SCRIPT_PATCH_PIN_PROJECTIONS)
        .where(SCRIPT_PATCH_PIN_PROJECTIONS.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(ScriptPatchPinProjectionsRecord record, ScriptPatchPinProjection entity) {
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setObservedPinnedScriptPatchVersion(entity.getObservedPinnedScriptPatchVersion());
    record.setPlayableStateScope(entity.getPlayableStateScope());
    record.setWorldSlug(entity.getWorldSlug());
    record.setRealmSlug(entity.getRealmSlug());
    record.setPointerVersion(entity.getPointerVersion());
    record.setRuntimeRegionId(entity.getRuntimeRegionId());
    record.setRuntimeRegionEpoch(entity.getRuntimeRegionEpoch());
    record.setLastObservedControlPlaneRequestId(entity.getLastObservedControlPlaneRequestId());
    record.setObservedAt(toLocalDateTime(entity.getObservedAt()));
    record.setProjectionRefreshedAt(toLocalDateTime(entity.getProjectionRefreshedAt()));
    record.setRowVersion(entity.getRowVersion());
  }

  private ScriptPatchPinProjection toEntity(Record record) {
    ScriptPatchPinProjection entity = new ScriptPatchPinProjection();
    entity.setId(record.get(SCRIPT_PATCH_PIN_PROJECTIONS.ID));
    entity.setTenantId(record.get(SCRIPT_PATCH_PIN_PROJECTIONS.TENANT_ID));
    entity.setGameInstanceId(record.get(SCRIPT_PATCH_PIN_PROJECTIONS.GAME_INSTANCE_ID));
    entity.setObservedPinnedScriptPatchVersion(
        record.get(SCRIPT_PATCH_PIN_PROJECTIONS.OBSERVED_PINNED_SCRIPT_PATCH_VERSION));
    entity.setPlayableStateScope(record.get(SCRIPT_PATCH_PIN_PROJECTIONS.PLAYABLE_STATE_SCOPE));
    entity.setWorldSlug(record.get(SCRIPT_PATCH_PIN_PROJECTIONS.WORLD_SLUG));
    entity.setRealmSlug(record.get(SCRIPT_PATCH_PIN_PROJECTIONS.REALM_SLUG));
    entity.setPointerVersion(record.get(SCRIPT_PATCH_PIN_PROJECTIONS.POINTER_VERSION));
    Long scriptPinEpoch = record.get(SCRIPT_PIN_EPOCH);
    entity.setScriptPinEpoch(scriptPinEpoch == null ? 0L : scriptPinEpoch);
    entity.setRuntimeRegionId(record.get(SCRIPT_PATCH_PIN_PROJECTIONS.RUNTIME_REGION_ID));
    Long runtimeRegionEpoch = record.get(SCRIPT_PATCH_PIN_PROJECTIONS.RUNTIME_REGION_EPOCH);
    entity.setRuntimeRegionEpoch(runtimeRegionEpoch == null ? 0L : runtimeRegionEpoch);
    entity.setLastObservedControlPlaneRequestId(
        record.get(SCRIPT_PATCH_PIN_PROJECTIONS.LAST_OBSERVED_CONTROL_PLANE_REQUEST_ID));
    entity.setObservedAt(toInstant(record.get(SCRIPT_PATCH_PIN_PROJECTIONS.OBSERVED_AT)));
    entity.setProjectionRefreshedAt(
        toInstant(record.get(SCRIPT_PATCH_PIN_PROJECTIONS.PROJECTION_REFRESHED_AT)));
    Integer rowVersion = record.get(SCRIPT_PATCH_PIN_PROJECTIONS.ROW_VERSION);
    entity.setRowVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
