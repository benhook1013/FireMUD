package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventBindings.SCRIPT_EVENT_BINDINGS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptEventBindingsRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ScriptEventBindingRepository {
  private final DSLContext dsl;

  public ScriptEventBindingRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public void deleteByTenantIdAndScriptPatchVersionAndScriptId(
      Long tenantId, String scriptPatchVersion, String scriptId) {
    dsl.deleteFrom(SCRIPT_EVENT_BINDINGS)
        .where(
            SCRIPT_EVENT_BINDINGS
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_EVENT_BINDINGS.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion))
                .and(SCRIPT_EVENT_BINDINGS.SCRIPT_ID.eq(scriptId)))
        .execute();
  }

  public List<ScriptEventBinding>
      findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
          Long tenantId, String scriptPatchVersion, String eventType, String eventSchemaVersion) {
    return dsl.selectFrom(SCRIPT_EVENT_BINDINGS)
        .where(
            SCRIPT_EVENT_BINDINGS
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_EVENT_BINDINGS.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion))
                .and(SCRIPT_EVENT_BINDINGS.EVENT_TYPE.eq(eventType))
                .and(SCRIPT_EVENT_BINDINGS.EVENT_SCHEMA_VERSION.eq(eventSchemaVersion))
                .and(SCRIPT_EVENT_BINDINGS.ENABLED.eq(true)))
        .orderBy(SCRIPT_EVENT_BINDINGS.PRIORITY.asc(), SCRIPT_EVENT_BINDINGS.SCRIPT_ID.asc())
        .fetch(this::toEntity);
  }

  public List<ScriptEventBinding>
      findByTenantIdOrderByScriptPatchVersionAscEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
          Long tenantId) {
    return dsl.selectFrom(SCRIPT_EVENT_BINDINGS)
        .where(SCRIPT_EVENT_BINDINGS.TENANT_ID.eq(tenantId))
        .orderBy(
            SCRIPT_EVENT_BINDINGS.SCRIPT_PATCH_VERSION.asc(),
            SCRIPT_EVENT_BINDINGS.EVENT_TYPE.asc(),
            SCRIPT_EVENT_BINDINGS.EVENT_SCHEMA_VERSION.asc(),
            SCRIPT_EVENT_BINDINGS.PRIORITY.asc(),
            SCRIPT_EVENT_BINDINGS.SCRIPT_ID.asc())
        .fetch(this::toEntity);
  }

  public List<ScriptEventBinding>
      findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
          Long tenantId, String scriptPatchVersion) {
    return dsl.selectFrom(SCRIPT_EVENT_BINDINGS)
        .where(
            SCRIPT_EVENT_BINDINGS
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_EVENT_BINDINGS.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion)))
        .orderBy(
            SCRIPT_EVENT_BINDINGS.EVENT_TYPE.asc(),
            SCRIPT_EVENT_BINDINGS.EVENT_SCHEMA_VERSION.asc(),
            SCRIPT_EVENT_BINDINGS.PRIORITY.asc(),
            SCRIPT_EVENT_BINDINGS.SCRIPT_ID.asc())
        .fetch(this::toEntity);
  }

  public List<ScriptEventBinding> saveAll(Collection<ScriptEventBinding> entities) {
    if (entities == null || entities.isEmpty()) {
      return List.of();
    }
    return entities.stream().map(this::save).toList();
  }

  public ScriptEventBinding save(ScriptEventBinding entity) {
    if (entity.getId() == null) {
      ScriptEventBindingsRecord record = dsl.newRecord(SCRIPT_EVENT_BINDINGS);
      populate(record, entity);
      record.store();
      return findById(record.getId());
    }
    int nextRowVersion = entity.getRowVersion() + 1;
    int updated =
        dsl.update(SCRIPT_EVENT_BINDINGS)
            .set(SCRIPT_EVENT_BINDINGS.TENANT_ID, entity.getTenantId())
            .set(SCRIPT_EVENT_BINDINGS.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
            .set(SCRIPT_EVENT_BINDINGS.EVENT_TYPE, entity.getEventType())
            .set(SCRIPT_EVENT_BINDINGS.EVENT_SCHEMA_VERSION, entity.getEventSchemaVersion())
            .set(SCRIPT_EVENT_BINDINGS.SCRIPT_ID, entity.getScriptId())
            .set(SCRIPT_EVENT_BINDINGS.TARGET_SCOPE_TYPE, entity.getTargetScopeType())
            .set(SCRIPT_EVENT_BINDINGS.TARGET_SCOPE_ID, entity.getTargetScopeId())
            .set(SCRIPT_EVENT_BINDINGS.PRIORITY, entity.getPriority())
            .set(SCRIPT_EVENT_BINDINGS.PRIORITY_TAG, entity.getPriorityTag())
            .set(SCRIPT_EVENT_BINDINGS.REQUIRES_EXCLUSIVE_EVENT, entity.isRequiresExclusiveEvent())
            .set(SCRIPT_EVENT_BINDINGS.ENABLED, entity.isEnabled())
            .set(SCRIPT_EVENT_BINDINGS.ROW_VERSION, nextRowVersion)
            .where(
                SCRIPT_EVENT_BINDINGS
                    .ID
                    .eq(entity.getId())
                    .and(SCRIPT_EVENT_BINDINGS.ROW_VERSION.eq(entity.getRowVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite(
          "script_event_bindings", entity.getId());
    }
    entity.setRowVersion(nextRowVersion);
    return findById(entity.getId());
  }

  private ScriptEventBinding findById(Long id) {
    return dsl.selectFrom(SCRIPT_EVENT_BINDINGS)
        .where(SCRIPT_EVENT_BINDINGS.ID.eq(id))
        .fetchOne(this::toEntity);
  }

  private void populate(ScriptEventBindingsRecord record, ScriptEventBinding entity) {
    record.setTenantId(entity.getTenantId());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setEventType(entity.getEventType());
    record.setEventSchemaVersion(entity.getEventSchemaVersion());
    record.setScriptId(entity.getScriptId());
    record.setTargetScopeType(entity.getTargetScopeType());
    record.setTargetScopeId(entity.getTargetScopeId());
    record.setPriority(entity.getPriority());
    record.setPriorityTag(entity.getPriorityTag());
    record.setRequiresExclusiveEvent(entity.isRequiresExclusiveEvent());
    record.setEnabled(entity.isEnabled());
    record.setRowVersion(entity.getRowVersion());
  }

  private ScriptEventBinding toEntity(Record record) {
    ScriptEventBinding entity = new ScriptEventBinding();
    entity.setId(record.get(SCRIPT_EVENT_BINDINGS.ID));
    entity.setTenantId(record.get(SCRIPT_EVENT_BINDINGS.TENANT_ID));
    entity.setScriptPatchVersion(record.get(SCRIPT_EVENT_BINDINGS.SCRIPT_PATCH_VERSION));
    entity.setEventType(record.get(SCRIPT_EVENT_BINDINGS.EVENT_TYPE));
    entity.setEventSchemaVersion(record.get(SCRIPT_EVENT_BINDINGS.EVENT_SCHEMA_VERSION));
    entity.setScriptId(record.get(SCRIPT_EVENT_BINDINGS.SCRIPT_ID));
    entity.setTargetScopeType(record.get(SCRIPT_EVENT_BINDINGS.TARGET_SCOPE_TYPE));
    entity.setTargetScopeId(record.get(SCRIPT_EVENT_BINDINGS.TARGET_SCOPE_ID));
    Integer priority = record.get(SCRIPT_EVENT_BINDINGS.PRIORITY);
    entity.setPriority(priority == null ? 0 : priority);
    entity.setPriorityTag(record.get(SCRIPT_EVENT_BINDINGS.PRIORITY_TAG));
    Boolean requiresExclusive = record.get(SCRIPT_EVENT_BINDINGS.REQUIRES_EXCLUSIVE_EVENT);
    entity.setRequiresExclusiveEvent(Boolean.TRUE.equals(requiresExclusive));
    Boolean enabled = record.get(SCRIPT_EVENT_BINDINGS.ENABLED);
    entity.setEnabled(Boolean.TRUE.equals(enabled));
    Integer rowVersion = record.get(SCRIPT_EVENT_BINDINGS.ROW_VERSION);
    entity.setRowVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
