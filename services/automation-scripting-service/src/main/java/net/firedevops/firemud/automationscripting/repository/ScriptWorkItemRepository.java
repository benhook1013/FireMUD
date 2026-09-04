package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptDeadLetterReplayResults.SCRIPT_DEAD_LETTER_REPLAY_RESULTS;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventAudit.SCRIPT_EVENT_AUDIT;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptHandoffEvents.SCRIPT_HANDOFF_EVENTS;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptWorkItems.SCRIPT_WORK_ITEMS;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.limitOrDefault;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.offsetOrZero;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
  private static final int MAX_CANCELLATION_ROWS = 100;
  private static final Field<Boolean> INSERTED_ROW =
      field("xmax = 0", Boolean.class).as("inserted");
  private static final Field<LocalDateTime> CURRENT_TIMESTAMP =
      field("CURRENT_TIMESTAMP", LocalDateTime.class);

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
      existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
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
            null,
            null,
            null,
            eventType,
            eventSchemaVersion,
            scriptPatchVersion,
            scriptEventId,
            dryRun,
            false));
  }

  public boolean
      existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndPluginIdAndPluginVersionIdAndBindingIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
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
            scriptEventId,
            dryRun));
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
      findByTenantIdAndScriptPatchVersionAndStatusInForUpdateOrderByCreatedAtAscIdAsc(
          String tenantId,
          String scriptPatchVersion,
          String gameInstanceId,
          String regionId,
          Collection<String> statuses) {
    Condition condition =
        SCRIPT_WORK_ITEMS
            .TENANT_ID
            .eq(tenantId)
            .and(SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion))
            .and(SCRIPT_WORK_ITEMS.STATUS.in(statuses));
    if (gameInstanceId != null && !gameInstanceId.isBlank()) {
      condition = condition.and(SCRIPT_WORK_ITEMS.GAME_INSTANCE_ID.eq(gameInstanceId));
    }
    if (regionId != null && !regionId.isBlank()) {
      condition = condition.and(SCRIPT_WORK_ITEMS.REGION_ID.eq(regionId));
    }
    return dsl.selectFrom(SCRIPT_WORK_ITEMS)
        .where(condition)
        .orderBy(SCRIPT_WORK_ITEMS.CREATED_AT.asc(), SCRIPT_WORK_ITEMS.ID.asc())
        .limit(MAX_CANCELLATION_ROWS)
        .forUpdate()
        .fetch(this::toEntity);
  }

  public List<ScriptWorkItem>
      findByTenantIdAndPluginIdAndPluginVersionIdAndStatusInOrderByCreatedAtAscIdAsc(
          String tenantId, String pluginId, String pluginVersionId, Collection<String> statuses) {
    return fetchMany(
        SCRIPT_WORK_ITEMS
            .TENANT_ID
            .eq(tenantId)
            .and(SCRIPT_WORK_ITEMS.PLUGIN_ID.eq(pluginId))
            .and(SCRIPT_WORK_ITEMS.PLUGIN_VERSION_ID.eq(pluginVersionId))
            .and(SCRIPT_WORK_ITEMS.STATUS.in(statuses)),
        SCRIPT_WORK_ITEMS.CREATED_AT.asc(),
        SCRIPT_WORK_ITEMS.ID.asc());
  }

  public List<ScriptWorkItem>
      findByTenantIdAndPluginIdAndPluginVersionIdAndStatusInForUpdateOrderByCreatedAtAscIdAsc(
          String tenantId,
          String pluginId,
          String pluginVersionId,
          String gameInstanceId,
          String regionId,
          Collection<String> statuses) {
    Condition condition =
        SCRIPT_WORK_ITEMS
            .TENANT_ID
            .eq(tenantId)
            .and(SCRIPT_WORK_ITEMS.PLUGIN_ID.eq(pluginId))
            .and(SCRIPT_WORK_ITEMS.PLUGIN_VERSION_ID.eq(pluginVersionId))
            .and(SCRIPT_WORK_ITEMS.STATUS.in(statuses));
    if (gameInstanceId != null && !gameInstanceId.isBlank()) {
      condition = condition.and(SCRIPT_WORK_ITEMS.GAME_INSTANCE_ID.eq(gameInstanceId));
    }
    if (regionId != null && !regionId.isBlank()) {
      condition = condition.and(SCRIPT_WORK_ITEMS.REGION_ID.eq(regionId));
    }
    return dsl.selectFrom(SCRIPT_WORK_ITEMS)
        .where(condition)
        .orderBy(SCRIPT_WORK_ITEMS.CREATED_AT.asc(), SCRIPT_WORK_ITEMS.ID.asc())
        .limit(MAX_CANCELLATION_ROWS)
        .forUpdate()
        .fetch(this::toEntity);
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
    if (gameInstanceId != null && !gameInstanceId.isBlank()) {
      condition = condition.and(SCRIPT_WORK_ITEMS.GAME_INSTANCE_ID.eq(gameInstanceId));
    }
    if (scriptPatchVersion != null && !scriptPatchVersion.isBlank()) {
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

  public List<ScriptWorkItem> findByStatusForUpdateOrderByCreatedAtAscIdAsc(
      String status, Pageable pageable) {
    Condition condition = SCRIPT_WORK_ITEMS.STATUS.eq(status);
    if ("PENDING_EVALUATION".equals(status)) {
      condition = condition.and(retryEligibilityCondition());
    }
    return dsl.selectFrom(SCRIPT_WORK_ITEMS)
        .where(condition)
        .orderBy(SCRIPT_WORK_ITEMS.CREATED_AT.asc(), SCRIPT_WORK_ITEMS.ID.asc())
        .limit(limitOrDefault(pageable, 100))
        .forUpdate()
        .skipLocked()
        .fetch(this::toEntity);
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

  public List<ScriptWorkItem> findByIdInAndStatusForUpdateOrderByCreatedAtAscIdAsc(
      Collection<Long> ids, String status, Pageable pageable) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    Condition condition = SCRIPT_WORK_ITEMS.ID.in(ids).and(SCRIPT_WORK_ITEMS.STATUS.eq(status));
    if ("PENDING_EVALUATION".equals(status)) {
      condition = condition.and(retryEligibilityCondition());
    }
    return dsl.selectFrom(SCRIPT_WORK_ITEMS)
        .where(condition)
        .orderBy(SCRIPT_WORK_ITEMS.CREATED_AT.asc(), SCRIPT_WORK_ITEMS.ID.asc())
        .limit(limitOrDefault(pageable, 100))
        .forUpdate()
        .skipLocked()
        .fetch(this::toEntity);
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

  /**
   * Reads an oldest-first page after a stable update-time/id cursor. Keyset paging lets cleanup
   * delete rows as it scans without skipping rows that shift into an earlier offset.
   */
  public List<ScriptWorkItem> findByStatusOrderByUpdatedAtAscIdAscAfter(
      String status, Instant afterUpdatedAt, Long afterId, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    Condition condition = SCRIPT_WORK_ITEMS.STATUS.eq(status);
    if (afterUpdatedAt != null && afterId != null) {
      condition =
          condition.and(
              SCRIPT_WORK_ITEMS
                  .UPDATED_AT
                  .gt(toLocalDateTime(afterUpdatedAt))
                  .or(
                      SCRIPT_WORK_ITEMS
                          .UPDATED_AT
                          .eq(toLocalDateTime(afterUpdatedAt))
                          .and(SCRIPT_WORK_ITEMS.ID.gt(afterId))));
    }
    return dsl.selectFrom(SCRIPT_WORK_ITEMS)
        .where(condition)
        .orderBy(SCRIPT_WORK_ITEMS.UPDATED_AT.asc(), SCRIPT_WORK_ITEMS.ID.asc())
        .limit(limit)
        .fetch(this::toEntity);
  }

  public List<ScriptWorkItem> findByTenantIdAndStatusOrderByUpdatedAtDescIdDesc(
      String tenantId, String status, Pageable pageable) {
    return fetchManyPaged(
        SCRIPT_WORK_ITEMS.TENANT_ID.eq(tenantId).and(SCRIPT_WORK_ITEMS.STATUS.eq(status)),
        pageable,
        SCRIPT_WORK_ITEMS.UPDATED_AT.desc(),
        SCRIPT_WORK_ITEMS.ID.desc());
  }

  public List<ScriptWorkItem> findDeadLetters(
      String tenantId,
      String status,
      String gameInstanceId,
      String scriptPatchVersion,
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
   * Counts old terminal rows across all tenants that could not be swept because retained evidence
   * still exists. The scheduled maintenance sweep and its blocked-row gauge are deployment-wide.
   */
  public long countTerminalRowsBlockedByEvidence(String status, Instant safeWatermark) {
    return dsl.fetchCount(
        SCRIPT_WORK_ITEMS,
        SCRIPT_WORK_ITEMS
            .STATUS
            .eq(status)
            .and(SCRIPT_WORK_ITEMS.UPDATED_AT.lt(toLocalDateTime(safeWatermark)))
            .and(noRetainedEvidence().not()));
  }

  public long deleteByStatusAndUpdatedAtBefore(String status, Instant updatedAt) {
    return dsl.deleteFrom(SCRIPT_WORK_ITEMS)
        .where(
            SCRIPT_WORK_ITEMS
                .STATUS
                .eq(status)
                .and(SCRIPT_WORK_ITEMS.UPDATED_AT.lt(toLocalDateTime(updatedAt)))
                .and(noRetainedEvidence()))
        .execute();
  }

  /** Deletes a terminal parent only when no audit or handoff evidence still references it. */
  public boolean deleteDeadLetteredIfNoRetainedEvidence(String tenantId, Long id) {
    return dsl.deleteFrom(SCRIPT_WORK_ITEMS)
            .where(
                SCRIPT_WORK_ITEMS
                    .ID
                    .eq(id)
                    .and(SCRIPT_WORK_ITEMS.TENANT_ID.eq(tenantId))
                    .and(SCRIPT_WORK_ITEMS.STATUS.eq("DEAD_LETTERED"))
                    .and(noRetainedEvidence()))
            .execute()
        == 1;
  }

  private Condition noRetainedEvidence() {
    return notExists(
            selectOne()
                .from(SCRIPT_EVENT_AUDIT)
                .where(
                    SCRIPT_EVENT_AUDIT
                        .TENANT_ID
                        .eq(SCRIPT_WORK_ITEMS.TENANT_ID)
                        .and(SCRIPT_EVENT_AUDIT.WORK_ITEM_ID.eq(SCRIPT_WORK_ITEMS.ID))))
        .and(
            notExists(
                selectOne()
                    .from(SCRIPT_HANDOFF_EVENTS)
                    .where(
                        SCRIPT_HANDOFF_EVENTS
                            .TENANT_ID
                            .eq(SCRIPT_WORK_ITEMS.TENANT_ID)
                            .and(SCRIPT_HANDOFF_EVENTS.WORK_ITEM_ID.eq(SCRIPT_WORK_ITEMS.ID)))))
        .and(
            notExists(
                selectOne()
                    .from(SCRIPT_DEAD_LETTER_REPLAY_RESULTS)
                    .where(
                        SCRIPT_DEAD_LETTER_REPLAY_RESULTS
                            .TENANT_ID
                            .eq(SCRIPT_WORK_ITEMS.TENANT_ID)
                            .and(
                                SCRIPT_DEAD_LETTER_REPLAY_RESULTS.WORK_ITEM_ID.eq(
                                    SCRIPT_WORK_ITEMS.ID)))));
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
      ScriptWorkItemsRecord record = dsl.newRecord(SCRIPT_WORK_ITEMS);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
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
            .set(SCRIPT_WORK_ITEMS.PLUGIN_ID, entity.getPluginId())
            .set(SCRIPT_WORK_ITEMS.PLUGIN_VERSION_ID, entity.getPluginVersionId())
            .set(SCRIPT_WORK_ITEMS.BINDING_ID, entity.getBindingId())
            .set(SCRIPT_WORK_ITEMS.PLUGIN_ACTIVATION_EPOCH, entity.getPluginActivationEpoch())
            .set(SCRIPT_WORK_ITEMS.LIFECYCLE_REVISION, entity.getLifecycleRevision())
            .set(SCRIPT_WORK_ITEMS.EVENT_TYPE, entity.getEventType())
            .set(SCRIPT_WORK_ITEMS.EVENT_SCHEMA_VERSION, entity.getEventSchemaVersion())
            .set(SCRIPT_WORK_ITEMS.QUOTA_CLASS, entity.getQuotaClass())
            .set(SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
            .set(SCRIPT_WORK_ITEMS.SCRIPT_PIN_EPOCH, entity.getScriptPinEpoch())
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
            .set(SCRIPT_WORK_ITEMS.FAILURE_GENERATION, entity.getFailureGeneration())
            .set(
                SCRIPT_WORK_ITEMS.AUTHORITY_UNAVAILABLE_SINCE,
                toLocalDateTime(entity.getAuthorityUnavailableSince()))
            .set(
                SCRIPT_WORK_ITEMS.AUTHORITY_UNAVAILABLE_COUNT,
                entity.getAuthorityUnavailableCount())
            .set(SCRIPT_WORK_ITEMS.NEXT_ELIGIBLE_AT, toLocalDateTime(entity.getNextEligibleAt()))
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
    for (int attempt = 0; attempt < MAX_TRIGGER_IDENTITY_INSERT_ATTEMPTS; attempt++) {
      Optional<TriggerIdentityInsertResult> insertResult = insertTriggerIdentity(entity);
      if (insertResult.isPresent()) {
        TriggerIdentityInsertResult result = insertResult.orElseThrow();
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
              entity.getScriptEventId(),
              entity.isDryRun());
      if (existing.isPresent()) {
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
    return dsl.insertInto(SCRIPT_WORK_ITEMS)
        .set(record)
        .onConflict(
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
            SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION,
            SCRIPT_WORK_ITEMS.SCRIPT_EVENT_ID,
            SCRIPT_WORK_ITEMS.DRY_RUN)
        .doUpdate()
        .set(SCRIPT_WORK_ITEMS.ID, SCRIPT_WORK_ITEMS.ID)
        .returningResult(returningFields)
        .fetchOptional(
            returned ->
                new TriggerIdentityInsertResult(
                    toEntity(returned), Boolean.TRUE.equals(returned.get(INSERTED_ROW))));
  }

  private record TriggerIdentityInsertResult(ScriptWorkItem workItem, boolean inserted) {}

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
      String scriptEventId,
      boolean dryRun) {
    return triggerIdentityCondition(
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
        scriptEventId,
        dryRun,
        true);
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
      String scriptEventId,
      boolean dryRun,
      boolean constrainPluginIdentity) {
    Condition condition =
        SCRIPT_WORK_ITEMS
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
            .and(SCRIPT_WORK_ITEMS.EVENT_TYPE.eq(eventType))
            .and(SCRIPT_WORK_ITEMS.EVENT_SCHEMA_VERSION.eq(eventSchemaVersion))
            .and(SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion))
            .and(SCRIPT_WORK_ITEMS.SCRIPT_EVENT_ID.eq(scriptEventId))
            .and(SCRIPT_WORK_ITEMS.DRY_RUN.eq(dryRun));
    if (constrainPluginIdentity) {
      condition =
          condition
              .and(SCRIPT_WORK_ITEMS.PLUGIN_ID.isNotDistinctFrom(pluginId))
              .and(SCRIPT_WORK_ITEMS.PLUGIN_VERSION_ID.isNotDistinctFrom(pluginVersionId))
              .and(SCRIPT_WORK_ITEMS.BINDING_ID.isNotDistinctFrom(bindingId));
    }
    return condition;
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

  /**
   * Claims one dead-letter item for replay using its observed failure generation and row version.
   * The status, generation, and row version predicates make competing replay requests
   * deterministic: exactly one request can move the item out of DEAD_LETTERED, while all other
   * requests receive an empty result and record recovery_in_progress rather than racing through a
   * stale entity write.
   */
  public Optional<ScriptWorkItem> claimDeadLetterForReplay(
      Long id,
      String tenantId,
      int expectedRowVersion,
      long expectedFailureGeneration,
      Instant now) {
    return dsl.update(SCRIPT_WORK_ITEMS)
        .set(SCRIPT_WORK_ITEMS.STATUS, "PENDING_EVALUATION")
        .set(SCRIPT_WORK_ITEMS.CANCEL_REASON, "")
        .set(SCRIPT_WORK_ITEMS.AUTHORITY_UNAVAILABLE_SINCE, (LocalDateTime) null)
        .set(SCRIPT_WORK_ITEMS.AUTHORITY_UNAVAILABLE_COUNT, 0)
        .set(SCRIPT_WORK_ITEMS.NEXT_ELIGIBLE_AT, (LocalDateTime) null)
        .set(SCRIPT_WORK_ITEMS.UPDATED_AT, toLocalDateTime(now))
        .set(SCRIPT_WORK_ITEMS.ROW_VERSION, expectedRowVersion + 1)
        .where(
            SCRIPT_WORK_ITEMS
                .ID
                .eq(id)
                .and(SCRIPT_WORK_ITEMS.TENANT_ID.eq(tenantId))
                .and(SCRIPT_WORK_ITEMS.STATUS.eq("DEAD_LETTERED"))
                .and(SCRIPT_WORK_ITEMS.ROW_VERSION.eq(expectedRowVersion))
                .and(SCRIPT_WORK_ITEMS.FAILURE_GENERATION.eq(expectedFailureGeneration)))
        .returningResult(SCRIPT_WORK_ITEMS.fields())
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
    dsl.deleteFrom(SCRIPT_WORK_ITEMS).where(SCRIPT_WORK_ITEMS.ID.in(ids)).execute();
  }

  private List<ScriptWorkItem> fetchMany(Condition condition, org.jooq.SortField<?>... orderBy) {
    return dsl.selectFrom(SCRIPT_WORK_ITEMS)
        .where(condition)
        .orderBy(orderBy)
        .fetch(this::toEntity);
  }

  private static Condition retryEligibilityCondition() {
    return SCRIPT_WORK_ITEMS
        .NEXT_ELIGIBLE_AT
        .isNull()
        .or(SCRIPT_WORK_ITEMS.NEXT_ELIGIBLE_AT.le(CURRENT_TIMESTAMP));
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
    record.setPluginId(entity.getPluginId());
    record.setPluginVersionId(entity.getPluginVersionId());
    record.setBindingId(entity.getBindingId());
    record.setPluginActivationEpoch(entity.getPluginActivationEpoch());
    record.setLifecycleRevision(entity.getLifecycleRevision());
    record.setFailureGeneration(entity.getFailureGeneration());
    record.setAuthorityUnavailableSince(toLocalDateTime(entity.getAuthorityUnavailableSince()));
    record.setAuthorityUnavailableCount(entity.getAuthorityUnavailableCount());
    record.setNextEligibleAt(toLocalDateTime(entity.getNextEligibleAt()));
    record.setEventType(entity.getEventType());
    record.setEventSchemaVersion(entity.getEventSchemaVersion());
    record.setQuotaClass(entity.getQuotaClass());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setScriptPinEpoch(entity.getScriptPinEpoch());
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
    entity.setPluginId(record.get(SCRIPT_WORK_ITEMS.PLUGIN_ID));
    entity.setPluginVersionId(record.get(SCRIPT_WORK_ITEMS.PLUGIN_VERSION_ID));
    entity.setBindingId(record.get(SCRIPT_WORK_ITEMS.BINDING_ID));
    Long pluginActivationEpoch = record.get(SCRIPT_WORK_ITEMS.PLUGIN_ACTIVATION_EPOCH);
    entity.setPluginActivationEpoch(pluginActivationEpoch == null ? 0L : pluginActivationEpoch);
    Long lifecycleRevision = record.get(SCRIPT_WORK_ITEMS.LIFECYCLE_REVISION);
    entity.setLifecycleRevision(lifecycleRevision == null ? 0L : lifecycleRevision);
    Long failureGeneration = record.get(SCRIPT_WORK_ITEMS.FAILURE_GENERATION);
    entity.setFailureGeneration(failureGeneration == null ? 1L : failureGeneration);
    entity.setAuthorityUnavailableSince(
        toInstant(record.get(SCRIPT_WORK_ITEMS.AUTHORITY_UNAVAILABLE_SINCE)));
    Integer authorityUnavailableCount = record.get(SCRIPT_WORK_ITEMS.AUTHORITY_UNAVAILABLE_COUNT);
    entity.setAuthorityUnavailableCount(
        authorityUnavailableCount == null ? 0 : authorityUnavailableCount);
    entity.setNextEligibleAt(toInstant(record.get(SCRIPT_WORK_ITEMS.NEXT_ELIGIBLE_AT)));
    entity.setEventType(record.get(SCRIPT_WORK_ITEMS.EVENT_TYPE));
    entity.setEventSchemaVersion(record.get(SCRIPT_WORK_ITEMS.EVENT_SCHEMA_VERSION));
    entity.setQuotaClass(record.get(SCRIPT_WORK_ITEMS.QUOTA_CLASS));
    entity.setScriptPatchVersion(record.get(SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION));
    Long scriptPinEpoch = record.get(SCRIPT_WORK_ITEMS.SCRIPT_PIN_EPOCH);
    entity.setScriptPinEpoch(scriptPinEpoch == null ? 0L : scriptPinEpoch);
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
}
