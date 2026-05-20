package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventIngressAudit.SCRIPT_EVENT_INGRESS_AUDIT;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptEventIngressAudit;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptEventIngressAuditRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ScriptEventIngressAuditRepository {
  private final DSLContext dsl;

  public ScriptEventIngressAuditRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<ScriptEventIngressAudit>
      findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
          String tenantId,
          String gameInstanceId,
          String regionId,
          Long regionEpoch,
          String entityId,
          String playableStateScope,
          String worldSlug,
          String realmSlug,
          String pointerVersion,
          String eventType,
          String eventSchemaVersion,
          String scriptPatchVersion,
          String scriptEventId,
          boolean dryRun) {
    return dsl.selectFrom(SCRIPT_EVENT_INGRESS_AUDIT)
        .where(
            SCRIPT_EVENT_INGRESS_AUDIT
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_EVENT_INGRESS_AUDIT.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.REGION_ID.eq(regionId))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.REGION_EPOCH.eq(regionEpoch))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.ENTITY_ID.eq(entityId))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.PLAYABLE_STATE_SCOPE.eq(playableStateScope))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.WORLD_SLUG.eq(worldSlug))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.REALM_SLUG.eq(realmSlug))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.POINTER_VERSION.eq(pointerVersion))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.EVENT_TYPE.eq(eventType))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.EVENT_SCHEMA_VERSION.eq(eventSchemaVersion))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_EVENT_ID.eq(scriptEventId))
                .and(SCRIPT_EVENT_INGRESS_AUDIT.DRY_RUN.eq(dryRun)))
        .fetchOptional(this::toEntity);
  }

  public ScriptEventIngressAudit save(ScriptEventIngressAudit entity) {
    if (entity.getId() == null) {
      ScriptEventIngressAuditRecord record = dsl.newRecord(SCRIPT_EVENT_INGRESS_AUDIT);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int nextRowVersion = entity.getRowVersion() + 1;
    int updated =
        dsl.update(SCRIPT_EVENT_INGRESS_AUDIT)
            .set(SCRIPT_EVENT_INGRESS_AUDIT.TENANT_ID, entity.getTenantId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.REGION_ID, entity.getRegionId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.REGION_EPOCH, entity.getRegionEpoch())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.ENTITY_ID, entity.getEntityId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.PLAYABLE_STATE_SCOPE, entity.getPlayableStateScope())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.WORLD_SLUG, entity.getWorldSlug())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.REALM_SLUG, entity.getRealmSlug())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.POINTER_VERSION, entity.getPointerVersion())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_ID, entity.getScriptId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.PLUGIN_ID, entity.getPluginId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.PLUGIN_VERSION_ID, entity.getPluginVersionId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.EVENT_TYPE, entity.getEventType())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.EVENT_SCHEMA_VERSION, entity.getEventSchemaVersion())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_EVENT_ID, entity.getScriptEventId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_SERVICE, entity.getSourceService())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.TRIGGER_MODE, entity.getTriggerMode())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_KIND, entity.getSourceKind())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_STATE, entity.getSourceState())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_ORDINAL, entity.getSourceOrdinal())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_DUE_TICK_ID, entity.getSourceDueTickId())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_DUE_AT_MS, entity.getSourceDueAtMs())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.DRY_RUN, entity.isDryRun())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.READ_SNAPSHOT_TOKEN, entity.getReadSnapshotToken())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.PAYLOAD_JSON, entity.getPayloadJson())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.ADMITTED, entity.isAdmitted())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.ADMISSION_OUTCOME, entity.getAdmissionOutcome())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.ADMISSION_REASON, entity.getAdmissionReason())
            .set(
                SCRIPT_EVENT_INGRESS_AUDIT.RESOLVED_HANDLER_COUNT, entity.getResolvedHandlerCount())
            .set(SCRIPT_EVENT_INGRESS_AUDIT.CREATED_AT, toLocalDateTime(entity.getCreatedAt()))
            .set(SCRIPT_EVENT_INGRESS_AUDIT.ROW_VERSION, nextRowVersion)
            .where(
                SCRIPT_EVENT_INGRESS_AUDIT
                    .ID
                    .eq(entity.getId())
                    .and(SCRIPT_EVENT_INGRESS_AUDIT.ROW_VERSION.eq(entity.getRowVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite(
          "script_event_ingress_audit", entity.getId());
    }
    entity.setRowVersion(nextRowVersion);
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<ScriptEventIngressAudit> findById(Long id) {
    return dsl.selectFrom(SCRIPT_EVENT_INGRESS_AUDIT)
        .where(SCRIPT_EVENT_INGRESS_AUDIT.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(ScriptEventIngressAuditRecord record, ScriptEventIngressAudit entity) {
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setRegionId(entity.getRegionId());
    record.setRegionEpoch(entity.getRegionEpoch());
    record.setEntityId(entity.getEntityId());
    record.setPlayableStateScope(entity.getPlayableStateScope());
    record.setWorldSlug(entity.getWorldSlug());
    record.setRealmSlug(entity.getRealmSlug());
    record.setPointerVersion(entity.getPointerVersion());
    record.setScriptId(entity.getScriptId());
    record.setPluginId(entity.getPluginId());
    record.setPluginVersionId(entity.getPluginVersionId());
    record.setEventType(entity.getEventType());
    record.setEventSchemaVersion(entity.getEventSchemaVersion());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setScriptEventId(entity.getScriptEventId());
    record.setSourceService(entity.getSourceService());
    record.setTriggerMode(entity.getTriggerMode());
    record.setSourceKind(entity.getSourceKind());
    record.setSourceState(entity.getSourceState());
    record.setSourceOrdinal(entity.getSourceOrdinal());
    record.setSourceDueTickId(entity.getSourceDueTickId());
    record.setSourceDueAtMs(entity.getSourceDueAtMs());
    record.setDryRun(entity.isDryRun());
    record.setReadSnapshotToken(entity.getReadSnapshotToken());
    record.setPayloadJson(entity.getPayloadJson());
    record.setAdmitted(entity.isAdmitted());
    record.setAdmissionOutcome(entity.getAdmissionOutcome());
    record.setAdmissionReason(entity.getAdmissionReason());
    record.setResolvedHandlerCount(entity.getResolvedHandlerCount());
    record.setCreatedAt(toLocalDateTime(entity.getCreatedAt()));
    record.setRowVersion(entity.getRowVersion());
  }

  private ScriptEventIngressAudit toEntity(Record record) {
    ScriptEventIngressAudit entity = new ScriptEventIngressAudit();
    entity.setId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.ID));
    entity.setTenantId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.TENANT_ID));
    entity.setGameInstanceId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.GAME_INSTANCE_ID));
    entity.setRegionId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.REGION_ID));
    entity.setRegionEpoch(record.get(SCRIPT_EVENT_INGRESS_AUDIT.REGION_EPOCH));
    entity.setEntityId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.ENTITY_ID));
    entity.setPlayableStateScope(record.get(SCRIPT_EVENT_INGRESS_AUDIT.PLAYABLE_STATE_SCOPE));
    entity.setWorldSlug(record.get(SCRIPT_EVENT_INGRESS_AUDIT.WORLD_SLUG));
    entity.setRealmSlug(record.get(SCRIPT_EVENT_INGRESS_AUDIT.REALM_SLUG));
    entity.setPointerVersion(record.get(SCRIPT_EVENT_INGRESS_AUDIT.POINTER_VERSION));
    entity.setScriptId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_ID));
    entity.setPluginId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.PLUGIN_ID));
    entity.setPluginVersionId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.PLUGIN_VERSION_ID));
    entity.setEventType(record.get(SCRIPT_EVENT_INGRESS_AUDIT.EVENT_TYPE));
    entity.setEventSchemaVersion(record.get(SCRIPT_EVENT_INGRESS_AUDIT.EVENT_SCHEMA_VERSION));
    entity.setScriptPatchVersion(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_PATCH_VERSION));
    entity.setScriptEventId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SCRIPT_EVENT_ID));
    entity.setSourceService(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_SERVICE));
    entity.setTriggerMode(record.get(SCRIPT_EVENT_INGRESS_AUDIT.TRIGGER_MODE));
    entity.setSourceKind(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_KIND));
    entity.setSourceState(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_STATE));
    entity.setSourceOrdinal(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_ORDINAL));
    entity.setSourceDueTickId(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_DUE_TICK_ID));
    entity.setSourceDueAtMs(record.get(SCRIPT_EVENT_INGRESS_AUDIT.SOURCE_DUE_AT_MS));
    Boolean dryRun = record.get(SCRIPT_EVENT_INGRESS_AUDIT.DRY_RUN);
    entity.setDryRun(Boolean.TRUE.equals(dryRun));
    entity.setReadSnapshotToken(record.get(SCRIPT_EVENT_INGRESS_AUDIT.READ_SNAPSHOT_TOKEN));
    entity.setPayloadJson(record.get(SCRIPT_EVENT_INGRESS_AUDIT.PAYLOAD_JSON));
    Boolean admitted = record.get(SCRIPT_EVENT_INGRESS_AUDIT.ADMITTED);
    entity.setAdmitted(Boolean.TRUE.equals(admitted));
    entity.setAdmissionOutcome(record.get(SCRIPT_EVENT_INGRESS_AUDIT.ADMISSION_OUTCOME));
    entity.setAdmissionReason(record.get(SCRIPT_EVENT_INGRESS_AUDIT.ADMISSION_REASON));
    Integer resolvedHandlerCount = record.get(SCRIPT_EVENT_INGRESS_AUDIT.RESOLVED_HANDLER_COUNT);
    entity.setResolvedHandlerCount(resolvedHandlerCount == null ? 0 : resolvedHandlerCount);
    entity.setCreatedAt(toInstant(record.get(SCRIPT_EVENT_INGRESS_AUDIT.CREATED_AT)));
    Integer rowVersion = record.get(SCRIPT_EVENT_INGRESS_AUDIT.ROW_VERSION);
    entity.setRowVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
