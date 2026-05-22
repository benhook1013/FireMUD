package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptScheduleInstances.SCRIPT_SCHEDULE_INSTANCES;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toOffsetDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleInstance;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptScheduleInstancesRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ScriptScheduleInstanceRepository {
  private final DSLContext dsl;

  public ScriptScheduleInstanceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<ScriptScheduleInstance>
      findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
          String tenantId, String gameInstanceId) {
    return dsl.selectFrom(SCRIPT_SCHEDULE_INSTANCES)
        .where(
            SCRIPT_SCHEDULE_INSTANCES
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_SCHEDULE_INSTANCES.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .orderBy(
            SCRIPT_SCHEDULE_INSTANCES.UPDATED_AT.desc(),
            SCRIPT_SCHEDULE_INSTANCES.SCHEDULE_DEFINITION_ID.asc())
        .fetch(this::toEntity);
  }

  public List<ScriptScheduleInstance>
      findByTenantIdAndGameInstanceIdAndScriptPatchVersionOrderByUpdatedAtDescScheduleDefinitionIdAsc(
          String tenantId, String gameInstanceId, String scriptPatchVersion) {
    return dsl.selectFrom(SCRIPT_SCHEDULE_INSTANCES)
        .where(
            SCRIPT_SCHEDULE_INSTANCES
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_SCHEDULE_INSTANCES.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(SCRIPT_SCHEDULE_INSTANCES.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion)))
        .orderBy(
            SCRIPT_SCHEDULE_INSTANCES.UPDATED_AT.desc(),
            SCRIPT_SCHEDULE_INSTANCES.SCHEDULE_DEFINITION_ID.asc())
        .fetch(this::toEntity);
  }

  public List<ScriptScheduleInstance> findByTenantIdAndGameInstanceIdAndCadenceUnit(
      String tenantId, String gameInstanceId, String cadenceUnit) {
    return dsl.selectFrom(SCRIPT_SCHEDULE_INSTANCES)
        .where(
            SCRIPT_SCHEDULE_INSTANCES
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_SCHEDULE_INSTANCES.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(SCRIPT_SCHEDULE_INSTANCES.CADENCE_UNIT.eq(cadenceUnit)))
        .fetch(this::toEntity);
  }

  public void deleteByTenantIdAndGameInstanceId(String tenantId, String gameInstanceId) {
    dsl.deleteFrom(SCRIPT_SCHEDULE_INSTANCES)
        .where(
            SCRIPT_SCHEDULE_INSTANCES
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_SCHEDULE_INSTANCES.GAME_INSTANCE_ID.eq(gameInstanceId)))
        .execute();
  }

  public void deleteAll(Collection<ScriptScheduleInstance> entities) {
    if (entities == null || entities.isEmpty()) {
      return;
    }
    List<Long> ids =
        entities.stream().map(ScriptScheduleInstance::getId).filter(id -> id != null).toList();
    if (ids.isEmpty()) {
      return;
    }
    dsl.deleteFrom(SCRIPT_SCHEDULE_INSTANCES).where(SCRIPT_SCHEDULE_INSTANCES.ID.in(ids)).execute();
  }

  public List<ScriptScheduleInstance> saveAll(Collection<ScriptScheduleInstance> entities) {
    if (entities == null || entities.isEmpty()) {
      return List.of();
    }
    return entities.stream().map(this::save).toList();
  }

  public ScriptScheduleInstance save(ScriptScheduleInstance entity) {
    if (entity.getId() == null) {
      ScriptScheduleInstancesRecord record = dsl.newRecord(SCRIPT_SCHEDULE_INSTANCES);
      populate(record, entity);
      record.store();
      return findById(record.getId());
    }
    int nextRowVersion = entity.getRowVersion() + 1;
    int updated =
        dsl.update(SCRIPT_SCHEDULE_INSTANCES)
            .set(SCRIPT_SCHEDULE_INSTANCES.TENANT_ID, entity.getTenantId())
            .set(SCRIPT_SCHEDULE_INSTANCES.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(SCRIPT_SCHEDULE_INSTANCES.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
            .set(SCRIPT_SCHEDULE_INSTANCES.SCRIPT_ID, entity.getScriptId())
            .set(SCRIPT_SCHEDULE_INSTANCES.PLAYABLE_STATE_SCOPE, entity.getPlayableStateScope())
            .set(SCRIPT_SCHEDULE_INSTANCES.WORLD_SLUG, entity.getWorldSlug())
            .set(SCRIPT_SCHEDULE_INSTANCES.REALM_SLUG, entity.getRealmSlug())
            .set(SCRIPT_SCHEDULE_INSTANCES.POINTER_VERSION, entity.getPointerVersion())
            .set(SCRIPT_SCHEDULE_INSTANCES.PLUGIN_ID, entity.getPluginId())
            .set(SCRIPT_SCHEDULE_INSTANCES.PLUGIN_VERSION_ID, entity.getPluginVersionId())
            .set(SCRIPT_SCHEDULE_INSTANCES.EVENT_TYPE, entity.getEventType())
            .set(SCRIPT_SCHEDULE_INSTANCES.SCHEDULE_DEFINITION_ID, entity.getScheduleDefinitionId())
            .set(SCRIPT_SCHEDULE_INSTANCES.SCHEDULE_KIND, entity.getScheduleKind())
            .set(SCRIPT_SCHEDULE_INSTANCES.CADENCE_VALUE, entity.getCadenceValue())
            .set(SCRIPT_SCHEDULE_INSTANCES.CADENCE_UNIT, entity.getCadenceUnit())
            .set(SCRIPT_SCHEDULE_INSTANCES.PRIORITY_TAG, entity.getPriorityTag())
            .set(SCRIPT_SCHEDULE_INSTANCES.TARGET_SCOPE_TYPE, entity.getTargetScopeType())
            .set(SCRIPT_SCHEDULE_INSTANCES.TARGET_SCOPE_ID, entity.getTargetScopeId())
            .set(SCRIPT_SCHEDULE_INSTANCES.BINDING_PRIORITY, entity.getBindingPriority())
            .set(
                SCRIPT_SCHEDULE_INSTANCES.REQUIRES_EXCLUSIVE_EVENT,
                entity.isRequiresExclusiveEvent())
            .set(
                SCRIPT_SCHEDULE_INSTANCES.MATERIALIZATION_STATUS, entity.getMaterializationStatus())
            .set(SCRIPT_SCHEDULE_INSTANCES.NEXT_DUE_AT, toOffsetDateTime(entity.getNextDueAt()))
            .set(SCRIPT_SCHEDULE_INSTANCES.NEXT_DUE_TICK_ID, entity.getNextDueTickId())
            .set(SCRIPT_SCHEDULE_INSTANCES.RUNTIME_REGION_ID, entity.getRuntimeRegionId())
            .set(SCRIPT_SCHEDULE_INSTANCES.RUNTIME_REGION_EPOCH, entity.getRuntimeRegionEpoch())
            .set(SCRIPT_SCHEDULE_INSTANCES.LAST_OBSERVED_TICK_ID, entity.getLastObservedTickId())
            .set(
                SCRIPT_SCHEDULE_INSTANCES.LAST_RUNTIME_PROGRESS_OBSERVED_AT,
                toOffsetDateTime(entity.getLastRuntimeProgressObservedAt()))
            .set(
                SCRIPT_SCHEDULE_INSTANCES.OBSERVED_RUNTIME_VERSION_ID,
                entity.getObservedRuntimeVersionId())
            .set(
                SCRIPT_SCHEDULE_INSTANCES.LAST_OBSERVED_CONTROL_PLANE_REQUEST_ID,
                entity.getLastObservedControlPlaneRequestId())
            .set(SCRIPT_SCHEDULE_INSTANCES.SCHEDULE_METADATA_JSON, entity.getScheduleMetadataJson())
            .set(
                SCRIPT_SCHEDULE_INSTANCES.SCHEDULE_SEMANTICS_HASH,
                entity.getScheduleSemanticsHash())
            .set(
                SCRIPT_SCHEDULE_INSTANCES.PIN_OBSERVED_AT,
                toOffsetDateTime(entity.getPinObservedAt()))
            .set(
                SCRIPT_SCHEDULE_INSTANCES.MATERIALIZED_AT,
                toOffsetDateTime(entity.getMaterializedAt()))
            .set(SCRIPT_SCHEDULE_INSTANCES.UPDATED_AT, toOffsetDateTime(entity.getUpdatedAt()))
            .set(SCRIPT_SCHEDULE_INSTANCES.ROW_VERSION, nextRowVersion)
            .where(
                SCRIPT_SCHEDULE_INSTANCES
                    .ID
                    .eq(entity.getId())
                    .and(SCRIPT_SCHEDULE_INSTANCES.ROW_VERSION.eq(entity.getRowVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite(
          "script_schedule_instances", entity.getId());
    }
    entity.setRowVersion(nextRowVersion);
    return findById(entity.getId());
  }

  private ScriptScheduleInstance findById(Long id) {
    return dsl.selectFrom(SCRIPT_SCHEDULE_INSTANCES)
        .where(SCRIPT_SCHEDULE_INSTANCES.ID.eq(id))
        .fetchOne(this::toEntity);
  }

  private void populate(ScriptScheduleInstancesRecord record, ScriptScheduleInstance entity) {
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setScriptId(entity.getScriptId());
    record.setPlayableStateScope(entity.getPlayableStateScope());
    record.setWorldSlug(entity.getWorldSlug());
    record.setRealmSlug(entity.getRealmSlug());
    record.setPointerVersion(entity.getPointerVersion());
    record.setPluginId(entity.getPluginId());
    record.setPluginVersionId(entity.getPluginVersionId());
    record.setEventType(entity.getEventType());
    record.setScheduleDefinitionId(entity.getScheduleDefinitionId());
    record.setScheduleKind(entity.getScheduleKind());
    record.setCadenceValue(entity.getCadenceValue());
    record.setCadenceUnit(entity.getCadenceUnit());
    record.setPriorityTag(entity.getPriorityTag());
    record.setTargetScopeType(entity.getTargetScopeType());
    record.setTargetScopeId(entity.getTargetScopeId());
    record.setBindingPriority(entity.getBindingPriority());
    record.setRequiresExclusiveEvent(entity.isRequiresExclusiveEvent());
    record.setMaterializationStatus(entity.getMaterializationStatus());
    record.setNextDueAt(toOffsetDateTime(entity.getNextDueAt()));
    record.setNextDueTickId(entity.getNextDueTickId());
    record.setRuntimeRegionId(entity.getRuntimeRegionId());
    record.setRuntimeRegionEpoch(entity.getRuntimeRegionEpoch());
    record.setLastObservedTickId(entity.getLastObservedTickId());
    record.setLastRuntimeProgressObservedAt(
        toOffsetDateTime(entity.getLastRuntimeProgressObservedAt()));
    record.setObservedRuntimeVersionId(entity.getObservedRuntimeVersionId());
    record.setLastObservedControlPlaneRequestId(entity.getLastObservedControlPlaneRequestId());
    record.setScheduleMetadataJson(entity.getScheduleMetadataJson());
    record.setScheduleSemanticsHash(entity.getScheduleSemanticsHash());
    record.setPinObservedAt(toOffsetDateTime(entity.getPinObservedAt()));
    record.setMaterializedAt(toOffsetDateTime(entity.getMaterializedAt()));
    record.setUpdatedAt(toOffsetDateTime(entity.getUpdatedAt()));
    record.setRowVersion(entity.getRowVersion());
  }

  private ScriptScheduleInstance toEntity(Record record) {
    ScriptScheduleInstance entity = new ScriptScheduleInstance();
    entity.setId(record.get(SCRIPT_SCHEDULE_INSTANCES.ID));
    entity.setTenantId(record.get(SCRIPT_SCHEDULE_INSTANCES.TENANT_ID));
    entity.setGameInstanceId(record.get(SCRIPT_SCHEDULE_INSTANCES.GAME_INSTANCE_ID));
    entity.setScriptPatchVersion(record.get(SCRIPT_SCHEDULE_INSTANCES.SCRIPT_PATCH_VERSION));
    entity.setScriptId(record.get(SCRIPT_SCHEDULE_INSTANCES.SCRIPT_ID));
    entity.setPlayableStateScope(record.get(SCRIPT_SCHEDULE_INSTANCES.PLAYABLE_STATE_SCOPE));
    entity.setWorldSlug(record.get(SCRIPT_SCHEDULE_INSTANCES.WORLD_SLUG));
    entity.setRealmSlug(record.get(SCRIPT_SCHEDULE_INSTANCES.REALM_SLUG));
    entity.setPointerVersion(record.get(SCRIPT_SCHEDULE_INSTANCES.POINTER_VERSION));
    entity.setPluginId(record.get(SCRIPT_SCHEDULE_INSTANCES.PLUGIN_ID));
    entity.setPluginVersionId(record.get(SCRIPT_SCHEDULE_INSTANCES.PLUGIN_VERSION_ID));
    entity.setEventType(record.get(SCRIPT_SCHEDULE_INSTANCES.EVENT_TYPE));
    entity.setScheduleDefinitionId(record.get(SCRIPT_SCHEDULE_INSTANCES.SCHEDULE_DEFINITION_ID));
    entity.setScheduleKind(record.get(SCRIPT_SCHEDULE_INSTANCES.SCHEDULE_KIND));
    entity.setCadenceValue(record.get(SCRIPT_SCHEDULE_INSTANCES.CADENCE_VALUE));
    entity.setCadenceUnit(record.get(SCRIPT_SCHEDULE_INSTANCES.CADENCE_UNIT));
    entity.setPriorityTag(record.get(SCRIPT_SCHEDULE_INSTANCES.PRIORITY_TAG));
    entity.setTargetScopeType(record.get(SCRIPT_SCHEDULE_INSTANCES.TARGET_SCOPE_TYPE));
    entity.setTargetScopeId(record.get(SCRIPT_SCHEDULE_INSTANCES.TARGET_SCOPE_ID));
    Integer bindingPriority = record.get(SCRIPT_SCHEDULE_INSTANCES.BINDING_PRIORITY);
    entity.setBindingPriority(bindingPriority == null ? 0 : bindingPriority);
    Boolean requiresExclusive = record.get(SCRIPT_SCHEDULE_INSTANCES.REQUIRES_EXCLUSIVE_EVENT);
    entity.setRequiresExclusiveEvent(Boolean.TRUE.equals(requiresExclusive));
    entity.setMaterializationStatus(record.get(SCRIPT_SCHEDULE_INSTANCES.MATERIALIZATION_STATUS));
    entity.setNextDueAt(toInstant(record.get(SCRIPT_SCHEDULE_INSTANCES.NEXT_DUE_AT)));
    entity.setNextDueTickId(record.get(SCRIPT_SCHEDULE_INSTANCES.NEXT_DUE_TICK_ID));
    entity.setRuntimeRegionId(record.get(SCRIPT_SCHEDULE_INSTANCES.RUNTIME_REGION_ID));
    entity.setRuntimeRegionEpoch(record.get(SCRIPT_SCHEDULE_INSTANCES.RUNTIME_REGION_EPOCH));
    entity.setLastObservedTickId(record.get(SCRIPT_SCHEDULE_INSTANCES.LAST_OBSERVED_TICK_ID));
    entity.setLastRuntimeProgressObservedAt(
        toInstant(record.get(SCRIPT_SCHEDULE_INSTANCES.LAST_RUNTIME_PROGRESS_OBSERVED_AT)));
    entity.setObservedRuntimeVersionId(
        record.get(SCRIPT_SCHEDULE_INSTANCES.OBSERVED_RUNTIME_VERSION_ID));
    entity.setLastObservedControlPlaneRequestId(
        record.get(SCRIPT_SCHEDULE_INSTANCES.LAST_OBSERVED_CONTROL_PLANE_REQUEST_ID));
    entity.setScheduleMetadataJson(record.get(SCRIPT_SCHEDULE_INSTANCES.SCHEDULE_METADATA_JSON));
    entity.setScheduleSemanticsHash(record.get(SCRIPT_SCHEDULE_INSTANCES.SCHEDULE_SEMANTICS_HASH));
    entity.setPinObservedAt(toInstant(record.get(SCRIPT_SCHEDULE_INSTANCES.PIN_OBSERVED_AT)));
    entity.setMaterializedAt(toInstant(record.get(SCRIPT_SCHEDULE_INSTANCES.MATERIALIZED_AT)));
    entity.setUpdatedAt(toInstant(record.get(SCRIPT_SCHEDULE_INSTANCES.UPDATED_AT)));
    Integer rowVersion = record.get(SCRIPT_SCHEDULE_INSTANCES.ROW_VERSION);
    entity.setRowVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
