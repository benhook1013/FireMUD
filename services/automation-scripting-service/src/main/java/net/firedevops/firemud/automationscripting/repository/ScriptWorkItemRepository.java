package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventAudit.SCRIPT_EVENT_AUDIT;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptHandoffEvents.SCRIPT_HANDOFF_EVENTS;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptWorkItems.SCRIPT_WORK_ITEMS;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.blankToNull;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.limitOrDefault;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.offsetOrZero;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static org.jooq.impl.DSL.field;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptWorkItemsRecord;
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
public class ScriptWorkItemRepository {
  private static final int MAX_TRIGGER_IDENTITY_INSERT_ATTEMPTS = 2;
  private static final String PIN_OWNER_EVIDENCE_CONFLICT_MESSAGE =
      "script_pin_control_plane_request_id conflicts with existing identity";
  private static final Field<Boolean> INSERTED_ROW =
      field("xmax = 0", Boolean.class).as("inserted");

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The inserted-or-existing work item is the repository result contract.")
  public record IdempotentInsertResult(ScriptWorkItem workItem, boolean inserted) {}

  public interface ScriptPatchInstanceProjection {
    String getGameInstanceId();

    String getScriptPatchVersion();
  }

  private final DSLContext dsl;

  public ScriptWorkItemRepository(DSLContext dsl) {
    this.dsl = dsl;
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
          long scriptPinEpoch,
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
          long scriptPinEpoch,
          String scriptPinControlPlaneRequestId,
          String scriptEventId,
          boolean dryRun) {
    return dsl.fetchExists(
        SCRIPT_WORK_ITEMS,
        triggerIdentityCondition(
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
                SCRIPT_WORK_ITEMS.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID.isNotDistinctFrom(
                    blankToNull(scriptPinControlPlaneRequestId))));
  }

  public boolean existsByTenantIdAndScriptIdAndStatusIn(
      String tenantId, String scriptId, Collection<String> statuses) {
    return dsl.fetchExists(
        SCRIPT_WORK_ITEMS,
        SCRIPT_WORK_ITEMS
            .TENANT_ID
            .eq(tenantId)
            .and(SCRIPT_WORK_ITEMS.SCRIPT_ID.eq(scriptId))
            .and(SCRIPT_WORK_ITEMS.STATUS.in(statuses)));
  }

