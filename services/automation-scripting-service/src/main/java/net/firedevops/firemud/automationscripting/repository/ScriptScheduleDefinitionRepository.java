package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptScheduleDefinitions.SCRIPT_SCHEDULE_DEFINITIONS;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toOffsetDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleDefinition;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptScheduleDefinitionsRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ScriptScheduleDefinitionRepository {
  private final DSLContext dsl;

  public ScriptScheduleDefinitionRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<ScriptScheduleDefinition>
      findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
          Long tenantId, String scriptPatchVersion) {
    return dsl.selectFrom(SCRIPT_SCHEDULE_DEFINITIONS)
        .where(
            SCRIPT_SCHEDULE_DEFINITIONS
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_SCHEDULE_DEFINITIONS.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion)))
        .orderBy(
            SCRIPT_SCHEDULE_DEFINITIONS.SCRIPT_ID.asc(),
            SCRIPT_SCHEDULE_DEFINITIONS.EVENT_TYPE.asc(),
            SCRIPT_SCHEDULE_DEFINITIONS.SCHEDULE_DEFINITION_ID.asc())
        .fetch(this::toEntity);
  }

  public void deleteByTenantIdAndScriptPatchVersionAndScriptIdIn(
      Long tenantId, String scriptPatchVersion, Collection<String> scriptIds) {
    if (scriptIds == null || scriptIds.isEmpty()) {
      return;
    }
    dsl.deleteFrom(SCRIPT_SCHEDULE_DEFINITIONS)
        .where(
            SCRIPT_SCHEDULE_DEFINITIONS
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_SCHEDULE_DEFINITIONS.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion))
                .and(SCRIPT_SCHEDULE_DEFINITIONS.SCRIPT_ID.in(scriptIds)))
        .execute();
  }

  public List<ScriptScheduleDefinition> saveAll(Collection<ScriptScheduleDefinition> entities) {
    if (entities == null || entities.isEmpty()) {
      return List.of();
    }
    return entities.stream().map(this::save).toList();
  }

  public ScriptScheduleDefinition save(ScriptScheduleDefinition entity) {
    if (entity.getId() == null) {
      ScriptScheduleDefinitionsRecord record = dsl.newRecord(SCRIPT_SCHEDULE_DEFINITIONS);
      populate(record, entity);
      record.store();
      return findById(record.getId());
    }
    int nextRowVersion = entity.getRowVersion() + 1;
    int updated =
        dsl.update(SCRIPT_SCHEDULE_DEFINITIONS)
            .set(SCRIPT_SCHEDULE_DEFINITIONS.TENANT_ID, entity.getTenantId())
            .set(SCRIPT_SCHEDULE_DEFINITIONS.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
            .set(SCRIPT_SCHEDULE_DEFINITIONS.SCRIPT_ID, entity.getScriptId())
            .set(SCRIPT_SCHEDULE_DEFINITIONS.PLUGIN_ID, entity.getPluginId())
            .set(SCRIPT_SCHEDULE_DEFINITIONS.PLUGIN_VERSION_ID, entity.getPluginVersionId())
            .set(SCRIPT_SCHEDULE_DEFINITIONS.EVENT_TYPE, entity.getEventType())
            .set(
                SCRIPT_SCHEDULE_DEFINITIONS.SCHEDULE_DEFINITION_ID,
                entity.getScheduleDefinitionId())
            .set(SCRIPT_SCHEDULE_DEFINITIONS.SCHEDULE_KIND, entity.getScheduleKind())
            .set(SCRIPT_SCHEDULE_DEFINITIONS.CADENCE_VALUE, entity.getCadenceValue())
            .set(SCRIPT_SCHEDULE_DEFINITIONS.CADENCE_UNIT, entity.getCadenceUnit())
            .set(SCRIPT_SCHEDULE_DEFINITIONS.PRIORITY_TAG, entity.getPriorityTag())
            .set(
                SCRIPT_SCHEDULE_DEFINITIONS.SCHEDULE_METADATA_JSON,
                entity.getScheduleMetadataJson())
            .set(
                SCRIPT_SCHEDULE_DEFINITIONS.SCHEDULE_SEMANTICS_HASH,
                entity.getScheduleSemanticsHash())
            .set(SCRIPT_SCHEDULE_DEFINITIONS.CREATED_AT, toOffsetDateTime(entity.getCreatedAt()))
            .set(SCRIPT_SCHEDULE_DEFINITIONS.UPDATED_AT, toOffsetDateTime(entity.getUpdatedAt()))
            .set(SCRIPT_SCHEDULE_DEFINITIONS.ROW_VERSION, nextRowVersion)
            .where(
                SCRIPT_SCHEDULE_DEFINITIONS
                    .ID
                    .eq(entity.getId())
                    .and(SCRIPT_SCHEDULE_DEFINITIONS.ROW_VERSION.eq(entity.getRowVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite(
          "script_schedule_definitions", entity.getId());
    }
    entity.setRowVersion(nextRowVersion);
    return findById(entity.getId());
  }

  private ScriptScheduleDefinition findById(Long id) {
    return dsl.selectFrom(SCRIPT_SCHEDULE_DEFINITIONS)
        .where(SCRIPT_SCHEDULE_DEFINITIONS.ID.eq(id))
        .fetchOne(this::toEntity);
  }

  private void populate(ScriptScheduleDefinitionsRecord record, ScriptScheduleDefinition entity) {
    record.setTenantId(entity.getTenantId());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setScriptId(entity.getScriptId());
    record.setPluginId(entity.getPluginId());
    record.setPluginVersionId(entity.getPluginVersionId());
    record.setEventType(entity.getEventType());
    record.setScheduleDefinitionId(entity.getScheduleDefinitionId());
    record.setScheduleKind(entity.getScheduleKind());
    record.setCadenceValue(entity.getCadenceValue());
    record.setCadenceUnit(entity.getCadenceUnit());
    record.setPriorityTag(entity.getPriorityTag());
    record.setScheduleMetadataJson(entity.getScheduleMetadataJson());
    record.setScheduleSemanticsHash(entity.getScheduleSemanticsHash());
    record.setCreatedAt(toOffsetDateTime(entity.getCreatedAt()));
    record.setUpdatedAt(toOffsetDateTime(entity.getUpdatedAt()));
    record.setRowVersion(entity.getRowVersion());
  }

  private ScriptScheduleDefinition toEntity(Record record) {
    ScriptScheduleDefinition entity = new ScriptScheduleDefinition();
    entity.setId(record.get(SCRIPT_SCHEDULE_DEFINITIONS.ID));
    entity.setTenantId(record.get(SCRIPT_SCHEDULE_DEFINITIONS.TENANT_ID));
    entity.setScriptPatchVersion(record.get(SCRIPT_SCHEDULE_DEFINITIONS.SCRIPT_PATCH_VERSION));
    entity.setScriptId(record.get(SCRIPT_SCHEDULE_DEFINITIONS.SCRIPT_ID));
    entity.setPluginId(record.get(SCRIPT_SCHEDULE_DEFINITIONS.PLUGIN_ID));
    entity.setPluginVersionId(record.get(SCRIPT_SCHEDULE_DEFINITIONS.PLUGIN_VERSION_ID));
    entity.setEventType(record.get(SCRIPT_SCHEDULE_DEFINITIONS.EVENT_TYPE));
    entity.setScheduleDefinitionId(record.get(SCRIPT_SCHEDULE_DEFINITIONS.SCHEDULE_DEFINITION_ID));
    entity.setScheduleKind(record.get(SCRIPT_SCHEDULE_DEFINITIONS.SCHEDULE_KIND));
    entity.setCadenceValue(record.get(SCRIPT_SCHEDULE_DEFINITIONS.CADENCE_VALUE));
    entity.setCadenceUnit(record.get(SCRIPT_SCHEDULE_DEFINITIONS.CADENCE_UNIT));
    entity.setPriorityTag(record.get(SCRIPT_SCHEDULE_DEFINITIONS.PRIORITY_TAG));
    entity.setScheduleMetadataJson(record.get(SCRIPT_SCHEDULE_DEFINITIONS.SCHEDULE_METADATA_JSON));
    entity.setScheduleSemanticsHash(
        record.get(SCRIPT_SCHEDULE_DEFINITIONS.SCHEDULE_SEMANTICS_HASH));
    entity.setCreatedAt(toInstant(record.get(SCRIPT_SCHEDULE_DEFINITIONS.CREATED_AT)));
    entity.setUpdatedAt(toInstant(record.get(SCRIPT_SCHEDULE_DEFINITIONS.UPDATED_AT)));
    Integer rowVersion = record.get(SCRIPT_SCHEDULE_DEFINITIONS.ROW_VERSION);
    entity.setRowVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
