package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventAudit.SCRIPT_EVENT_AUDIT;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.limitOrDefault;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.offsetOrZero;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static org.jooq.impl.DSL.field;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptEventAuditRecord;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectFieldOrAsterisk;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ScriptEventAuditRepository {
  private static final String SOURCE_KIND_SCHEDULE_TIMER = "SCHEDULE_TIMER";
  private static final String PIN_OWNER_EVIDENCE_CONFLICT_MESSAGE =
      "script_pin_control_plane_request_id conflicts with existing identity";
  private static final int MAX_HANDLER_IDENTITY_INSERT_ATTEMPTS = 2;
  private static final Field<Boolean> INSERTED_ROW =
      field("xmax = 0", Boolean.class).as("inserted");

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The inserted-or-existing audit is the repository result contract.")
  public record IdempotentInsertResult(ScriptEventAudit audit, boolean inserted) {}

  private final DSLContext dsl;

  public ScriptEventAuditRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  private static Condition handlerIdentityCondition(
      String tenantId,
      String gameInstanceId,
      String regionId,
      Long regionEpoch,
      String entityId,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      String pointerVersion,
      String scriptId,
      String pluginId,
      String pluginVersionId,
      String bindingId,
      String eventType,
      String eventSchemaVersion,
      String scriptPatchVersion,
      Long scriptPinEpoch,
      String scriptEventId,
      boolean dryRun) {
    Long normalizedScriptPinEpoch = normalizeScriptPinEpoch(scriptPinEpoch);
    String normalizedPluginId = normalizePluginIdentity(pluginId);
    String normalizedPluginVersionId = normalizePluginIdentity(pluginVersionId);
    String normalizedBindingId = normalizePluginIdentity(bindingId);
    return SCRIPT_EVENT_AUDIT
        .TENANT_ID
        .eq(tenantId)
        .and(SCRIPT_EVENT_AUDIT.GAME_INSTANCE_ID.eq(gameInstanceId))
        .and(SCRIPT_EVENT_AUDIT.REGION_ID.eq(regionId))
        .and(SCRIPT_EVENT_AUDIT.REGION_EPOCH.isNotDistinctFrom(regionEpoch))
        .and(SCRIPT_EVENT_AUDIT.ENTITY_ID.eq(entityId))
        .and(SCRIPT_EVENT_AUDIT.PLAYABLE_STATE_SCOPE.eq(playableStateScope))
        .and(SCRIPT_EVENT_AUDIT.WORLD_SLUG.eq(worldSlug))
        .and(SCRIPT_EVENT_AUDIT.REALM_SLUG.eq(realmSlug))
        .and(SCRIPT_EVENT_AUDIT.POINTER_VERSION.eq(pointerVersion))
        .and(SCRIPT_EVENT_AUDIT.SCRIPT_ID.eq(scriptId))
        .and(SCRIPT_EVENT_AUDIT.PLUGIN_ID.eq(normalizedPluginId))
        .and(SCRIPT_EVENT_AUDIT.PLUGIN_VERSION_ID.eq(normalizedPluginVersionId))
        .and(SCRIPT_EVENT_AUDIT.BINDING_ID.eq(normalizedBindingId))
        .and(SCRIPT_EVENT_AUDIT.EVENT_TYPE.eq(eventType))
        .and(SCRIPT_EVENT_AUDIT.EVENT_SCHEMA_VERSION.eq(eventSchemaVersion))
        .and(SCRIPT_EVENT_AUDIT.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion))
        .and(SCRIPT_EVENT_AUDIT.SCRIPT_PIN_EPOCH.isNotDistinctFrom(normalizedScriptPinEpoch))
        .and(SCRIPT_EVENT_AUDIT.SCRIPT_EVENT_ID.eq(scriptEventId))
        .and(SCRIPT_EVENT_AUDIT.DRY_RUN.eq(dryRun));
  }

  private static Condition handlerIdentityCondition(ScriptEventAudit entity) {
    return handlerIdentityCondition(
        entity.getTenantId(),
        entity.getGameInstanceId(),
        entity.getRegionId(),
        entity.getRegionEpoch(),
        entity.getEntityId(),
        entity.getPlayableStateScope(),
        entity.getWorldSlug(),
        entity.getRealmSlug(),
        entity.getPointerVersion(),
        entity.getScriptId(),
        entity.getPluginId(),
        entity.getPluginVersionId(),
        entity.getBindingId(),
        entity.getEventType(),
        entity.getEventSchemaVersion(),
        entity.getScriptPatchVersion(),
        entity.getScriptPinEpoch(),
        entity.getScriptEventId(),
        entity.isDryRun());
  }

  public boolean
      existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptPinEpochAndScriptPinControlPlaneRequestIdAndScriptEventIdAndDryRun(
          String tenantId,
          String gameInstanceId,
          String regionId,
          Long regionEpoch,
          String entityId,
          String playableStateScope,
          String worldSlug,
          String realmSlug,
          String pointerVersion,
          String scriptId,
          String eventType,
          String eventSchemaVersion,
          String scriptPatchVersion,
          Long scriptPinEpoch,
          String scriptPinControlPlaneRequestId,
          String scriptEventId,
          boolean dryRun) {
    return existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndPluginIdAndPluginVersionIdAndBindingIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptPinEpochAndScriptPinControlPlaneRequestIdAndScriptEventIdAndDryRun(
        tenantId,
        gameInstanceId,
        regionId,
        regionEpoch,
        entityId,
        playableStateScope,
        worldSlug,
        realmSlug,
        pointerVersion,
        scriptId,
        "",
        "",
        "",
        eventType,
        eventSchemaVersion,
        scriptPatchVersion,
        scriptPinEpoch,
        scriptPinControlPlaneRequestId,
        scriptEventId,
        dryRun);
  }

  public boolean
      existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndPluginIdAndPluginVersionIdAndBindingIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptPinEpochAndScriptPinControlPlaneRequestIdAndScriptEventIdAndDryRun(
          String tenantId,
          String gameInstanceId,
          String regionId,
          Long regionEpoch,
          String entityId,
          String playableStateScope,
          String worldSlug,
          String realmSlug,
          String pointerVersion,
          String scriptId,
          String pluginId,
          String pluginVersionId,
          String bindingId,
          String eventType,
          String eventSchemaVersion,
          String scriptPatchVersion,
          Long scriptPinEpoch,
          String scriptPinControlPlaneRequestId,
          String scriptEventId,
          boolean dryRun) {
    return dsl.fetchExists(
        SCRIPT_EVENT_AUDIT,
        handlerIdentityCondition(
                tenantId,
                gameInstanceId,
                regionId,
                regionEpoch,
                entityId,
                playableStateScope,
                worldSlug,
                realmSlug,
                pointerVersion,
                scriptId,
                pluginId,
                pluginVersionId,
                bindingId,
                eventType,
                eventSchemaVersion,
                scriptPatchVersion,
                scriptPinEpoch,
                scriptEventId,
                dryRun)
            .and(
                SCRIPT_EVENT_AUDIT.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID.isNotDistinctFrom(
                    blankToNull(scriptPinControlPlaneRequestId))));
  }

  /** Inserts a timer audit without raising a transaction-aborting uniqueness exception. */
  public IdempotentInsertResult insertIfAbsentByHandlerIdentity(ScriptEventAudit entity) {
    if (entity.getId() != null) {
      throw new IllegalArgumentException("A new script event audit is required");
    }
    Long normalizedScriptPinEpoch = normalizedScriptPinEpoch(entity);
    String normalizedScriptPinControlPlaneRequestId =
        blankToNull(entity.getScriptPinControlPlaneRequestId());
    requireCoherentPinTuple(normalizedScriptPinEpoch, normalizedScriptPinControlPlaneRequestId);
    for (int attempt = 0; attempt < MAX_HANDLER_IDENTITY_INSERT_ATTEMPTS; attempt++) {
      Optional<HandlerIdentityInsertResult> inserted = insertHandlerIdentity(entity);
      if (inserted.isPresent()) {
        HandlerIdentityInsertResult result = inserted.orElseThrow();
        if (!result.inserted()) {
          requireMatchingPinOwnerEvidence(
              normalizedScriptPinControlPlaneRequestId,
              result.audit().getScriptPinControlPlaneRequestId());
        }
        return new IdempotentInsertResult(result.audit(), result.inserted());
      }
      Optional<ScriptEventAudit> existing =
          dsl.selectFrom(SCRIPT_EVENT_AUDIT)
              .where(handlerIdentityCondition(entity))
              .fetchOptional(this::toEntity);
      if (existing.isPresent()) {
        requireMatchingPinOwnerEvidence(
            normalizedScriptPinControlPlaneRequestId,
            existing.orElseThrow().getScriptPinControlPlaneRequestId());
        return new IdempotentInsertResult(existing.orElseThrow(), false);
      }
    }
    throw new IllegalStateException("Audit identity conflict did not yield a row");
  }

  private Optional<HandlerIdentityInsertResult> insertHandlerIdentity(ScriptEventAudit entity) {
    Long normalizedScriptPinEpoch = normalizedScriptPinEpoch(entity);
    boolean pinned = normalizedScriptPinEpoch != null;
    ScriptEventAuditRecord record = dsl.newRecord(SCRIPT_EVENT_AUDIT);
    populate(record, entity, normalizedScriptPinEpoch);
    List<SelectFieldOrAsterisk> returningFields = new ArrayList<>();
    Collections.addAll(returningFields, SCRIPT_EVENT_AUDIT.fields());
    returningFields.add(INSERTED_ROW);
    // PostgreSQL waits for a concurrent unique-index winner before resolving
    // ON CONFLICT DO UPDATE. Returning xmax distinguishes the inserted row
    // from the existing row returned by the no-op conflict update, avoiding a
    // race between DO NOTHING and a separate readback query.
    return dsl.insertInto(SCRIPT_EVENT_AUDIT)
        .set(record)
        .onConflict(handlerConflictFields(pinned))
        .where(
            pinned
                ? SCRIPT_EVENT_AUDIT.SCRIPT_PIN_EPOCH.gt(0L)
                : SCRIPT_EVENT_AUDIT.SCRIPT_PIN_EPOCH.isNull())
        .doUpdate()
        .set(SCRIPT_EVENT_AUDIT.ID, SCRIPT_EVENT_AUDIT.ID)
        .returningResult(returningFields)
        .fetchOptional(
            returned ->
                new HandlerIdentityInsertResult(
                    toEntity(returned), Boolean.TRUE.equals(returned.get(INSERTED_ROW))));
  }

  private record HandlerIdentityInsertResult(ScriptEventAudit audit, boolean inserted) {}

  private static Field<?>[] handlerConflictFields(boolean pinned) {
    List<Field<?>> fields =
        new ArrayList<>(
            List.of(
                SCRIPT_EVENT_AUDIT.TENANT_ID,
                SCRIPT_EVENT_AUDIT.GAME_INSTANCE_ID,
                SCRIPT_EVENT_AUDIT.REGION_ID,
                SCRIPT_EVENT_AUDIT.REGION_EPOCH,
                SCRIPT_EVENT_AUDIT.ENTITY_ID,
                SCRIPT_EVENT_AUDIT.PLAYABLE_STATE_SCOPE,
                SCRIPT_EVENT_AUDIT.WORLD_SLUG,
                SCRIPT_EVENT_AUDIT.REALM_SLUG,
                SCRIPT_EVENT_AUDIT.POINTER_VERSION,
                SCRIPT_EVENT_AUDIT.SCRIPT_ID,
                SCRIPT_EVENT_AUDIT.PLUGIN_ID,
                SCRIPT_EVENT_AUDIT.PLUGIN_VERSION_ID,
                SCRIPT_EVENT_AUDIT.BINDING_ID,
                SCRIPT_EVENT_AUDIT.EVENT_TYPE,
                SCRIPT_EVENT_AUDIT.EVENT_SCHEMA_VERSION,
                SCRIPT_EVENT_AUDIT.SCRIPT_PATCH_VERSION));
    if (pinned) {
      fields.add(SCRIPT_EVENT_AUDIT.SCRIPT_PIN_EPOCH);
    }
    fields.add(SCRIPT_EVENT_AUDIT.SCRIPT_EVENT_ID);
    fields.add(SCRIPT_EVENT_AUDIT.DRY_RUN);
    return fields.toArray(Field<?>[]::new);
  }

  public Optional<ScriptEventAudit> findByWorkItemId(Long workItemId) {
    return dsl.selectFrom(SCRIPT_EVENT_AUDIT)
        .where(SCRIPT_EVENT_AUDIT.WORK_ITEM_ID.eq(workItemId))
        .fetchOptional(this::toEntity);
  }

  public List<ScriptEventAudit> findTimerAuditEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      Long scriptPinEpoch,
      String scriptPinControlPlaneRequestId,
      String scriptId,
      String eventType,
      String finalReason,
      Instant changedAfter,
      Instant changedBefore,
      Pageable pageable) {
    Long normalizedScriptPinEpoch = normalizeScriptPinEpoch(scriptPinEpoch);
    String normalizedScriptPinControlPlaneRequestId = blankToNull(scriptPinControlPlaneRequestId);
    Condition condition =
        SCRIPT_EVENT_AUDIT
            .TENANT_ID
            .eq(tenantId)
            .and(SCRIPT_EVENT_AUDIT.SOURCE_KIND.eq(SOURCE_KIND_SCHEDULE_TIMER));
    if (!gameInstanceId.isBlank()) {
      condition = condition.and(SCRIPT_EVENT_AUDIT.GAME_INSTANCE_ID.eq(gameInstanceId));
    }
    if (!scriptPatchVersion.isBlank()) {
      condition = condition.and(SCRIPT_EVENT_AUDIT.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion));
    }
    if (normalizedScriptPinEpoch != null) {
      condition = condition.and(SCRIPT_EVENT_AUDIT.SCRIPT_PIN_EPOCH.eq(normalizedScriptPinEpoch));
    }
    if (normalizedScriptPinControlPlaneRequestId != null) {
      condition =
          condition.and(
              SCRIPT_EVENT_AUDIT.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID.isNotDistinctFrom(
                  normalizedScriptPinControlPlaneRequestId));
    }
    if (!scriptId.isBlank()) {
      condition = condition.and(SCRIPT_EVENT_AUDIT.SCRIPT_ID.eq(scriptId));
    }
    if (!eventType.isBlank()) {
      condition = condition.and(SCRIPT_EVENT_AUDIT.EVENT_TYPE.eq(eventType));
    }
    if (!finalReason.isBlank()) {
      condition = condition.and(SCRIPT_EVENT_AUDIT.FINAL_REASON.eq(finalReason));
    }
    if (changedAfter != null) {
      condition = condition.and(SCRIPT_EVENT_AUDIT.UPDATED_AT.gt(toLocalDateTime(changedAfter)));
    }
    if (changedBefore != null) {
      condition = condition.and(SCRIPT_EVENT_AUDIT.UPDATED_AT.lt(toLocalDateTime(changedBefore)));
    }
    return dsl.selectFrom(SCRIPT_EVENT_AUDIT)
        .where(condition)
        .orderBy(SCRIPT_EVENT_AUDIT.UPDATED_AT.desc(), SCRIPT_EVENT_AUDIT.ID.desc())
        .limit(limitOrDefault(pageable, 100))
        .offset(offsetOrZero(pageable))
        .fetch(this::toEntity);
  }

  public List<ScriptEventAudit> findTimerAuditEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      String scriptId,
      String eventType,
      String finalReason,
      Instant changedAfter,
      Instant changedBefore,
      Pageable pageable) {
    return findTimerAuditEvents(
        tenantId,
        gameInstanceId,
        scriptPatchVersion,
        null,
        null,
        scriptId,
        eventType,
        finalReason,
        changedAfter,
        changedBefore,
        pageable);
  }

  public ScriptEventAudit save(ScriptEventAudit entity) {
    if (entity.getId() == null) {
      return insertIfAbsentByHandlerIdentity(entity).audit();
    }
    Long normalizedScriptPinEpoch = normalizedScriptPinEpoch(entity);
    String normalizedScriptPinControlPlaneRequestId =
        blankToNull(entity.getScriptPinControlPlaneRequestId());
    requireCoherentPinTuple(normalizedScriptPinEpoch, normalizedScriptPinControlPlaneRequestId);
    int nextRowVersion = entity.getRowVersion() + 1;
    int updated =
        dsl.update(SCRIPT_EVENT_AUDIT)
            .set(SCRIPT_EVENT_AUDIT.TENANT_ID, entity.getTenantId())
            .set(SCRIPT_EVENT_AUDIT.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(SCRIPT_EVENT_AUDIT.REGION_ID, entity.getRegionId())
            .set(SCRIPT_EVENT_AUDIT.REGION_EPOCH, entity.getRegionEpoch())
            .set(SCRIPT_EVENT_AUDIT.ENTITY_ID, entity.getEntityId())
            .set(SCRIPT_EVENT_AUDIT.PLAYABLE_STATE_SCOPE, entity.getPlayableStateScope())
            .set(SCRIPT_EVENT_AUDIT.WORLD_SLUG, entity.getWorldSlug())
            .set(SCRIPT_EVENT_AUDIT.REALM_SLUG, entity.getRealmSlug())
            .set(SCRIPT_EVENT_AUDIT.POINTER_VERSION, entity.getPointerVersion())
            .set(SCRIPT_EVENT_AUDIT.SCRIPT_ID, entity.getScriptId())
            .set(SCRIPT_EVENT_AUDIT.BINDING_ID, normalizePluginIdentity(entity.getBindingId()))
            .set(SCRIPT_EVENT_AUDIT.PLUGIN_ID, entity.getPluginId())
            .set(SCRIPT_EVENT_AUDIT.PLUGIN_VERSION_ID, entity.getPluginVersionId())
            .set(SCRIPT_EVENT_AUDIT.TARGET_SCOPE_TYPE, entity.getTargetScopeType())
            .set(SCRIPT_EVENT_AUDIT.TARGET_SCOPE_ID, entity.getTargetScopeId())
            .set(SCRIPT_EVENT_AUDIT.SCRIPT_PIN_EPOCH, normalizedScriptPinEpoch)
            .set(
                SCRIPT_EVENT_AUDIT.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID,
                normalizedScriptPinControlPlaneRequestId)
            .set(SCRIPT_EVENT_AUDIT.EVENT_TYPE, entity.getEventType())
            .set(SCRIPT_EVENT_AUDIT.EVENT_SCHEMA_VERSION, entity.getEventSchemaVersion())
            .set(SCRIPT_EVENT_AUDIT.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
            .set(SCRIPT_EVENT_AUDIT.SCRIPT_EVENT_ID, entity.getScriptEventId())
            .set(SCRIPT_EVENT_AUDIT.DRY_RUN, entity.isDryRun())
            .set(SCRIPT_EVENT_AUDIT.SOURCE_SERVICE, entity.getSourceService())
            .set(SCRIPT_EVENT_AUDIT.TRIGGER_MODE, entity.getTriggerMode())
            .set(SCRIPT_EVENT_AUDIT.SOURCE_KIND, entity.getSourceKind())
            .set(SCRIPT_EVENT_AUDIT.SOURCE_STATE, entity.getSourceState())
            .set(SCRIPT_EVENT_AUDIT.SOURCE_ORDINAL, entity.getSourceOrdinal())
            .set(SCRIPT_EVENT_AUDIT.SOURCE_DUE_TICK_ID, entity.getSourceDueTickId())
            .set(SCRIPT_EVENT_AUDIT.SOURCE_DUE_AT_MS, entity.getSourceDueAtMs())
            .set(SCRIPT_EVENT_AUDIT.WORK_ITEM_ID, entity.getWorkItemId())
            .set(SCRIPT_EVENT_AUDIT.FINAL_STAGE, entity.getFinalStage())
            .set(SCRIPT_EVENT_AUDIT.FINAL_OUTCOME, entity.getFinalOutcome())
            .set(SCRIPT_EVENT_AUDIT.FINAL_REASON, entity.getFinalReason())
            .set(SCRIPT_EVENT_AUDIT.CREATED_AT, toLocalDateTime(entity.getCreatedAt()))
            .set(SCRIPT_EVENT_AUDIT.UPDATED_AT, toLocalDateTime(entity.getUpdatedAt()))
            .set(SCRIPT_EVENT_AUDIT.ROW_VERSION, nextRowVersion)
            .where(
                SCRIPT_EVENT_AUDIT
                    .ID
                    .eq(entity.getId())
                    .and(SCRIPT_EVENT_AUDIT.ROW_VERSION.eq(entity.getRowVersion()))
                    .and(SCRIPT_EVENT_AUDIT.SCRIPT_PATCH_VERSION.eq(entity.getScriptPatchVersion()))
                    .and(
                        SCRIPT_EVENT_AUDIT.SCRIPT_PIN_EPOCH.isNotDistinctFrom(
                            normalizedScriptPinEpoch))
                    .and(
                        SCRIPT_EVENT_AUDIT.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID.isNotDistinctFrom(
                            normalizedScriptPinControlPlaneRequestId)))
            .execute();
    if (updated != 1) {
      Optional<ScriptEventAudit> existing = findById(entity.getId());
      if (existing.isPresent()) {
        ScriptEventAudit existingAudit = existing.get();
        if (existingAudit.getRowVersion() == entity.getRowVersion()
            && Objects.equals(existingAudit.getScriptPatchVersion(), entity.getScriptPatchVersion())
            && Objects.equals(
                normalizeScriptPinEpoch(existingAudit.getScriptPinEpoch()),
                normalizedScriptPinEpoch)) {
          requireMatchingPinOwnerEvidence(
              normalizedScriptPinControlPlaneRequestId,
              existingAudit.getScriptPinControlPlaneRequestId());
        }
      }
      throw AutomationScriptingJooqRepositorySupport.staleWrite(
          "script_event_audit", entity.getId());
    }
    entity.setRowVersion(nextRowVersion);
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<ScriptEventAudit> findById(Long id) {
    return dsl.selectFrom(SCRIPT_EVENT_AUDIT)
        .where(SCRIPT_EVENT_AUDIT.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(
      ScriptEventAuditRecord record, ScriptEventAudit entity, Long normalizedScriptPinEpoch) {
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
    record.setBindingId(normalizePluginIdentity(entity.getBindingId()));
    record.setPluginId(normalizePluginIdentity(entity.getPluginId()));
    record.setPluginVersionId(normalizePluginIdentity(entity.getPluginVersionId()));
    record.setTargetScopeType(entity.getTargetScopeType());
    record.setTargetScopeId(entity.getTargetScopeId());
    record.setScriptPinEpoch(normalizedScriptPinEpoch);
    record.setScriptPinControlPlaneRequestId(
        blankToNull(entity.getScriptPinControlPlaneRequestId()));
    record.setEventType(entity.getEventType());
    record.setEventSchemaVersion(entity.getEventSchemaVersion());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setScriptEventId(entity.getScriptEventId());
    record.setDryRun(entity.isDryRun());
    record.setSourceService(entity.getSourceService());
    record.setTriggerMode(entity.getTriggerMode());
    record.setSourceKind(entity.getSourceKind());
    record.setSourceState(entity.getSourceState());
    record.setSourceOrdinal(entity.getSourceOrdinal());
    record.setSourceDueTickId(entity.getSourceDueTickId());
    record.setSourceDueAtMs(entity.getSourceDueAtMs());
    record.setWorkItemId(entity.getWorkItemId());
    record.setFinalStage(entity.getFinalStage());
    record.setFinalOutcome(entity.getFinalOutcome());
    record.setFinalReason(entity.getFinalReason());
    record.setCreatedAt(toLocalDateTime(entity.getCreatedAt()));
    record.setUpdatedAt(toLocalDateTime(entity.getUpdatedAt()));
    record.setRowVersion(entity.getRowVersion());
  }

  private ScriptEventAudit toEntity(Record record) {
    ScriptEventAudit entity = new ScriptEventAudit();
    entity.setId(record.get(SCRIPT_EVENT_AUDIT.ID));
    entity.setTenantId(record.get(SCRIPT_EVENT_AUDIT.TENANT_ID));
    entity.setGameInstanceId(record.get(SCRIPT_EVENT_AUDIT.GAME_INSTANCE_ID));
    entity.setRegionId(record.get(SCRIPT_EVENT_AUDIT.REGION_ID));
    entity.setRegionEpoch(record.get(SCRIPT_EVENT_AUDIT.REGION_EPOCH));
    entity.setEntityId(record.get(SCRIPT_EVENT_AUDIT.ENTITY_ID));
    entity.setPlayableStateScope(record.get(SCRIPT_EVENT_AUDIT.PLAYABLE_STATE_SCOPE));
    entity.setWorldSlug(record.get(SCRIPT_EVENT_AUDIT.WORLD_SLUG));
    entity.setRealmSlug(record.get(SCRIPT_EVENT_AUDIT.REALM_SLUG));
    entity.setPointerVersion(record.get(SCRIPT_EVENT_AUDIT.POINTER_VERSION));
    entity.setScriptId(record.get(SCRIPT_EVENT_AUDIT.SCRIPT_ID));
    entity.setBindingId(normalizePluginIdentity(record.get(SCRIPT_EVENT_AUDIT.BINDING_ID)));
    entity.setPluginId(normalizePluginIdentity(record.get(SCRIPT_EVENT_AUDIT.PLUGIN_ID)));
    entity.setPluginVersionId(
        normalizePluginIdentity(record.get(SCRIPT_EVENT_AUDIT.PLUGIN_VERSION_ID)));
    entity.setTargetScopeType(record.get(SCRIPT_EVENT_AUDIT.TARGET_SCOPE_TYPE));
    entity.setTargetScopeId(record.get(SCRIPT_EVENT_AUDIT.TARGET_SCOPE_ID));
    Long scriptPinEpoch = record.get(SCRIPT_EVENT_AUDIT.SCRIPT_PIN_EPOCH);
    entity.setScriptPinEpoch(normalizeScriptPinEpoch(scriptPinEpoch));
    entity.setScriptPinControlPlaneRequestId(
        blankToNull(record.get(SCRIPT_EVENT_AUDIT.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID)));
    entity.setEventType(record.get(SCRIPT_EVENT_AUDIT.EVENT_TYPE));
    entity.setEventSchemaVersion(record.get(SCRIPT_EVENT_AUDIT.EVENT_SCHEMA_VERSION));
    entity.setScriptPatchVersion(record.get(SCRIPT_EVENT_AUDIT.SCRIPT_PATCH_VERSION));
    entity.setScriptEventId(record.get(SCRIPT_EVENT_AUDIT.SCRIPT_EVENT_ID));
    Boolean dryRun = record.get(SCRIPT_EVENT_AUDIT.DRY_RUN);
    entity.setDryRun(Boolean.TRUE.equals(dryRun));
    entity.setSourceService(record.get(SCRIPT_EVENT_AUDIT.SOURCE_SERVICE));
    entity.setTriggerMode(record.get(SCRIPT_EVENT_AUDIT.TRIGGER_MODE));
    entity.setSourceKind(record.get(SCRIPT_EVENT_AUDIT.SOURCE_KIND));
    entity.setSourceState(record.get(SCRIPT_EVENT_AUDIT.SOURCE_STATE));
    entity.setSourceOrdinal(record.get(SCRIPT_EVENT_AUDIT.SOURCE_ORDINAL));
    entity.setSourceDueTickId(record.get(SCRIPT_EVENT_AUDIT.SOURCE_DUE_TICK_ID));
    entity.setSourceDueAtMs(record.get(SCRIPT_EVENT_AUDIT.SOURCE_DUE_AT_MS));
    entity.setWorkItemId(record.get(SCRIPT_EVENT_AUDIT.WORK_ITEM_ID));
    entity.setFinalStage(record.get(SCRIPT_EVENT_AUDIT.FINAL_STAGE));
    entity.setFinalOutcome(record.get(SCRIPT_EVENT_AUDIT.FINAL_OUTCOME));
    entity.setFinalReason(record.get(SCRIPT_EVENT_AUDIT.FINAL_REASON));
    entity.setCreatedAt(toInstant(record.get(SCRIPT_EVENT_AUDIT.CREATED_AT)));
    entity.setUpdatedAt(toInstant(record.get(SCRIPT_EVENT_AUDIT.UPDATED_AT)));
    Integer rowVersion = record.get(SCRIPT_EVENT_AUDIT.ROW_VERSION);
    entity.setRowVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static void requireMatchingPinOwnerEvidence(
      String requestedRequestId, String existingRequestId) {
    if (!Objects.equals(requestedRequestId, blankToNull(existingRequestId))) {
      throw new IllegalStateException(PIN_OWNER_EVIDENCE_CONFLICT_MESSAGE);
    }
  }

  private static Long normalizedScriptPinEpoch(ScriptEventAudit entity) {
    return normalizeScriptPinEpoch(entity.getScriptPinEpoch());
  }

  private static void requireCoherentPinTuple(Long scriptPinEpoch, String requestId) {
    if ((scriptPinEpoch != null) != (requestId != null)) {
      throw new IllegalArgumentException(
          "script_pin_control_plane_request_id is required exactly when script_pin_epoch is positive");
    }
  }

  private static Long normalizeScriptPinEpoch(Long epoch) {
    if (epoch == null || epoch == 0L) {
      return null;
    }
    if (epoch < 0L) {
      throw new IllegalArgumentException("script_pin_epoch must be non-negative");
    }
    return epoch;
  }

  private static String normalizePluginIdentity(String value) {
    return value == null ? "" : value;
  }
}