  public List<ScriptWorkItem>
      findByTenantIdAndScriptPatchVersionAndStatusInOrderByCreatedAtAscIdAsc(
          String tenantId, String scriptPatchVersion, Collection<String> statuses) {
    return fetchMany(
        SCRIPT_WORK_ITEMS
            .TENANT_ID
            .eq(tenantId)
            .and(SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion))
            .and(SCRIPT_WORK_ITEMS.STATUS.in(statuses)),
        SCRIPT_WORK_ITEMS.CREATED_AT.asc(),
        SCRIPT_WORK_ITEMS.ID.asc());
  }

  public List<ScriptWorkItem>
      findByTenantIdAndPluginIdAndPluginVersionIdAndStatusInOrderByCreatedAtAscIdAsc(
          String tenantId, String pluginId, String pluginVersionId, Collection<String> statuses) {
    return fetchMany(
        SCRIPT_WORK_ITEMS
            .TENANT_ID
            .eq(tenantId)
            .and(SCRIPT_WORK_ITEMS.PLUGIN_ID.eq(normalizePluginIdentity(pluginId)))
            .and(SCRIPT_WORK_ITEMS.PLUGIN_VERSION_ID.eq(normalizePluginIdentity(pluginVersionId)))
            .and(SCRIPT_WORK_ITEMS.STATUS.in(statuses)),
        SCRIPT_WORK_ITEMS.CREATED_AT.asc(),
        SCRIPT_WORK_ITEMS.ID.asc());
  }

  public List<ScriptWorkItem> findByTenantIdAndScriptPatchVersion(
      String tenantId, String scriptPatchVersion) {
    return fetchMany(
        SCRIPT_WORK_ITEMS
            .TENANT_ID
            .eq(tenantId)
            .and(SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion)),
        SCRIPT_WORK_ITEMS.CREATED_AT.asc(),
        SCRIPT_WORK_ITEMS.ID.asc());
  }

  public List<ScriptWorkItem> findByTenantIdAndEventTypeAndStatusInOrderByCreatedAtAscIdAsc(
      String tenantId, String eventType, Collection<String> statuses) {
    return fetchMany(
        SCRIPT_WORK_ITEMS
            .TENANT_ID
            .eq(tenantId)
            .and(SCRIPT_WORK_ITEMS.EVENT_TYPE.eq(eventType))
            .and(SCRIPT_WORK_ITEMS.STATUS.in(statuses)),
        SCRIPT_WORK_ITEMS.CREATED_AT.asc(),
        SCRIPT_WORK_ITEMS.ID.asc());
  }

  public List<ScriptWorkItem> findByTenantIdAndGameInstanceIdAndScriptPatchVersion(
      String tenantId, String gameInstanceId, String scriptPatchVersion) {
    return fetchMany(
        SCRIPT_WORK_ITEMS
            .TENANT_ID
            .eq(tenantId)
            .and(SCRIPT_WORK_ITEMS.GAME_INSTANCE_ID.eq(gameInstanceId))
            .and(SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion)),
        SCRIPT_WORK_ITEMS.CREATED_AT.asc(),
        SCRIPT_WORK_ITEMS.ID.asc());
  }

  public List<ScriptWorkItem> findByScopeAndStatusesOrderByCreatedAtAscIdAsc(
      String tenantId, String gameInstanceId, String regionId, Collection<String> statuses) {
    Condition condition =
        SCRIPT_WORK_ITEMS
            .TENANT_ID
            .eq(tenantId)
            .and(SCRIPT_WORK_ITEMS.GAME_INSTANCE_ID.eq(gameInstanceId))
            .and(SCRIPT_WORK_ITEMS.REGION_ID.eq(regionId))
            .and(SCRIPT_WORK_ITEMS.STATUS.in(statuses));
    return fetchMany(condition, SCRIPT_WORK_ITEMS.CREATED_AT.asc(), SCRIPT_WORK_ITEMS.ID.asc());
  }

  public List<String> findDistinctScriptPatchVersionsByTenantId(String tenantId) {
    return dsl.selectDistinct(SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION)
        .from(SCRIPT_WORK_ITEMS)
        .where(SCRIPT_WORK_ITEMS.TENANT_ID.eq(tenantId))
        .fetch(SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION);
  }

  public List<ScriptPatchInstanceProjection> findDistinctInstancePatchPairs(
      String tenantId, String gameInstanceId, String scriptPatchVersion) {
    Condition condition = SCRIPT_WORK_ITEMS.TENANT_ID.eq(tenantId);
    if (!gameInstanceId.isBlank()) {
      condition = condition.and(SCRIPT_WORK_ITEMS.GAME_INSTANCE_ID.eq(gameInstanceId));
    }
    if (!scriptPatchVersion.isBlank()) {
      condition = condition.and(SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion));
    }
    return dsl.selectDistinct(
            SCRIPT_WORK_ITEMS.GAME_INSTANCE_ID, SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION)
        .from(SCRIPT_WORK_ITEMS)
        .where(condition)
        .fetch(
            record ->
                new ScriptPatchInstanceProjectionView(
                    record.get(SCRIPT_WORK_ITEMS.GAME_INSTANCE_ID),
                    record.get(SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION)));
  }

  public List<ScriptWorkItem> findByStatusOrderByCreatedAtAscIdAsc(
      String status, Pageable pageable) {
    return fetchManyPaged(
        SCRIPT_WORK_ITEMS.STATUS.eq(status),
        pageable,
        SCRIPT_WORK_ITEMS.CREATED_AT.asc(),
        SCRIPT_WORK_ITEMS.ID.asc());
  }

  public List<ScriptWorkItem> findByIdInAndStatusOrderByCreatedAtAscIdAsc(
      Collection<Long> ids, String status, Pageable pageable) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return fetchManyPaged(
        SCRIPT_WORK_ITEMS.ID.in(ids).and(SCRIPT_WORK_ITEMS.STATUS.eq(status)),
        pageable,
        SCRIPT_WORK_ITEMS.CREATED_AT.asc(),
        SCRIPT_WORK_ITEMS.ID.asc());
  }

  public List<ScriptWorkItem> findByStatusInOrderByCreatedAtAscIdAsc(
      Collection<String> statuses, Pageable pageable) {
    return fetchManyPaged(
        SCRIPT_WORK_ITEMS.STATUS.in(statuses),
        pageable,
        SCRIPT_WORK_ITEMS.CREATED_AT.asc(),
        SCRIPT_WORK_ITEMS.ID.asc());
  }

  public List<ScriptWorkItem> findByStatusOrderByUpdatedAtAscIdAsc(
      String status, Pageable pageable) {
    return fetchManyPaged(
        SCRIPT_WORK_ITEMS.STATUS.eq(status),
        pageable,
        SCRIPT_WORK_ITEMS.UPDATED_AT.asc(),
        SCRIPT_WORK_ITEMS.ID.asc());
  }

  public List<ScriptWorkItem> findByTenantIdAndStatusOrderByUpdatedAtDescIdDesc(
      String tenantId, String status, Pageable pageable) {
    return fetchManyPaged(
        SCRIPT_WORK_ITEMS.TENANT_ID.eq(tenantId).and(SCRIPT_WORK_ITEMS.STATUS.eq(status)),
        pageable,
        SCRIPT_WORK_ITEMS.UPDATED_AT.desc(),
        SCRIPT_WORK_ITEMS.ID.desc());
  }

  public List<ScriptWorkItem> findDeadLettersByTenantIdAndFiltersOrderByUpdatedAtDescIdDesc(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      String status,
      Pageable pageable) {
    Condition condition =
        SCRIPT_WORK_ITEMS.TENANT_ID.eq(tenantId).and(SCRIPT_WORK_ITEMS.STATUS.eq(status));
    if (gameInstanceId != null && !gameInstanceId.isBlank()) {
      condition = condition.and(SCRIPT_WORK_ITEMS.GAME_INSTANCE_ID.eq(gameInstanceId));
    }
    if (scriptPatchVersion != null && !scriptPatchVersion.isBlank()) {
      condition = condition.and(SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion));
    }
    return fetchManyPaged(
        condition, pageable, SCRIPT_WORK_ITEMS.UPDATED_AT.desc(), SCRIPT_WORK_ITEMS.ID.desc());
  }

  public long countByStatus(String status) {
    return dsl.fetchCount(SCRIPT_WORK_ITEMS, SCRIPT_WORK_ITEMS.STATUS.eq(status));
  }

  /**
   * Deletes eligible rows while retaining the eligibility decision through child and parent
   * deletion. The caller must invoke this inside a transaction so the row locks remain held.
   */
  public long deleteByStatusAndUpdatedAtBefore(String status, Instant updatedAt) {
    if ("DEAD_LETTERED".equals(status)) {
      // The live schema has no recovery aggregate, generation/claim state, expected-child ledger,
      // or evidence-retention horizons. Deleting a dead-letter parent cannot prove that its
      // recovery and supporting evidence are terminal and retention-eligible.
      return 0L;
    }
    Condition eligibility = cleanupEligibility(status, updatedAt);
    List<Long> ids =
        dsl.select(SCRIPT_WORK_ITEMS.ID)
            .from(SCRIPT_WORK_ITEMS)
            .where(eligibility)
            // Lock the eligibility decision until child evidence and the parent are deleted so a
            // replay cannot revive a row after this select but before the delete.
            .forUpdate()
            .fetch(SCRIPT_WORK_ITEMS.ID);
    return deleteByIds(ids, eligibility);
  }

  /**
   * Deletes the oldest rows for a status while retaining the status eligibility decision through
   * child and parent deletion. The caller must invoke this inside a transaction; row locks are
   * deliberately held until the child evidence and parent rows are disposed.
   */
  public long deleteOldestByStatus(String status, int limit) {
    if (limit <= 0 || "DEAD_LETTERED".equals(status)) {
      // Row-cap cleanup uses the same fail-closed gate as age cleanup.
      return 0L;
    }
    List<Long> ids =
        dsl.select(SCRIPT_WORK_ITEMS.ID)
            .from(SCRIPT_WORK_ITEMS)
            .where(cleanupEligibility(status, null))
            .orderBy(SCRIPT_WORK_ITEMS.UPDATED_AT.asc(), SCRIPT_WORK_ITEMS.ID.asc())
            .limit(limit)
            .forUpdate()
            .fetch(SCRIPT_WORK_ITEMS.ID);
    return deleteByIds(ids, cleanupEligibility(status, null));
  }

  public List<ScriptWorkItem> findAllById(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return dsl.selectFrom(SCRIPT_WORK_ITEMS)
        .where(SCRIPT_WORK_ITEMS.ID.in(ids))
        .fetch(this::toEntity);
  }

  public ScriptWorkItem save(ScriptWorkItem entity) {
    if (entity.getId() == null) {
      return insertIfAbsentByTriggerIdentity(entity).workItem();
    }
    int nextRowVersion = entity.getRowVersion() + 1;
    int updated =
        dsl.update(SCRIPT_WORK_ITEMS)
            .set(SCRIPT_WORK_ITEMS.TENANT_ID, entity.getTenantId())
            .set(SCRIPT_WORK_ITEMS.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(SCRIPT_WORK_ITEMS.REGION_ID, entity.getRegionId())
            .set(SCRIPT_WORK_ITEMS.REGION_EPOCH, entity.getRegionEpoch())
            .set(SCRIPT_WORK_ITEMS.ENTITY_ID, entity.getEntityId())
            .set(SCRIPT_WORK_ITEMS.PLAYABLE_STATE_SCOPE, entity.getPlayableStateScope())
            .set(SCRIPT_WORK_ITEMS.WORLD_SLUG, entity.getWorldSlug())
            .set(SCRIPT_WORK_ITEMS.REALM_SLUG, entity.getRealmSlug())
            .set(SCRIPT_WORK_ITEMS.POINTER_VERSION, entity.getPointerVersion())
            .set(SCRIPT_WORK_ITEMS.SCRIPT_ID, entity.getScriptId())
            .set(SCRIPT_WORK_ITEMS.BINDING_ID, normalizePluginIdentity(entity.getBindingId()))
            .set(SCRIPT_WORK_ITEMS.PLUGIN_ID, normalizePluginIdentity(entity.getPluginId()))
            .set(
                SCRIPT_WORK_ITEMS.PLUGIN_VERSION_ID,
                normalizePluginIdentity(entity.getPluginVersionId()))
            .set(SCRIPT_WORK_ITEMS.TARGET_SCOPE_TYPE, entity.getTargetScopeType())
            .set(SCRIPT_WORK_ITEMS.TARGET_SCOPE_ID, entity.getTargetScopeId())
            .set(SCRIPT_WORK_ITEMS.EVENT_TYPE, entity.getEventType())
            .set(SCRIPT_WORK_ITEMS.EVENT_SCHEMA_VERSION, entity.getEventSchemaVersion())
            .set(SCRIPT_WORK_ITEMS.QUOTA_CLASS, entity.getQuotaClass())
            .set(SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
            .set(SCRIPT_WORK_ITEMS.SCRIPT_PIN_EPOCH, entity.getScriptPinEpoch())
            .set(
                SCRIPT_WORK_ITEMS.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID,
                normalizedScriptPinControlPlaneRequestId(entity))
            .set(SCRIPT_WORK_ITEMS.SCRIPT_EVENT_ID, entity.getScriptEventId())
            .set(SCRIPT_WORK_ITEMS.DRY_RUN, entity.isDryRun())
            .set(SCRIPT_WORK_ITEMS.SOURCE_SERVICE, entity.getSourceService())
            .set(SCRIPT_WORK_ITEMS.TRIGGER_MODE, entity.getTriggerMode())
            .set(SCRIPT_WORK_ITEMS.SOURCE_KIND, entity.getSourceKind())
            .set(SCRIPT_WORK_ITEMS.SOURCE_STATE, entity.getSourceState())
            .set(SCRIPT_WORK_ITEMS.SOURCE_ORDINAL, entity.getSourceOrdinal())
            .set(SCRIPT_WORK_ITEMS.SOURCE_DUE_TICK_ID, entity.getSourceDueTickId())
            .set(SCRIPT_WORK_ITEMS.SOURCE_DUE_AT_MS, entity.getSourceDueAtMs())
            .set(SCRIPT_WORK_ITEMS.PRIORITY_TAG, entity.getPriorityTag())
            .set(SCRIPT_WORK_ITEMS.READ_SNAPSHOT_TOKEN, entity.getReadSnapshotToken())
            .set(SCRIPT_WORK_ITEMS.PAYLOAD_JSON, entity.getPayloadJson())
            .set(SCRIPT_WORK_ITEMS.ADMISSION_EPOCH, entity.getAdmissionEpoch())
            .set(SCRIPT_WORK_ITEMS.STATUS, entity.getStatus())
            .set(SCRIPT_WORK_ITEMS.CANCEL_REASON, entity.getCancelReason())
            .set(SCRIPT_WORK_ITEMS.CREATED_AT, toLocalDateTime(entity.getCreatedAt()))
            .set(SCRIPT_WORK_ITEMS.UPDATED_AT, toLocalDateTime(entity.getUpdatedAt()))
            .set(SCRIPT_WORK_ITEMS.ROW_VERSION, nextRowVersion)
            .where(
                SCRIPT_WORK_ITEMS
                    .ID
                    .eq(entity.getId())
                    .and(SCRIPT_WORK_ITEMS.ROW_VERSION.eq(entity.getRowVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite(
          "script_work_items", entity.getId());
    }
    entity.setRowVersion(nextRowVersion);
    return findById(entity.getId())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Saved script_work_item missing for id=" + entity.getId()));
  }

  public ScriptWorkItem saveAndFlush(ScriptWorkItem entity) {
    return save(entity);
  }

  /** Inserts a trigger row without raising a transaction-aborting uniqueness exception. */
  public IdempotentInsertResult insertIfAbsentByTriggerIdentity(ScriptWorkItem entity) {
    if (entity.getId() != null) {
      throw new IllegalArgumentException("A new script work item is required");
    }
    String normalizedRequestId = normalizedScriptPinControlPlaneRequestId(entity);
    for (int attempt = 0; attempt < MAX_TRIGGER_IDENTITY_INSERT_ATTEMPTS; attempt++) {
      Optional<TriggerIdentityInsertResult> insertResult = insertTriggerIdentity(entity);
      if (insertResult.isPresent()) {
        TriggerIdentityInsertResult result = insertResult.orElseThrow();
        if (!result.inserted()) {
          requireMatchingPinOwnerEvidence(
              normalizedRequestId, result.workItem().getScriptPinControlPlaneRequestId());
        }
        return new IdempotentInsertResult(result.workItem(), result.inserted());
      }
      Optional<ScriptWorkItem> existing =
          findByTriggerIdentity(
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
      if (existing.isPresent()) {
        requireMatchingPinOwnerEvidence(
            normalizedRequestId, existing.orElseThrow().getScriptPinControlPlaneRequestId());
        return new IdempotentInsertResult(existing.orElseThrow(), false);
      }
    }
    throw new IllegalStateException("Trigger identity conflict did not yield a row");
  }

  private Optional<TriggerIdentityInsertResult> insertTriggerIdentity(ScriptWorkItem entity) {
    ScriptWorkItemsRecord record = dsl.newRecord(SCRIPT_WORK_ITEMS);
    populate(record, entity);
    List<SelectFieldOrAsterisk> returningFields = new ArrayList<>();
    Collections.addAll(returningFields, SCRIPT_WORK_ITEMS.fields());
    returningFields.add(INSERTED_ROW);
    // PostgreSQL waits for a concurrent unique-index winner before resolving
    // ON CONFLICT DO UPDATE. Returning xmax distinguishes the inserted row
    // from the existing row returned by the no-op conflict update, avoiding a
    // race between DO NOTHING and a separate readback query.
    var conflictTarget =
        dsl.insertInto(SCRIPT_WORK_ITEMS).set(record).onConflict(triggerConflictFields(entity));
    var conflictPredicate =
        entity.getScriptPinEpoch() > 0L
            ? SCRIPT_WORK_ITEMS.SCRIPT_PIN_EPOCH.gt(0L)
            : SCRIPT_WORK_ITEMS.SCRIPT_PIN_EPOCH.eq(0L);
    return conflictTarget
        .where(conflictPredicate)
        .doUpdate()
        .set(SCRIPT_WORK_ITEMS.ID, SCRIPT_WORK_ITEMS.ID)
        .returningResult(returningFields)
        .fetchOptional(
            returned ->
                new TriggerIdentityInsertResult(
                    toEntity(returned), Boolean.TRUE.equals(returned.get(INSERTED_ROW))));
  }

  private record TriggerIdentityInsertResult(ScriptWorkItem workItem, boolean inserted) {}

  private static Field<?>[] triggerConflictFields(ScriptWorkItem entity) {
    List<Field<?>> fields =
        new ArrayList<>(
            List.of(
                SCRIPT_WORK_ITEMS.TENANT_ID,
                SCRIPT_WORK_ITEMS.GAME_INSTANCE_ID,
                SCRIPT_WORK_ITEMS.REGION_ID,
                SCRIPT_WORK_ITEMS.REGION_EPOCH,
                SCRIPT_WORK_ITEMS.ENTITY_ID,
                SCRIPT_WORK_ITEMS.PLAYABLE_STATE_SCOPE,
                SCRIPT_WORK_ITEMS.WORLD_SLUG,
                SCRIPT_WORK_ITEMS.REALM_SLUG,
                SCRIPT_WORK_ITEMS.POINTER_VERSION,
                SCRIPT_WORK_ITEMS.SCRIPT_ID,
                SCRIPT_WORK_ITEMS.PLUGIN_ID,
                SCRIPT_WORK_ITEMS.PLUGIN_VERSION_ID,
                SCRIPT_WORK_ITEMS.BINDING_ID,
                SCRIPT_WORK_ITEMS.EVENT_TYPE,
                SCRIPT_WORK_ITEMS.EVENT_SCHEMA_VERSION,
                SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION));
    if (entity.getScriptPinEpoch() > 0L) {
      fields.add(SCRIPT_WORK_ITEMS.SCRIPT_PIN_EPOCH);
    }
    fields.add(SCRIPT_WORK_ITEMS.SCRIPT_EVENT_ID);
    fields.add(SCRIPT_WORK_ITEMS.DRY_RUN);
    return fields.toArray(Field<?>[]::new);
  }

  private Optional<ScriptWorkItem> findByTriggerIdentity(
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
      long scriptPinEpoch,
      String scriptEventId,
      boolean dryRun) {
    return dsl.selectFrom(SCRIPT_WORK_ITEMS)
        .where(
            triggerIdentityCondition(
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
                dryRun))
        .fetchOptional(this::toEntity);
  }

  private Condition triggerIdentityCondition(
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
      long scriptPinEpoch,
      String scriptEventId,
      boolean dryRun) {
    return SCRIPT_WORK_ITEMS
        .TENANT_ID
        .eq(tenantId)
        .and(SCRIPT_WORK_ITEMS.GAME_INSTANCE_ID.eq(gameInstanceId))
        .and(SCRIPT_WORK_ITEMS.REGION_ID.eq(regionId))
        .and(SCRIPT_WORK_ITEMS.REGION_EPOCH.eq(regionEpoch))
        .and(SCRIPT_WORK_ITEMS.ENTITY_ID.eq(entityId))
        .and(SCRIPT_WORK_ITEMS.PLAYABLE_STATE_SCOPE.eq(playableStateScope))
        .and(SCRIPT_WORK_ITEMS.WORLD_SLUG.eq(worldSlug))
        .and(SCRIPT_WORK_ITEMS.REALM_SLUG.eq(realmSlug))
        .and(SCRIPT_WORK_ITEMS.POINTER_VERSION.eq(pointerVersion))
        .and(SCRIPT_WORK_ITEMS.SCRIPT_ID.eq(scriptId))
        .and(SCRIPT_WORK_ITEMS.PLUGIN_ID.eq(normalizePluginIdentity(pluginId)))
        .and(SCRIPT_WORK_ITEMS.PLUGIN_VERSION_ID.eq(normalizePluginIdentity(pluginVersionId)))
        .and(SCRIPT_WORK_ITEMS.BINDING_ID.eq(normalizePluginIdentity(bindingId)))
        .and(SCRIPT_WORK_ITEMS.EVENT_TYPE.eq(eventType))
        .and(SCRIPT_WORK_ITEMS.EVENT_SCHEMA_VERSION.eq(eventSchemaVersion))
        .and(SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion))
        .and(SCRIPT_WORK_ITEMS.SCRIPT_PIN_EPOCH.eq(scriptPinEpoch))
        .and(SCRIPT_WORK_ITEMS.SCRIPT_EVENT_ID.eq(scriptEventId))
        .and(SCRIPT_WORK_ITEMS.DRY_RUN.eq(dryRun));
  }

  public List<ScriptWorkItem> saveAll(Collection<ScriptWorkItem> entities) {
    if (entities == null || entities.isEmpty()) {
      return List.of();
    }
    return entities.stream().map(this::save).toList();
  }

  public Optional<ScriptWorkItem> findById(Long id) {
    return dsl.selectFrom(SCRIPT_WORK_ITEMS)
        .where(SCRIPT_WORK_ITEMS.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  public void deleteAll(Collection<ScriptWorkItem> entities) {
    if (entities == null || entities.isEmpty()) {
      return;
    }
    List<Long> ids =
        entities.stream().map(ScriptWorkItem::getId).filter(java.util.Objects::nonNull).toList();
    if (ids.isEmpty()) {
      return;
    }
    deleteByIds(ids);
  }

  /**
   * Deletes terminal work-item evidence in FK-safe order under the caller's transaction. Handoff
   * rows are disposed with the parent, while audit rows remain under their independent retention
   * policy and are detached from the deleted work-item through their nullable foreign key.
   */
  private long deleteByIds(Collection<Long> ids) {
    return deleteByIds(ids, org.jooq.impl.DSL.noCondition());
  }

  private long deleteByIds(Collection<Long> ids, Condition parentEligibility) {
    if (ids == null || ids.isEmpty()) {
      return 0L;
    }
    dsl.update(SCRIPT_EVENT_AUDIT)
        .set(SCRIPT_EVENT_AUDIT.WORK_ITEM_ID, (Long) null)
        .where(SCRIPT_EVENT_AUDIT.WORK_ITEM_ID.in(ids))
        .execute();
    dsl.deleteFrom(SCRIPT_HANDOFF_EVENTS)
        .where(SCRIPT_HANDOFF_EVENTS.WORK_ITEM_ID.in(ids))
        .execute();
    return dsl.deleteFrom(SCRIPT_WORK_ITEMS)
        .where(SCRIPT_WORK_ITEMS.ID.in(ids).and(parentEligibility))
        .execute();
  }

  private static Condition cleanupEligibility(String status, Instant updatedAt) {
    Condition condition = SCRIPT_WORK_ITEMS.STATUS.eq(status);
    return updatedAt == null
        ? condition
        : condition.and(SCRIPT_WORK_ITEMS.UPDATED_AT.lt(toLocalDateTime(updatedAt)));
  }

  private List<ScriptWorkItem> fetchMany(Condition condition, org.jooq.SortField<?>... orderBy) {
    return dsl.selectFrom(SCRIPT_WORK_ITEMS)
        .where(condition)
        .orderBy(orderBy)
        .fetch(this::toEntity);
  }

  private List<ScriptWorkItem> fetchManyPaged(
      Condition condition, Pageable pageable, org.jooq.SortField<?>... orderBy) {
    return dsl.selectFrom(SCRIPT_WORK_ITEMS)
        .where(condition)
        .orderBy(orderBy)
        .limit(limitOrDefault(pageable, 100))
        .offset(offsetOrZero(pageable))
        .fetch(this::toEntity);
  }

  private void populate(ScriptWorkItemsRecord record, ScriptWorkItem entity) {
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
    record.setEventType(entity.getEventType());
    record.setEventSchemaVersion(entity.getEventSchemaVersion());
    record.setQuotaClass(entity.getQuotaClass());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setScriptPinEpoch(entity.getScriptPinEpoch());
    record.set(
        SCRIPT_WORK_ITEMS.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID,
        normalizedScriptPinControlPlaneRequestId(entity));
    record.setScriptEventId(entity.getScriptEventId());
    record.setDryRun(entity.isDryRun());
    record.setSourceService(entity.getSourceService());
    record.setTriggerMode(entity.getTriggerMode());
    record.setSourceKind(entity.getSourceKind());
    record.setSourceState(entity.getSourceState());
    record.setSourceOrdinal(entity.getSourceOrdinal());
    record.setSourceDueTickId(entity.getSourceDueTickId());
    record.setSourceDueAtMs(entity.getSourceDueAtMs());
    record.setPriorityTag(entity.getPriorityTag());
    record.setReadSnapshotToken(entity.getReadSnapshotToken());
    record.setPayloadJson(entity.getPayloadJson());
    record.setAdmissionEpoch(entity.getAdmissionEpoch());
    record.setStatus(entity.getStatus());
    record.setCancelReason(entity.getCancelReason());
    record.setCreatedAt(toLocalDateTime(entity.getCreatedAt()));
    record.setUpdatedAt(toLocalDateTime(entity.getUpdatedAt()));
    record.setRowVersion(entity.getRowVersion());
  }

  private static String normalizedScriptPinControlPlaneRequestId(ScriptWorkItem entity) {
    if (entity.getScriptPinEpoch() < 0L) {
      throw new IllegalArgumentException("script_pin_epoch must be non-negative");
    }
    String requestId = entity.getScriptPinControlPlaneRequestId();
    if (entity.getScriptPinEpoch() == 0L) {
      if (requestId != null && !requestId.isBlank()) {
        throw new IllegalArgumentException(
            "script_pin_control_plane_request_id requires a positive script_pin_epoch");
      }
      return null;
    }
    if (requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException(
          "script_pin_control_plane_request_id is required for a positive script_pin_epoch");
    }
    return requestId;
  }

  private static void requireMatchingPinOwnerEvidence(
      String requestedRequestId, String existingRequestId) {
    if (!Objects.equals(requestedRequestId, blankToNull(existingRequestId))) {
      throw new IllegalStateException(PIN_OWNER_EVIDENCE_CONFLICT_MESSAGE);
    }
  }

  private ScriptWorkItem toEntity(Record record) {
    ScriptWorkItem entity = new ScriptWorkItem();
    entity.setId(record.get(SCRIPT_WORK_ITEMS.ID));
    entity.setTenantId(record.get(SCRIPT_WORK_ITEMS.TENANT_ID));
    entity.setGameInstanceId(record.get(SCRIPT_WORK_ITEMS.GAME_INSTANCE_ID));
    entity.setRegionId(record.get(SCRIPT_WORK_ITEMS.REGION_ID));
    entity.setRegionEpoch(record.get(SCRIPT_WORK_ITEMS.REGION_EPOCH));
    entity.setEntityId(record.get(SCRIPT_WORK_ITEMS.ENTITY_ID));
    entity.setPlayableStateScope(record.get(SCRIPT_WORK_ITEMS.PLAYABLE_STATE_SCOPE));
    entity.setWorldSlug(record.get(SCRIPT_WORK_ITEMS.WORLD_SLUG));
    entity.setRealmSlug(record.get(SCRIPT_WORK_ITEMS.REALM_SLUG));
    entity.setPointerVersion(record.get(SCRIPT_WORK_ITEMS.POINTER_VERSION));
    entity.setScriptId(record.get(SCRIPT_WORK_ITEMS.SCRIPT_ID));
    entity.setBindingId(normalizePluginIdentity(record.get(SCRIPT_WORK_ITEMS.BINDING_ID)));
    entity.setPluginId(normalizePluginIdentity(record.get(SCRIPT_WORK_ITEMS.PLUGIN_ID)));
    entity.setPluginVersionId(
        normalizePluginIdentity(record.get(SCRIPT_WORK_ITEMS.PLUGIN_VERSION_ID)));
    entity.setTargetScopeType(record.get(SCRIPT_WORK_ITEMS.TARGET_SCOPE_TYPE));
    entity.setTargetScopeId(record.get(SCRIPT_WORK_ITEMS.TARGET_SCOPE_ID));
    entity.setEventType(record.get(SCRIPT_WORK_ITEMS.EVENT_TYPE));
    entity.setEventSchemaVersion(record.get(SCRIPT_WORK_ITEMS.EVENT_SCHEMA_VERSION));
    entity.setQuotaClass(record.get(SCRIPT_WORK_ITEMS.QUOTA_CLASS));
    entity.setScriptPatchVersion(record.get(SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION));
    Long scriptPinEpoch = record.get(SCRIPT_WORK_ITEMS.SCRIPT_PIN_EPOCH);
    entity.setScriptPinEpoch(scriptPinEpoch == null ? 0L : scriptPinEpoch);
    entity.setScriptPinControlPlaneRequestId(
        record.get(SCRIPT_WORK_ITEMS.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID));
    entity.setScriptEventId(record.get(SCRIPT_WORK_ITEMS.SCRIPT_EVENT_ID));
    entity.setDryRun(Boolean.TRUE.equals(record.get(SCRIPT_WORK_ITEMS.DRY_RUN)));
    entity.setSourceService(record.get(SCRIPT_WORK_ITEMS.SOURCE_SERVICE));
    entity.setTriggerMode(record.get(SCRIPT_WORK_ITEMS.TRIGGER_MODE));
    entity.setSourceKind(record.get(SCRIPT_WORK_ITEMS.SOURCE_KIND));
    entity.setSourceState(record.get(SCRIPT_WORK_ITEMS.SOURCE_STATE));
    entity.setSourceOrdinal(record.get(SCRIPT_WORK_ITEMS.SOURCE_ORDINAL));
    entity.setSourceDueTickId(record.get(SCRIPT_WORK_ITEMS.SOURCE_DUE_TICK_ID));
    entity.setSourceDueAtMs(record.get(SCRIPT_WORK_ITEMS.SOURCE_DUE_AT_MS));
    entity.setPriorityTag(record.get(SCRIPT_WORK_ITEMS.PRIORITY_TAG));
    entity.setReadSnapshotToken(record.get(SCRIPT_WORK_ITEMS.READ_SNAPSHOT_TOKEN));
    entity.setPayloadJson(record.get(SCRIPT_WORK_ITEMS.PAYLOAD_JSON));
    Long admissionEpoch = record.get(SCRIPT_WORK_ITEMS.ADMISSION_EPOCH);
    entity.setAdmissionEpoch(admissionEpoch == null ? 0L : admissionEpoch);
    entity.setStatus(record.get(SCRIPT_WORK_ITEMS.STATUS));
    entity.setCancelReason(record.get(SCRIPT_WORK_ITEMS.CANCEL_REASON));
    entity.setCreatedAt(toInstant(record.get(SCRIPT_WORK_ITEMS.CREATED_AT)));
    entity.setUpdatedAt(toInstant(record.get(SCRIPT_WORK_ITEMS.UPDATED_AT)));
    Integer rowVersion = record.get(SCRIPT_WORK_ITEMS.ROW_VERSION);
    entity.setRowVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }

  private record ScriptPatchInstanceProjectionView(String gameInstanceId, String scriptPatchVersion)
      implements ScriptPatchInstanceProjection {
    @Override
    public String getGameInstanceId() {
      return gameInstanceId;
    }

    @Override
    public String getScriptPatchVersion() {
      return scriptPatchVersion;
    }
  }

  private static String normalizePluginIdentity(String value) {
    return value == null ? "" : value;
  }
}
