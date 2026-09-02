package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.limitOrDefault;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.offsetOrZero;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toOffsetDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.GameplayCommand.GAMEPLAY_COMMAND;
import static net.firedevops.firemud.gamesession.jooq.tables.RemoteCommandCoordinator.REMOTE_COMMAND_COORDINATOR;
import static net.firedevops.firemud.gamesession.jooq.tables.RemoteFollowup.REMOTE_FOLLOWUP;
import static net.firedevops.firemud.gamesession.jooq.tables.RuntimeRegionStatus.RUNTIME_REGION_STATUS;
import static net.firedevops.firemud.gamesession.repository.JooqGameSessionRepositorySupport.addIfNonNull;
import static net.firedevops.firemud.gamesession.repository.JooqGameSessionRepositorySupport.addIfNotBlank;
import static net.firedevops.firemud.gamesession.repository.JooqGameSessionRepositorySupport.addIfPositive;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.selectOne;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import net.firedevops.firemud.gamesession.jooq.tables.records.RemoteFollowupRecord;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectJoinStep;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class RemoteFollowupRepository {
  private static final int DEFAULT_CONTROL_PLANE_LIMIT = 200;

  private final DSLContext dsl;

  public RemoteFollowupRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<RemoteFollowup> findByFollowupId(String followupId) {
    return dsl.selectFrom(REMOTE_FOLLOWUP)
        .where(REMOTE_FOLLOWUP.FOLLOWUP_ID.eq(followupId))
        .fetchOptional(this::toEntity);
  }

  public Optional<RemoteFollowup> findByTenantIdAndFollowupId(Long tenantId, String followupId) {
    return dsl.selectFrom(REMOTE_FOLLOWUP)
        .where(
            REMOTE_FOLLOWUP.TENANT_ID.eq(tenantId).and(REMOTE_FOLLOWUP.FOLLOWUP_ID.eq(followupId)))
        .fetchOptional(this::toEntity);
  }

  public List<RemoteFollowup> findByTenantIdAndFollowupIdIn(Long tenantId, Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return dsl.selectFrom(REMOTE_FOLLOWUP)
        .where(REMOTE_FOLLOWUP.TENANT_ID.eq(tenantId).and(REMOTE_FOLLOWUP.FOLLOWUP_ID.in(ids)))
        .orderBy(REMOTE_FOLLOWUP.ID.asc())
        .fetch(this::toEntity);
  }

  public Optional<RemoteFollowup>
      findByTenantIdAndTargetGameInstanceIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
          Long tenantId,
          Long targetGameInstanceId,
          String targetRegionId,
          long targetRegionEpoch,
          String effectKey) {
    return dsl.selectFrom(REMOTE_FOLLOWUP)
        .where(
            REMOTE_FOLLOWUP
                .TENANT_ID
                .eq(tenantId)
                .and(REMOTE_FOLLOWUP.TARGET_GAME_INSTANCE_ID.eq(targetGameInstanceId))
                .and(REMOTE_FOLLOWUP.TARGET_REGION_ID.eq(targetRegionId))
                .and(REMOTE_FOLLOWUP.TARGET_REGION_EPOCH.eq(targetRegionEpoch))
                .and(REMOTE_FOLLOWUP.EFFECT_KEY.eq(effectKey)))
        .fetchOptional(this::toEntity);
  }

  public long
      countByTenantIdAndTargetGameInstanceIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqual(
          Long tenantId,
          Long targetGameInstanceId,
          String targetRegionId,
          String status,
          long dueTickId) {
    return dsl.fetchCount(
        REMOTE_FOLLOWUP,
        REMOTE_FOLLOWUP
            .TENANT_ID
            .eq(tenantId)
            .and(REMOTE_FOLLOWUP.TARGET_GAME_INSTANCE_ID.eq(targetGameInstanceId))
            .and(REMOTE_FOLLOWUP.TARGET_REGION_ID.eq(targetRegionId))
            .and(REMOTE_FOLLOWUP.STATUS.eq(status))
            .and(REMOTE_FOLLOWUP.DUE_TICK_ID.le(dueTickId)));
  }

  public List<RemoteFollowup>
      findByTenantIdAndTargetGameInstanceIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAscIdAsc(
          Long tenantId,
          Long targetGameInstanceId,
          String targetRegionId,
          String status,
          long dueTickId,
          Pageable pageable) {
    return dsl.selectFrom(REMOTE_FOLLOWUP)
        .where(
            REMOTE_FOLLOWUP
                .TENANT_ID
                .eq(tenantId)
                .and(REMOTE_FOLLOWUP.TARGET_GAME_INSTANCE_ID.eq(targetGameInstanceId))
                .and(REMOTE_FOLLOWUP.TARGET_REGION_ID.eq(targetRegionId))
                .and(REMOTE_FOLLOWUP.STATUS.eq(status))
                .and(REMOTE_FOLLOWUP.DUE_TICK_ID.le(dueTickId)))
        .orderBy(REMOTE_FOLLOWUP.DUE_TICK_ID.asc(), REMOTE_FOLLOWUP.ID.asc())
        .limit(limitOrDefault(pageable, DEFAULT_CONTROL_PLANE_LIMIT))
        .offset(offsetOrZero(pageable))
        .forUpdate()
        .fetch(this::toEntity);
  }

  public List<RemoteFollowup>
      findByTenantIdAndTargetGameInstanceIdAndTargetRegionIdAndStatusOrderByDueTickIdAscIdAsc(
          Long tenantId, Long targetGameInstanceId, String targetRegionId, String status) {
    return dsl.selectFrom(REMOTE_FOLLOWUP)
        .where(
            REMOTE_FOLLOWUP
                .TENANT_ID
                .eq(tenantId)
                .and(REMOTE_FOLLOWUP.TARGET_GAME_INSTANCE_ID.eq(targetGameInstanceId))
                .and(REMOTE_FOLLOWUP.TARGET_REGION_ID.eq(targetRegionId))
                .and(REMOTE_FOLLOWUP.STATUS.eq(status)))
        .orderBy(REMOTE_FOLLOWUP.DUE_TICK_ID.asc(), REMOTE_FOLLOWUP.ID.asc())
        .fetch(this::toEntity);
  }

  public Optional<RemoteFollowup>
      findFirstByTenantIdAndTargetGameInstanceIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAsc(
          Long tenantId,
          Long targetGameInstanceId,
          String targetRegionId,
          String status,
          long dueTickId) {
    return dsl.selectFrom(REMOTE_FOLLOWUP)
        .where(
            REMOTE_FOLLOWUP
                .TENANT_ID
                .eq(tenantId)
                .and(REMOTE_FOLLOWUP.TARGET_GAME_INSTANCE_ID.eq(targetGameInstanceId))
                .and(REMOTE_FOLLOWUP.TARGET_REGION_ID.eq(targetRegionId))
                .and(REMOTE_FOLLOWUP.STATUS.eq(status))
                .and(REMOTE_FOLLOWUP.DUE_TICK_ID.le(dueTickId)))
        .orderBy(REMOTE_FOLLOWUP.DUE_TICK_ID.asc(), REMOTE_FOLLOWUP.ID.asc())
        .limit(1)
        .fetchOptional(this::toEntity);
  }

  public List<RemoteFollowup>
      findByTenantIdAndTargetGameInstanceIdAndTargetRegionIdOrderByDueTickIdAsc(
          Long tenantId, Long targetGameInstanceId, String targetRegionId) {
    return dsl.selectFrom(REMOTE_FOLLOWUP)
        .where(
            REMOTE_FOLLOWUP
                .TENANT_ID
                .eq(tenantId)
                .and(REMOTE_FOLLOWUP.TARGET_GAME_INSTANCE_ID.eq(targetGameInstanceId))
                .and(REMOTE_FOLLOWUP.TARGET_REGION_ID.eq(targetRegionId)))
        .orderBy(REMOTE_FOLLOWUP.DUE_TICK_ID.asc(), REMOTE_FOLLOWUP.ID.asc())
        .fetch(this::toEntity);
  }

  public List<RemoteFollowup> findByClaimedTickBatchIdOrderByIdAsc(String claimedTickBatchId) {
    return dsl.selectFrom(REMOTE_FOLLOWUP)
        .where(REMOTE_FOLLOWUP.CLAIMED_TICK_BATCH_ID.eq(claimedTickBatchId))
        .orderBy(REMOTE_FOLLOWUP.ID.asc())
        .fetch(this::toEntity);
  }

  public List<RemoteFollowup> findForControlPlane(
      Long tenantId,
      String targetRegionId,
      String status,
      Long originGameInstanceId,
      String originRegionId,
      long originRegionEpoch,
      Long targetGameInstanceId,
      long targetRegionEpoch,
      String currentOriginRuntimeRegionId,
      long currentOriginRuntimeRegionEpoch,
      Long currentOriginRuntimeGameInstanceId,
      String currentTargetRuntimeRegionId,
      long currentTargetRuntimeRegionEpoch,
      Long currentTargetRuntimeGameInstanceId,
      String followupId,
      String scriptId,
      String pluginId,
      String scriptPatchVersion,
      String pluginVersionId,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      Long pointerVersion,
      String payloadKind,
      String originSourceKind,
      String originSourceState,
      String automationWorkItemId,
      String targetEntityId,
      String claimTargetAggregate,
      String effectKey,
      String failureCode,
      Boolean requiresSoloTick,
      String claimedTickBatchId,
      String queueSourceKind,
      String queueSourceState,
      long queueSourceOrdinal,
      long queueSourceDueTickId,
      long queueSourceDueAtMs,
      String requestedCommand,
      String eventType,
      String scriptEventId,
      long originDeadlineRegionEpoch,
      long originDeadlineTickId,
      String lateResultPolicy,
      String automationDispatchId,
      String commandId,
      String targetCommandId,
      String targetCommandExecutionOutcome,
      String targetCommandGameplayResult,
      Pageable pageable) {
    if (targetRegionId != null && !targetRegionId.isBlank() && targetGameInstanceId == null) {
      throw new IllegalArgumentException(
          "target_game_instance_id is required when target_region_id is set");
    }
    var followup = REMOTE_FOLLOWUP.as("followup");
    var currentOrigin = RUNTIME_REGION_STATUS.as("currentOrigin");
    var currentTarget = RUNTIME_REGION_STATUS.as("currentTarget");
    var targetCommand = GAMEPLAY_COMMAND.as("targetCommand");
    var coordinator = REMOTE_COMMAND_COORDINATOR.as("coordinator");

    List<Condition> conditions = new ArrayList<>();
    conditions.add(followup.TENANT_ID.eq(tenantId));
    addIfNotBlank(conditions, targetRegionId, () -> followup.TARGET_REGION_ID.eq(targetRegionId));
    addIfNotBlank(conditions, status, () -> followup.STATUS.eq(status));
    addIfNonNull(
        conditions,
        originGameInstanceId,
        () -> followup.ORIGIN_GAME_INSTANCE_ID.eq(originGameInstanceId));
    addIfNotBlank(conditions, originRegionId, () -> followup.ORIGIN_REGION_ID.eq(originRegionId));
    addIfPositive(
        conditions, originRegionEpoch, () -> followup.ORIGIN_REGION_EPOCH.eq(originRegionEpoch));
    addIfNonNull(
        conditions,
        targetGameInstanceId,
        () -> followup.TARGET_GAME_INSTANCE_ID.eq(targetGameInstanceId));
    addIfPositive(
        conditions, targetRegionEpoch, () -> followup.TARGET_REGION_EPOCH.eq(targetRegionEpoch));
    addIfNotBlank(
        conditions,
        currentOriginRuntimeRegionId,
        () -> currentOrigin.REGION_ID.eq(currentOriginRuntimeRegionId));
    addIfPositive(
        conditions,
        currentOriginRuntimeRegionEpoch,
        () -> currentOrigin.REGION_EPOCH.eq(currentOriginRuntimeRegionEpoch));
    addIfNonNull(
        conditions,
        currentOriginRuntimeGameInstanceId,
        () -> currentOrigin.GAME_INSTANCE_ID.eq(currentOriginRuntimeGameInstanceId));
    addIfNotBlank(
        conditions,
        currentTargetRuntimeRegionId,
        () -> currentTarget.REGION_ID.eq(currentTargetRuntimeRegionId));
    addIfPositive(
        conditions,
        currentTargetRuntimeRegionEpoch,
        () -> currentTarget.REGION_EPOCH.eq(currentTargetRuntimeRegionEpoch));
    addIfNonNull(
        conditions,
        currentTargetRuntimeGameInstanceId,
        () -> currentTarget.GAME_INSTANCE_ID.eq(currentTargetRuntimeGameInstanceId));
    addIfNotBlank(conditions, followupId, () -> followup.FOLLOWUP_ID.eq(followupId));
    addIfNotBlank(conditions, scriptId, () -> followup.SCRIPT_ID.eq(scriptId));
    addIfNotBlank(conditions, pluginId, () -> followup.PLUGIN_ID.eq(pluginId));
    addIfNotBlank(
        conditions, scriptPatchVersion, () -> followup.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion));
    addIfNotBlank(
        conditions, pluginVersionId, () -> followup.PLUGIN_VERSION_ID.eq(pluginVersionId));
    addIfNotBlank(
        conditions, playableStateScope, () -> followup.PLAYABLE_STATE_SCOPE.eq(playableStateScope));
    addIfNotBlank(conditions, worldSlug, () -> followup.WORLD_SLUG.eq(worldSlug));
    addIfNotBlank(conditions, realmSlug, () -> followup.REALM_SLUG.eq(realmSlug));
    addIfNonNull(conditions, pointerVersion, () -> followup.POINTER_VERSION.eq(pointerVersion));
    addIfNotBlank(conditions, payloadKind, () -> followup.PAYLOAD_KIND.eq(payloadKind));
    addIfNotBlank(
        conditions, originSourceKind, () -> followup.ORIGIN_SOURCE_KIND.eq(originSourceKind));
    addIfNotBlank(
        conditions, originSourceState, () -> followup.ORIGIN_SOURCE_STATE.eq(originSourceState));
    addIfNotBlank(
        conditions,
        automationWorkItemId,
        () -> followup.AUTOMATION_WORK_ITEM_ID.eq(automationWorkItemId));
    addIfNotBlank(conditions, targetEntityId, () -> followup.TARGET_ENTITY_ID.eq(targetEntityId));
    addIfNotBlank(
        conditions,
        claimTargetAggregate,
        () -> followup.CLAIM_TARGET_AGGREGATE.eq(claimTargetAggregate));
    addIfNotBlank(conditions, effectKey, () -> followup.EFFECT_KEY.eq(effectKey));
    addIfNotBlank(conditions, failureCode, () -> followup.FAILURE_CODE.eq(failureCode));
    addIfNonNull(
        conditions, requiresSoloTick, () -> followup.REQUIRES_SOLO_TICK.eq(requiresSoloTick));
    addIfNotBlank(
        conditions,
        claimedTickBatchId,
        () -> followup.CLAIMED_TICK_BATCH_ID.eq(claimedTickBatchId));
    addIfNotBlank(
        conditions, queueSourceKind, () -> followup.QUEUE_SOURCE_KIND.eq(queueSourceKind));
    addIfNotBlank(
        conditions, queueSourceState, () -> followup.QUEUE_SOURCE_STATE.eq(queueSourceState));
    addIfPositive(
        conditions, queueSourceOrdinal, () -> followup.QUEUE_SOURCE_ORDINAL.eq(queueSourceOrdinal));
    addIfPositive(
        conditions,
        queueSourceDueTickId,
        () -> followup.QUEUE_SOURCE_DUE_TICK_ID.eq(queueSourceDueTickId));
    addIfPositive(
        conditions,
        queueSourceDueAtMs,
        () -> followup.QUEUE_SOURCE_DUE_AT_MS.eq(queueSourceDueAtMs));
    addIfNotBlank(
        conditions, requestedCommand, () -> followup.REQUESTED_COMMAND.eq(requestedCommand));
    addIfNotBlank(conditions, eventType, () -> followup.EVENT_TYPE.eq(eventType));
    addIfNotBlank(conditions, scriptEventId, () -> followup.SCRIPT_EVENT_ID.eq(scriptEventId));
    addIfNotBlank(
        conditions,
        automationDispatchId,
        () -> followup.AUTOMATION_DISPATCH_ID.eq(automationDispatchId));
    addIfNotBlank(conditions, commandId, () -> followup.COMMAND_ID.eq(commandId));
    addIfNotBlank(conditions, targetCommandId, () -> targetCommand.COMMAND_ID.eq(targetCommandId));
    addIfNotBlank(
        conditions,
        targetCommandExecutionOutcome,
        () -> targetCommand.EXECUTION_OUTCOME.eq(targetCommandExecutionOutcome));
    addIfNotBlank(
        conditions,
        targetCommandGameplayResult,
        () -> targetCommand.GAMEPLAY_RESULT.eq(targetCommandGameplayResult));
    addIfPositive(
        conditions,
        originDeadlineRegionEpoch,
        () ->
            exists(
                selectOne()
                    .from(coordinator)
                    .where(
                        coordinator
                            .TENANT_ID
                            .eq(followup.TENANT_ID)
                            .and(coordinator.FOLLOWUP_ID.eq(followup.FOLLOWUP_ID))
                            .and(
                                coordinator.ORIGIN_GAME_INSTANCE_ID.eq(
                                    followup.ORIGIN_GAME_INSTANCE_ID))
                            .and(coordinator.ORIGIN_REGION_ID.eq(followup.ORIGIN_REGION_ID))
                            .and(coordinator.ORIGIN_REGION_EPOCH.eq(followup.ORIGIN_REGION_EPOCH))
                            .and(
                                coordinator.TARGET_GAME_INSTANCE_ID.eq(
                                    followup.TARGET_GAME_INSTANCE_ID))
                            .and(coordinator.TARGET_REGION_ID.eq(followup.TARGET_REGION_ID))
                            .and(coordinator.TARGET_REGION_EPOCH.eq(followup.TARGET_REGION_EPOCH))
                            .and(
                                coordinator.ORIGIN_DEADLINE_REGION_EPOCH.eq(
                                    originDeadlineRegionEpoch)))));
    addIfPositive(
        conditions,
        originDeadlineTickId,
        () ->
            exists(
                selectOne()
                    .from(coordinator)
                    .where(
                        coordinator
                            .TENANT_ID
                            .eq(followup.TENANT_ID)
                            .and(coordinator.FOLLOWUP_ID.eq(followup.FOLLOWUP_ID))
                            .and(
                                coordinator.TARGET_GAME_INSTANCE_ID.eq(
                                    followup.TARGET_GAME_INSTANCE_ID))
                            .and(coordinator.TARGET_REGION_ID.eq(followup.TARGET_REGION_ID))
                            .and(coordinator.TARGET_REGION_EPOCH.eq(followup.TARGET_REGION_EPOCH))
                            .and(
                                coordinator.ORIGIN_GAME_INSTANCE_ID.eq(
                                    followup.ORIGIN_GAME_INSTANCE_ID))
                            .and(coordinator.ORIGIN_REGION_ID.eq(followup.ORIGIN_REGION_ID))
                            .and(coordinator.ORIGIN_REGION_EPOCH.eq(followup.ORIGIN_REGION_EPOCH))
                            .and(coordinator.ORIGIN_DEADLINE_TICK_ID.eq(originDeadlineTickId)))));
    addIfNotBlank(
        conditions,
        lateResultPolicy,
        () ->
            exists(
                selectOne()
                    .from(coordinator)
                    .where(
                        coordinator
                            .TENANT_ID
                            .eq(followup.TENANT_ID)
                            .and(coordinator.FOLLOWUP_ID.eq(followup.FOLLOWUP_ID))
                            .and(
                                coordinator.TARGET_GAME_INSTANCE_ID.eq(
                                    followup.TARGET_GAME_INSTANCE_ID))
                            .and(coordinator.TARGET_REGION_ID.eq(followup.TARGET_REGION_ID))
                            .and(coordinator.TARGET_REGION_EPOCH.eq(followup.TARGET_REGION_EPOCH))
                            .and(
                                coordinator.ORIGIN_GAME_INSTANCE_ID.eq(
                                    followup.ORIGIN_GAME_INSTANCE_ID))
                            .and(coordinator.ORIGIN_REGION_ID.eq(followup.ORIGIN_REGION_ID))
                            .and(coordinator.ORIGIN_REGION_EPOCH.eq(followup.ORIGIN_REGION_EPOCH))
                            .and(coordinator.LATE_RESULT_POLICY.eq(lateResultPolicy)))));

    return baseControlPlaneQuery(followup, currentOrigin, currentTarget, targetCommand)
        .where(conditions)
        .orderBy(followup.DUE_TICK_ID.asc(), followup.ID.asc())
        .limit(limitOrDefault(pageable, DEFAULT_CONTROL_PLANE_LIMIT))
        .offset(offsetOrZero(pageable))
        .fetch(this::toEntity);
  }

  public List<RemoteFollowup> saveAll(Iterable<RemoteFollowup> entities) {
    List<RemoteFollowup> saved = new ArrayList<>();
    for (RemoteFollowup entity : entities) {
      saved.add(save(entity));
    }
    return List.copyOf(saved);
  }

  public RemoteFollowup save(RemoteFollowup entity) {
    if (entity.getId() == null) {
      RemoteFollowupRecord record = dsl.newRecord(REMOTE_FOLLOWUP);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(REMOTE_FOLLOWUP)
            .set(REMOTE_FOLLOWUP.FOLLOWUP_ID, entity.getFollowupId())
            .set(REMOTE_FOLLOWUP.TENANT_ID, entity.getTenantId())
            .set(REMOTE_FOLLOWUP.ORIGIN_GAME_INSTANCE_ID, entity.getOriginGameInstanceId())
            .set(REMOTE_FOLLOWUP.ORIGIN_REGION_ID, entity.getOriginRegionId())
            .set(REMOTE_FOLLOWUP.ORIGIN_REGION_EPOCH, entity.getOriginRegionEpoch())
            .set(REMOTE_FOLLOWUP.TARGET_GAME_INSTANCE_ID, entity.getTargetGameInstanceId())
            .set(REMOTE_FOLLOWUP.TARGET_REGION_ID, entity.getTargetRegionId())
            .set(REMOTE_FOLLOWUP.TARGET_REGION_EPOCH, entity.getTargetRegionEpoch())
            .set(REMOTE_FOLLOWUP.DUE_TICK_ID, entity.getDueTickId())
            .set(REMOTE_FOLLOWUP.EFFECT_KEY, entity.getEffectKey())
            .set(REMOTE_FOLLOWUP.TARGET_ENTITY_ID, entity.getTargetEntityId())
            .set(REMOTE_FOLLOWUP.CLAIM_TARGET_AGGREGATE, entity.getClaimTargetAggregate())
            .set(REMOTE_FOLLOWUP.STATUS, entity.getStatus())
            .set(REMOTE_FOLLOWUP.CLAIMED_TICK_BATCH_ID, entity.getClaimedTickBatchId())
            .set(REMOTE_FOLLOWUP.CLAIM_ORDINAL, entity.getClaimOrdinal())
            .set(REMOTE_FOLLOWUP.QUEUE_SOURCE_KIND, entity.getQueueSourceKind())
            .set(REMOTE_FOLLOWUP.QUEUE_SOURCE_STATE, entity.getQueueSourceState())
            .set(REMOTE_FOLLOWUP.QUEUE_SOURCE_ORDINAL, entity.getQueueSourceOrdinal())
            .set(REMOTE_FOLLOWUP.QUEUE_SOURCE_DUE_TICK_ID, entity.getQueueSourceDueTickId())
            .set(REMOTE_FOLLOWUP.QUEUE_SOURCE_DUE_AT_MS, entity.getQueueSourceDueAtMs())
            .set(REMOTE_FOLLOWUP.PAYLOAD_JSON, entity.getPayloadJson())
            .set(REMOTE_FOLLOWUP.PAYLOAD_KIND, entity.getPayloadKind())
            .set(REMOTE_FOLLOWUP.REQUESTED_COMMAND, entity.getRequestedCommand())
            .set(REMOTE_FOLLOWUP.REQUIRES_SOLO_TICK, entity.isRequiresSoloTick())
            .set(REMOTE_FOLLOWUP.ORIGIN_SOURCE_KIND, entity.getOriginSourceKind())
            .set(REMOTE_FOLLOWUP.ORIGIN_SOURCE_STATE, entity.getOriginSourceState())
            .set(REMOTE_FOLLOWUP.ORIGIN_SOURCE_ORDINAL, entity.getOriginSourceOrdinal())
            .set(REMOTE_FOLLOWUP.ORIGIN_SOURCE_DUE_TICK_ID, entity.getOriginSourceDueTickId())
            .set(REMOTE_FOLLOWUP.ORIGIN_SOURCE_DUE_AT_MS, entity.getOriginSourceDueAtMs())
            .set(REMOTE_FOLLOWUP.EVENT_TYPE, entity.getEventType())
            .set(REMOTE_FOLLOWUP.EVENT_SCHEMA_VERSION, entity.getEventSchemaVersion())
            .set(REMOTE_FOLLOWUP.SCRIPT_EVENT_ID, entity.getScriptEventId())
            .set(REMOTE_FOLLOWUP.TRIGGER_MODE, entity.getTriggerMode())
            .set(REMOTE_FOLLOWUP.READ_SNAPSHOT_TOKEN, entity.getReadSnapshotToken())
            .set(REMOTE_FOLLOWUP.EVENT_PAYLOAD_JSON, entity.getEventPayloadJson())
            .set(REMOTE_FOLLOWUP.FAILURE_CODE, entity.getFailureCode())
            .set(REMOTE_FOLLOWUP.FAILURE_MESSAGE, entity.getFailureMessage())
            .set(REMOTE_FOLLOWUP.PLAYABLE_STATE_SCOPE, entity.getPlayableStateScope())
            .set(REMOTE_FOLLOWUP.WORLD_SLUG, entity.getWorldSlug())
            .set(REMOTE_FOLLOWUP.REALM_SLUG, entity.getRealmSlug())
            .set(REMOTE_FOLLOWUP.POINTER_VERSION, entity.getPointerVersion())
            .set(REMOTE_FOLLOWUP.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
            .set(REMOTE_FOLLOWUP.PLUGIN_ID, entity.getPluginId())
            .set(REMOTE_FOLLOWUP.PLUGIN_VERSION_ID, entity.getPluginVersionId())
            .set(REMOTE_FOLLOWUP.COMMAND_ID, entity.getCommandId())
            .set(REMOTE_FOLLOWUP.AUTOMATION_DISPATCH_ID, entity.getAutomationDispatchId())
            .set(REMOTE_FOLLOWUP.AUTOMATION_WORK_ITEM_ID, entity.getAutomationWorkItemId())
            .set(REMOTE_FOLLOWUP.SCRIPT_ID, entity.getScriptId())
            .set(REMOTE_FOLLOWUP.CREATED_AT, toOffsetDateTime(entity.getCreatedAt()))
            .set(REMOTE_FOLLOWUP.UPDATED_AT, toOffsetDateTime(entity.getUpdatedAt()))
            .where(REMOTE_FOLLOWUP.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("Failed to update remote_followup id=" + entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private SelectJoinStep<Record> baseControlPlaneQuery(
      net.firedevops.firemud.gamesession.jooq.tables.RemoteFollowup followup,
      net.firedevops.firemud.gamesession.jooq.tables.RuntimeRegionStatus currentOrigin,
      net.firedevops.firemud.gamesession.jooq.tables.RuntimeRegionStatus currentTarget,
      net.firedevops.firemud.gamesession.jooq.tables.GameplayCommand targetCommand) {
    return dsl.select(followup.fields())
        .from(followup)
        .leftJoin(currentOrigin)
        .on(
            currentOrigin
                .TENANT_ID
                .eq(followup.TENANT_ID)
                .and(currentOrigin.GAME_INSTANCE_ID.eq(followup.ORIGIN_GAME_INSTANCE_ID)))
        .leftJoin(currentTarget)
        .on(
            currentTarget
                .TENANT_ID
                .eq(followup.TENANT_ID)
                .and(currentTarget.GAME_INSTANCE_ID.eq(followup.TARGET_GAME_INSTANCE_ID)))
        .leftJoin(targetCommand)
        .on(
            targetCommand
                .TENANT_ID
                .eq(followup.TENANT_ID)
                .and(targetCommand.GAME_INSTANCE_ID.eq(followup.TARGET_GAME_INSTANCE_ID))
                .and(targetCommand.REGION_ID.isNotDistinctFrom(followup.TARGET_REGION_ID))
                .and(targetCommand.REGION_EPOCH.isNotDistinctFrom(followup.TARGET_REGION_EPOCH))
                .and(targetCommand.REMOTE_FOLLOWUP_ID.eq(followup.FOLLOWUP_ID)));
  }

  private Optional<RemoteFollowup> findById(Long id) {
    return dsl.selectFrom(REMOTE_FOLLOWUP)
        .where(REMOTE_FOLLOWUP.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(RemoteFollowupRecord record, RemoteFollowup entity) {
    record.setFollowupId(entity.getFollowupId());
    record.setTenantId(entity.getTenantId());
    record.setOriginGameInstanceId(entity.getOriginGameInstanceId());
    record.setOriginRegionId(entity.getOriginRegionId());
    record.setOriginRegionEpoch(entity.getOriginRegionEpoch());
    record.setTargetGameInstanceId(entity.getTargetGameInstanceId());
    record.setTargetRegionId(entity.getTargetRegionId());
    record.setTargetRegionEpoch(entity.getTargetRegionEpoch());
    record.setDueTickId(entity.getDueTickId());
    record.setEffectKey(entity.getEffectKey());
    record.setTargetEntityId(entity.getTargetEntityId());
    record.setClaimTargetAggregate(entity.getClaimTargetAggregate());
    record.setStatus(entity.getStatus());
    record.setClaimedTickBatchId(entity.getClaimedTickBatchId());
    record.setClaimOrdinal(entity.getClaimOrdinal());
    record.setQueueSourceKind(entity.getQueueSourceKind());
    record.setQueueSourceState(entity.getQueueSourceState());
    record.setQueueSourceOrdinal(entity.getQueueSourceOrdinal());
    record.setQueueSourceDueTickId(entity.getQueueSourceDueTickId());
    record.setQueueSourceDueAtMs(entity.getQueueSourceDueAtMs());
    record.setPayloadJson(entity.getPayloadJson());
    record.setPayloadKind(entity.getPayloadKind());
    record.setRequestedCommand(entity.getRequestedCommand());
    record.setRequiresSoloTick(entity.isRequiresSoloTick());
    record.setOriginSourceKind(entity.getOriginSourceKind());
    record.setOriginSourceState(entity.getOriginSourceState());
    record.setOriginSourceOrdinal(entity.getOriginSourceOrdinal());
    record.setOriginSourceDueTickId(entity.getOriginSourceDueTickId());
    record.setOriginSourceDueAtMs(entity.getOriginSourceDueAtMs());
    record.setEventType(entity.getEventType());
    record.setEventSchemaVersion(entity.getEventSchemaVersion());
    record.setScriptEventId(entity.getScriptEventId());
    record.setTriggerMode(entity.getTriggerMode());
    record.setReadSnapshotToken(entity.getReadSnapshotToken());
    record.setEventPayloadJson(entity.getEventPayloadJson());
    record.setFailureCode(entity.getFailureCode());
    record.setFailureMessage(entity.getFailureMessage());
    record.setPlayableStateScope(entity.getPlayableStateScope());
    record.setWorldSlug(entity.getWorldSlug());
    record.setRealmSlug(entity.getRealmSlug());
    record.setPointerVersion(entity.getPointerVersion());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setPluginId(entity.getPluginId());
    record.setPluginVersionId(entity.getPluginVersionId());
    record.setCommandId(entity.getCommandId());
    record.setAutomationDispatchId(entity.getAutomationDispatchId());
    record.setAutomationWorkItemId(entity.getAutomationWorkItemId());
    record.setScriptId(entity.getScriptId());
    record.setCreatedAt(toOffsetDateTime(entity.getCreatedAt()));
    record.setUpdatedAt(toOffsetDateTime(entity.getUpdatedAt()));
  }

  private RemoteFollowup toEntity(Record record) {
    RemoteFollowup entity = new RemoteFollowup();
    entity.setId(record.get(REMOTE_FOLLOWUP.ID));
    entity.setFollowupId(record.get(REMOTE_FOLLOWUP.FOLLOWUP_ID));
    entity.setTenantId(record.get(REMOTE_FOLLOWUP.TENANT_ID));
    entity.setOriginGameInstanceId(record.get(REMOTE_FOLLOWUP.ORIGIN_GAME_INSTANCE_ID));
    entity.setOriginRegionId(record.get(REMOTE_FOLLOWUP.ORIGIN_REGION_ID));
    entity.setOriginRegionEpoch(record.get(REMOTE_FOLLOWUP.ORIGIN_REGION_EPOCH));
    entity.setTargetGameInstanceId(record.get(REMOTE_FOLLOWUP.TARGET_GAME_INSTANCE_ID));
    entity.setTargetRegionId(record.get(REMOTE_FOLLOWUP.TARGET_REGION_ID));
    entity.setTargetRegionEpoch(record.get(REMOTE_FOLLOWUP.TARGET_REGION_EPOCH));
    entity.setDueTickId(record.get(REMOTE_FOLLOWUP.DUE_TICK_ID));
    entity.setEffectKey(record.get(REMOTE_FOLLOWUP.EFFECT_KEY));
    entity.setTargetEntityId(record.get(REMOTE_FOLLOWUP.TARGET_ENTITY_ID));
    entity.setClaimTargetAggregate(record.get(REMOTE_FOLLOWUP.CLAIM_TARGET_AGGREGATE));
    entity.setStatus(record.get(REMOTE_FOLLOWUP.STATUS));
    entity.setClaimedTickBatchId(record.get(REMOTE_FOLLOWUP.CLAIMED_TICK_BATCH_ID));
    entity.setClaimOrdinal(record.get(REMOTE_FOLLOWUP.CLAIM_ORDINAL));
    entity.setQueueSourceKind(record.get(REMOTE_FOLLOWUP.QUEUE_SOURCE_KIND));
    entity.setQueueSourceState(record.get(REMOTE_FOLLOWUP.QUEUE_SOURCE_STATE));
    entity.setQueueSourceOrdinal(record.get(REMOTE_FOLLOWUP.QUEUE_SOURCE_ORDINAL));
    entity.setQueueSourceDueTickId(record.get(REMOTE_FOLLOWUP.QUEUE_SOURCE_DUE_TICK_ID));
    entity.setQueueSourceDueAtMs(record.get(REMOTE_FOLLOWUP.QUEUE_SOURCE_DUE_AT_MS));
    entity.setPayloadJson(record.get(REMOTE_FOLLOWUP.PAYLOAD_JSON));
    entity.setPayloadKind(record.get(REMOTE_FOLLOWUP.PAYLOAD_KIND));
    entity.setRequestedCommand(record.get(REMOTE_FOLLOWUP.REQUESTED_COMMAND));
    entity.setRequiresSoloTick(Boolean.TRUE.equals(record.get(REMOTE_FOLLOWUP.REQUIRES_SOLO_TICK)));
    entity.setOriginSourceKind(record.get(REMOTE_FOLLOWUP.ORIGIN_SOURCE_KIND));
    entity.setOriginSourceState(record.get(REMOTE_FOLLOWUP.ORIGIN_SOURCE_STATE));
    entity.setOriginSourceOrdinal(record.get(REMOTE_FOLLOWUP.ORIGIN_SOURCE_ORDINAL));
    entity.setOriginSourceDueTickId(record.get(REMOTE_FOLLOWUP.ORIGIN_SOURCE_DUE_TICK_ID));
    entity.setOriginSourceDueAtMs(record.get(REMOTE_FOLLOWUP.ORIGIN_SOURCE_DUE_AT_MS));
    entity.setEventType(record.get(REMOTE_FOLLOWUP.EVENT_TYPE));
    entity.setEventSchemaVersion(record.get(REMOTE_FOLLOWUP.EVENT_SCHEMA_VERSION));
    entity.setScriptEventId(record.get(REMOTE_FOLLOWUP.SCRIPT_EVENT_ID));
    entity.setTriggerMode(record.get(REMOTE_FOLLOWUP.TRIGGER_MODE));
    entity.setReadSnapshotToken(record.get(REMOTE_FOLLOWUP.READ_SNAPSHOT_TOKEN));
    entity.setEventPayloadJson(record.get(REMOTE_FOLLOWUP.EVENT_PAYLOAD_JSON));
    entity.setFailureCode(record.get(REMOTE_FOLLOWUP.FAILURE_CODE));
    entity.setFailureMessage(record.get(REMOTE_FOLLOWUP.FAILURE_MESSAGE));
    entity.setPlayableStateScope(record.get(REMOTE_FOLLOWUP.PLAYABLE_STATE_SCOPE));
    entity.setWorldSlug(record.get(REMOTE_FOLLOWUP.WORLD_SLUG));
    entity.setRealmSlug(record.get(REMOTE_FOLLOWUP.REALM_SLUG));
    entity.setPointerVersion(record.get(REMOTE_FOLLOWUP.POINTER_VERSION));
    entity.setScriptPatchVersion(record.get(REMOTE_FOLLOWUP.SCRIPT_PATCH_VERSION));
    entity.setPluginId(record.get(REMOTE_FOLLOWUP.PLUGIN_ID));
    entity.setPluginVersionId(record.get(REMOTE_FOLLOWUP.PLUGIN_VERSION_ID));
    entity.setCommandId(record.get(REMOTE_FOLLOWUP.COMMAND_ID));
    entity.setAutomationDispatchId(record.get(REMOTE_FOLLOWUP.AUTOMATION_DISPATCH_ID));
    entity.setAutomationWorkItemId(record.get(REMOTE_FOLLOWUP.AUTOMATION_WORK_ITEM_ID));
    entity.setScriptId(record.get(REMOTE_FOLLOWUP.SCRIPT_ID));
    entity.setCreatedAt(toInstant(record.get(REMOTE_FOLLOWUP.CREATED_AT)));
    entity.setUpdatedAt(toInstant(record.get(REMOTE_FOLLOWUP.UPDATED_AT)));
    return entity;
  }
}
