package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.limitOrDefault;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.offsetOrZero;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toOffsetDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.GameplayCommand.GAMEPLAY_COMMAND;
import static net.firedevops.firemud.gamesession.jooq.tables.RemoteCommandCoordinator.REMOTE_COMMAND_COORDINATOR;
import static net.firedevops.firemud.gamesession.jooq.tables.RemoteFollowup.REMOTE_FOLLOWUP;
import static net.firedevops.firemud.gamesession.jooq.tables.RemoteFollowupResult.REMOTE_FOLLOWUP_RESULT;
import static net.firedevops.firemud.gamesession.jooq.tables.RuntimeRegionStatus.RUNTIME_REGION_STATUS;
import static net.firedevops.firemud.gamesession.repository.JooqGameSessionRepositorySupport.addIfNonNull;
import static net.firedevops.firemud.gamesession.repository.JooqGameSessionRepositorySupport.addIfNotBlank;
import static net.firedevops.firemud.gamesession.repository.JooqGameSessionRepositorySupport.addIfPositive;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.RemoteCommandCoordinator;
import net.firedevops.firemud.gamesession.jooq.tables.records.RemoteCommandCoordinatorRecord;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectJoinStep;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class RemoteCommandCoordinatorRepository {
  private static final int DEFAULT_CONTROL_PLANE_LIMIT = 200;

  private final DSLContext dsl;

  public RemoteCommandCoordinatorRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<RemoteCommandCoordinator> findByTenantIdAndCommandId(
      Long tenantId, String commandId) {
    return dsl.selectFrom(REMOTE_COMMAND_COORDINATOR)
        .where(
            REMOTE_COMMAND_COORDINATOR
                .TENANT_ID
                .eq(tenantId)
                .and(REMOTE_COMMAND_COORDINATOR.COMMAND_ID.eq(commandId)))
        .fetchOptional(this::toEntity);
  }

  public Optional<RemoteCommandCoordinator> findByTenantIdAndCoordinatorId(
      Long tenantId, String coordinatorId) {
    return dsl.selectFrom(REMOTE_COMMAND_COORDINATOR)
        .where(
            REMOTE_COMMAND_COORDINATOR
                .TENANT_ID
                .eq(tenantId)
                .and(REMOTE_COMMAND_COORDINATOR.COORDINATOR_ID.eq(coordinatorId)))
        .fetchOptional(this::toEntity);
  }

  public Optional<RemoteCommandCoordinator> findByTenantIdAndFollowupId(
      Long tenantId, String followupId) {
    return dsl.selectFrom(REMOTE_COMMAND_COORDINATOR)
        .where(
            REMOTE_COMMAND_COORDINATOR
                .TENANT_ID
                .eq(tenantId)
                .and(REMOTE_COMMAND_COORDINATOR.FOLLOWUP_ID.eq(followupId)))
        .fetchOptional(this::toEntity);
  }

  public List<RemoteCommandCoordinator> findByTenantIdAndFollowupIdIn(
      Long tenantId, Collection<String> followupIds) {
    if (followupIds == null || followupIds.isEmpty()) {
      return List.of();
    }
    return dsl.selectFrom(REMOTE_COMMAND_COORDINATOR)
        .where(
            REMOTE_COMMAND_COORDINATOR
                .TENANT_ID
                .eq(tenantId)
                .and(REMOTE_COMMAND_COORDINATOR.FOLLOWUP_ID.in(followupIds)))
        .orderBy(REMOTE_COMMAND_COORDINATOR.ID.asc())
        .fetch(this::toEntity);
  }

  public List<RemoteCommandCoordinator> findByTenantIdAndCoordinatorIdIn(
      Long tenantId, Collection<String> coordinatorIds) {
    if (coordinatorIds == null || coordinatorIds.isEmpty()) {
      return List.of();
    }
    return dsl.selectFrom(REMOTE_COMMAND_COORDINATOR)
        .where(
            REMOTE_COMMAND_COORDINATOR
                .TENANT_ID
                .eq(tenantId)
                .and(REMOTE_COMMAND_COORDINATOR.COORDINATOR_ID.in(coordinatorIds)))
        .orderBy(REMOTE_COMMAND_COORDINATOR.ID.asc())
        .fetch(this::toEntity);
  }

  public List<RemoteCommandCoordinator> findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
      Long tenantId, String originRegionId, String state) {
    return dsl.selectFrom(REMOTE_COMMAND_COORDINATOR)
        .where(
            REMOTE_COMMAND_COORDINATOR
                .TENANT_ID
                .eq(tenantId)
                .and(REMOTE_COMMAND_COORDINATOR.ORIGIN_REGION_ID.eq(originRegionId))
                .and(REMOTE_COMMAND_COORDINATOR.STATE.eq(state)))
        .orderBy(REMOTE_COMMAND_COORDINATOR.UPDATED_AT.desc(), REMOTE_COMMAND_COORDINATOR.ID.desc())
        .fetch(this::toEntity);
  }

  public List<RemoteCommandCoordinator> findByTenantIdAndOriginRegionIdOrderByUpdatedAtDesc(
      Long tenantId, String originRegionId) {
    return dsl.selectFrom(REMOTE_COMMAND_COORDINATOR)
        .where(
            REMOTE_COMMAND_COORDINATOR
                .TENANT_ID
                .eq(tenantId)
                .and(REMOTE_COMMAND_COORDINATOR.ORIGIN_REGION_ID.eq(originRegionId)))
        .orderBy(REMOTE_COMMAND_COORDINATOR.UPDATED_AT.desc(), REMOTE_COMMAND_COORDINATOR.ID.desc())
        .fetch(this::toEntity);
  }

  public List<RemoteCommandCoordinator> findForControlPlane(
      Long tenantId,
      Long originGameInstanceId,
      String originRegionId,
      long originRegionEpoch,
      Long targetGameInstanceId,
      String targetRegionId,
      long targetRegionEpoch,
      String currentOriginRuntimeRegionId,
      long currentOriginRuntimeRegionEpoch,
      Long currentOriginRuntimeGameInstanceId,
      String currentTargetRuntimeRegionId,
      long currentTargetRuntimeRegionEpoch,
      Long currentTargetRuntimeGameInstanceId,
      String state,
      String followupId,
      String scriptId,
      String pluginId,
      String scriptPatchVersion,
      String pluginVersionId,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      Long pointerVersion,
      String targetEntityId,
      String claimTargetAggregate,
      String effectKey,
      String payloadKind,
      String originSourceKind,
      String originSourceState,
      String automationWorkItemId,
      String eventType,
      String scriptEventId,
      String lateResultPolicy,
      String executionOutcome,
      String gameplayResult,
      String followupStatus,
      String followupClaimedTickBatchId,
      Boolean followupRequiresSoloTick,
      String followupQueueSourceKind,
      String followupQueueSourceState,
      long followupQueueSourceOrdinal,
      long followupQueueSourceDueTickId,
      long followupQueueSourceDueAtMs,
      String automationDispatchId,
      String commandId,
      String targetCommandId,
      String targetCommandExecutionOutcome,
      String targetCommandGameplayResult,
      String latestResultOutcome,
      String latestResultErrorCode,
      Pageable pageable) {
    var coordinator = REMOTE_COMMAND_COORDINATOR.as("coordinator");
    var linkedFollowup = REMOTE_FOLLOWUP.as("linkedFollowup");
    var currentOrigin = RUNTIME_REGION_STATUS.as("currentOrigin");
    var currentTarget = RUNTIME_REGION_STATUS.as("currentTarget");
    var targetCommand = GAMEPLAY_COMMAND.as("targetCommand");
    var result = REMOTE_FOLLOWUP_RESULT.as("result");
    var latest = REMOTE_FOLLOWUP_RESULT.as("latest");

    List<Condition> conditions = new ArrayList<>();
    conditions.add(coordinator.TENANT_ID.eq(tenantId));
    addIfNonNull(
        conditions,
        originGameInstanceId,
        () -> coordinator.ORIGIN_GAME_INSTANCE_ID.eq(originGameInstanceId));
    addIfNotBlank(
        conditions, originRegionId, () -> coordinator.ORIGIN_REGION_ID.eq(originRegionId));
    addIfPositive(
        conditions, originRegionEpoch, () -> coordinator.ORIGIN_REGION_EPOCH.eq(originRegionEpoch));
    addIfNonNull(
        conditions,
        targetGameInstanceId,
        () -> coordinator.TARGET_GAME_INSTANCE_ID.eq(targetGameInstanceId));
    addIfNotBlank(
        conditions, targetRegionId, () -> coordinator.TARGET_REGION_ID.eq(targetRegionId));
    addIfPositive(
        conditions, targetRegionEpoch, () -> coordinator.TARGET_REGION_EPOCH.eq(targetRegionEpoch));
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
    addIfNotBlank(conditions, state, () -> coordinator.STATE.eq(state));
    addIfNotBlank(conditions, followupId, () -> coordinator.FOLLOWUP_ID.eq(followupId));
    addIfNotBlank(conditions, scriptId, () -> coordinator.SCRIPT_ID.eq(scriptId));
    addIfNotBlank(conditions, pluginId, () -> coordinator.PLUGIN_ID.eq(pluginId));
    addIfNotBlank(
        conditions,
        scriptPatchVersion,
        () -> coordinator.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion));
    addIfNotBlank(
        conditions, pluginVersionId, () -> coordinator.PLUGIN_VERSION_ID.eq(pluginVersionId));
    addIfNotBlank(
        conditions,
        playableStateScope,
        () -> coordinator.PLAYABLE_STATE_SCOPE.eq(playableStateScope));
    addIfNotBlank(conditions, worldSlug, () -> coordinator.WORLD_SLUG.eq(worldSlug));
    addIfNotBlank(conditions, realmSlug, () -> coordinator.REALM_SLUG.eq(realmSlug));
    addIfNonNull(conditions, pointerVersion, () -> coordinator.POINTER_VERSION.eq(pointerVersion));
    addIfNotBlank(
        conditions, targetEntityId, () -> linkedFollowup.TARGET_ENTITY_ID.eq(targetEntityId));
    addIfNotBlank(
        conditions,
        claimTargetAggregate,
        () -> linkedFollowup.CLAIM_TARGET_AGGREGATE.eq(claimTargetAggregate));
    addIfNotBlank(conditions, effectKey, () -> linkedFollowup.EFFECT_KEY.eq(effectKey));
    addIfNotBlank(conditions, payloadKind, () -> linkedFollowup.PAYLOAD_KIND.eq(payloadKind));
    addIfNotBlank(
        conditions, originSourceKind, () -> linkedFollowup.ORIGIN_SOURCE_KIND.eq(originSourceKind));
    addIfNotBlank(
        conditions,
        originSourceState,
        () -> linkedFollowup.ORIGIN_SOURCE_STATE.eq(originSourceState));
    addIfNotBlank(
        conditions,
        automationWorkItemId,
        () -> coordinator.AUTOMATION_WORK_ITEM_ID.eq(automationWorkItemId));
    addIfNotBlank(conditions, eventType, () -> linkedFollowup.EVENT_TYPE.eq(eventType));
    addIfNotBlank(
        conditions, scriptEventId, () -> linkedFollowup.SCRIPT_EVENT_ID.eq(scriptEventId));
    addIfNotBlank(
        conditions,
        automationDispatchId,
        () -> coordinator.AUTOMATION_DISPATCH_ID.eq(automationDispatchId));
    addIfNotBlank(conditions, commandId, () -> coordinator.COMMAND_ID.eq(commandId));
    addIfNotBlank(
        conditions, lateResultPolicy, () -> coordinator.LATE_RESULT_POLICY.eq(lateResultPolicy));
    addIfNotBlank(
        conditions, executionOutcome, () -> coordinator.EXECUTION_OUTCOME.eq(executionOutcome));
    addIfNotBlank(conditions, gameplayResult, () -> coordinator.GAMEPLAY_RESULT.eq(gameplayResult));
    addIfNotBlank(conditions, followupStatus, () -> linkedFollowup.STATUS.eq(followupStatus));
    addIfNotBlank(
        conditions,
        followupClaimedTickBatchId,
        () -> linkedFollowup.CLAIMED_TICK_BATCH_ID.eq(followupClaimedTickBatchId));
    addIfNonNull(
        conditions,
        followupRequiresSoloTick,
        () -> linkedFollowup.REQUIRES_SOLO_TICK.eq(followupRequiresSoloTick));
    addIfNotBlank(
        conditions,
        followupQueueSourceKind,
        () -> linkedFollowup.QUEUE_SOURCE_KIND.eq(followupQueueSourceKind));
    addIfNotBlank(
        conditions,
        followupQueueSourceState,
        () -> linkedFollowup.QUEUE_SOURCE_STATE.eq(followupQueueSourceState));
    addIfPositive(
        conditions,
        followupQueueSourceOrdinal,
        () -> linkedFollowup.QUEUE_SOURCE_ORDINAL.eq(followupQueueSourceOrdinal));
    addIfPositive(
        conditions,
        followupQueueSourceDueTickId,
        () -> linkedFollowup.QUEUE_SOURCE_DUE_TICK_ID.eq(followupQueueSourceDueTickId));
    addIfPositive(
        conditions,
        followupQueueSourceDueAtMs,
        () -> linkedFollowup.QUEUE_SOURCE_DUE_AT_MS.eq(followupQueueSourceDueAtMs));
    addIfNotBlank(conditions, targetCommandId, () -> targetCommand.COMMAND_ID.eq(targetCommandId));
    addIfNotBlank(
        conditions,
        targetCommandExecutionOutcome,
        () -> targetCommand.EXECUTION_OUTCOME.eq(targetCommandExecutionOutcome));
    addIfNotBlank(
        conditions,
        targetCommandGameplayResult,
        () -> targetCommand.GAMEPLAY_RESULT.eq(targetCommandGameplayResult));
    addIfNotBlank(
        conditions,
        latestResultOutcome,
        () ->
            latestResultExists(
                coordinator, result.OUTCOME.eq(latestResultOutcome), result, latest));
    addIfNotBlank(
        conditions,
        latestResultErrorCode,
        () ->
            latestResultExists(
                coordinator, result.RESULT_ERROR_CODE.eq(latestResultErrorCode), result, latest));

    return baseControlPlaneQuery(
            coordinator, linkedFollowup, currentOrigin, currentTarget, targetCommand)
        .where(conditions)
        .orderBy(coordinator.UPDATED_AT.desc(), coordinator.ID.desc())
        .limit(limitOrDefault(pageable, DEFAULT_CONTROL_PLANE_LIMIT))
        .offset(offsetOrZero(pageable))
        .fetch(this::toEntity);
  }

  public RemoteCommandCoordinator save(RemoteCommandCoordinator entity) {
    if (entity.getId() == null) {
      RemoteCommandCoordinatorRecord record = dsl.newRecord(REMOTE_COMMAND_COORDINATOR);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(REMOTE_COMMAND_COORDINATOR)
            .set(REMOTE_COMMAND_COORDINATOR.COORDINATOR_ID, entity.getCoordinatorId())
            .set(REMOTE_COMMAND_COORDINATOR.TENANT_ID, entity.getTenantId())
            .set(REMOTE_COMMAND_COORDINATOR.COMMAND_ID, entity.getCommandId())
            .set(REMOTE_COMMAND_COORDINATOR.FOLLOWUP_ID, entity.getFollowupId())
            .set(
                REMOTE_COMMAND_COORDINATOR.ORIGIN_GAME_INSTANCE_ID,
                entity.getOriginGameInstanceId())
            .set(REMOTE_COMMAND_COORDINATOR.ORIGIN_REGION_ID, entity.getOriginRegionId())
            .set(REMOTE_COMMAND_COORDINATOR.ORIGIN_REGION_EPOCH, entity.getOriginRegionEpoch())
            .set(
                REMOTE_COMMAND_COORDINATOR.TARGET_GAME_INSTANCE_ID,
                entity.getTargetGameInstanceId())
            .set(REMOTE_COMMAND_COORDINATOR.TARGET_REGION_ID, entity.getTargetRegionId())
            .set(REMOTE_COMMAND_COORDINATOR.TARGET_REGION_EPOCH, entity.getTargetRegionEpoch())
            .set(REMOTE_COMMAND_COORDINATOR.TARGET_DUE_TICK_ID, entity.getTargetDueTickId())
            .set(
                REMOTE_COMMAND_COORDINATOR.ORIGIN_DEADLINE_REGION_EPOCH,
                entity.getOriginDeadlineRegionEpoch())
            .set(
                REMOTE_COMMAND_COORDINATOR.ORIGIN_DEADLINE_TICK_ID,
                entity.getOriginDeadlineTickId())
            .set(REMOTE_COMMAND_COORDINATOR.STATE, entity.getState())
            .set(REMOTE_COMMAND_COORDINATOR.LATE_RESULT_POLICY, entity.getLateResultPolicy())
            .set(REMOTE_COMMAND_COORDINATOR.EXECUTION_OUTCOME, entity.getExecutionOutcome())
            .set(REMOTE_COMMAND_COORDINATOR.GAMEPLAY_RESULT, entity.getGameplayResult())
            .set(REMOTE_COMMAND_COORDINATOR.PLAYABLE_STATE_SCOPE, entity.getPlayableStateScope())
            .set(REMOTE_COMMAND_COORDINATOR.WORLD_SLUG, entity.getWorldSlug())
            .set(REMOTE_COMMAND_COORDINATOR.REALM_SLUG, entity.getRealmSlug())
            .set(REMOTE_COMMAND_COORDINATOR.POINTER_VERSION, entity.getPointerVersion())
            .set(REMOTE_COMMAND_COORDINATOR.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
            .set(REMOTE_COMMAND_COORDINATOR.SCRIPT_PIN_EPOCH, entity.getScriptPinEpoch())
            .set(REMOTE_COMMAND_COORDINATOR.PLUGIN_ID, entity.getPluginId())
            .set(REMOTE_COMMAND_COORDINATOR.PLUGIN_VERSION_ID, entity.getPluginVersionId())
            .set(
                REMOTE_COMMAND_COORDINATOR.AUTOMATION_DISPATCH_ID, entity.getAutomationDispatchId())
            .set(
                REMOTE_COMMAND_COORDINATOR.AUTOMATION_WORK_ITEM_ID,
                entity.getAutomationWorkItemId())
            .set(REMOTE_COMMAND_COORDINATOR.SCRIPT_ID, entity.getScriptId())
            .set(REMOTE_COMMAND_COORDINATOR.UPDATED_AT, toOffsetDateTime(entity.getUpdatedAt()))
            .where(REMOTE_COMMAND_COORDINATOR.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException(
          "Failed to update remote_command_coordinator id=" + entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private Condition latestResultExists(
      net.firedevops.firemud.gamesession.jooq.tables.RemoteCommandCoordinator coordinator,
      Condition resultCondition,
      net.firedevops.firemud.gamesession.jooq.tables.RemoteFollowupResult result,
      net.firedevops.firemud.gamesession.jooq.tables.RemoteFollowupResult latest) {
    Field<Long> latestId =
        select(latest.ID)
            .from(latest)
            .where(
                latest
                    .TENANT_ID
                    .eq(coordinator.TENANT_ID)
                    .and(latest.COORDINATOR_ID.eq(coordinator.COORDINATOR_ID))
                    .and(latest.FOLLOWUP_ID.eq(coordinator.FOLLOWUP_ID))
                    .and(latest.ORIGIN_GAME_INSTANCE_ID.eq(coordinator.ORIGIN_GAME_INSTANCE_ID))
                    .and(latest.ORIGIN_REGION_ID.eq(coordinator.ORIGIN_REGION_ID))
                    .and(latest.ORIGIN_REGION_EPOCH.eq(coordinator.ORIGIN_REGION_EPOCH))
                    .and(latest.TARGET_GAME_INSTANCE_ID.eq(coordinator.TARGET_GAME_INSTANCE_ID))
                    .and(latest.TARGET_REGION_ID.eq(coordinator.TARGET_REGION_ID))
                    .and(latest.TARGET_REGION_EPOCH.eq(coordinator.TARGET_REGION_EPOCH)))
            .orderBy(latest.OBSERVED_AT.desc(), latest.ID.desc())
            .limit(1)
            .asField();
    return exists(
        selectOne()
            .from(result)
            .where(
                result
                    .TENANT_ID
                    .eq(coordinator.TENANT_ID)
                    .and(result.COORDINATOR_ID.eq(coordinator.COORDINATOR_ID))
                    .and(result.FOLLOWUP_ID.eq(coordinator.FOLLOWUP_ID))
                    .and(result.ORIGIN_GAME_INSTANCE_ID.eq(coordinator.ORIGIN_GAME_INSTANCE_ID))
                    .and(result.ORIGIN_REGION_ID.eq(coordinator.ORIGIN_REGION_ID))
                    .and(result.ORIGIN_REGION_EPOCH.eq(coordinator.ORIGIN_REGION_EPOCH))
                    .and(result.TARGET_GAME_INSTANCE_ID.eq(coordinator.TARGET_GAME_INSTANCE_ID))
                    .and(result.TARGET_REGION_ID.eq(coordinator.TARGET_REGION_ID))
                    .and(result.TARGET_REGION_EPOCH.eq(coordinator.TARGET_REGION_EPOCH))
                    .and(resultCondition)
                    .and(result.ID.eq(latestId))));
  }

  private SelectJoinStep<Record> baseControlPlaneQuery(
      net.firedevops.firemud.gamesession.jooq.tables.RemoteCommandCoordinator coordinator,
      net.firedevops.firemud.gamesession.jooq.tables.RemoteFollowup linkedFollowup,
      net.firedevops.firemud.gamesession.jooq.tables.RuntimeRegionStatus currentOrigin,
      net.firedevops.firemud.gamesession.jooq.tables.RuntimeRegionStatus currentTarget,
      net.firedevops.firemud.gamesession.jooq.tables.GameplayCommand targetCommand) {
    return dsl.select(coordinator.fields())
        .from(coordinator)
        .leftJoin(linkedFollowup)
        .on(
            linkedFollowup
                .TENANT_ID
                .eq(coordinator.TENANT_ID)
                .and(linkedFollowup.ORIGIN_GAME_INSTANCE_ID.eq(coordinator.ORIGIN_GAME_INSTANCE_ID))
                .and(linkedFollowup.ORIGIN_REGION_ID.eq(coordinator.ORIGIN_REGION_ID))
                .and(linkedFollowup.ORIGIN_REGION_EPOCH.eq(coordinator.ORIGIN_REGION_EPOCH))
                .and(linkedFollowup.TARGET_GAME_INSTANCE_ID.eq(coordinator.TARGET_GAME_INSTANCE_ID))
                .and(linkedFollowup.TARGET_REGION_ID.eq(coordinator.TARGET_REGION_ID))
                .and(linkedFollowup.TARGET_REGION_EPOCH.eq(coordinator.TARGET_REGION_EPOCH))
                .and(linkedFollowup.FOLLOWUP_ID.eq(coordinator.FOLLOWUP_ID)))
        .leftJoin(currentOrigin)
        .on(
            currentOrigin
                .TENANT_ID
                .eq(coordinator.TENANT_ID)
                .and(currentOrigin.GAME_INSTANCE_ID.eq(coordinator.ORIGIN_GAME_INSTANCE_ID)))
        .leftJoin(currentTarget)
        .on(
            currentTarget
                .TENANT_ID
                .eq(coordinator.TENANT_ID)
                .and(currentTarget.GAME_INSTANCE_ID.eq(coordinator.TARGET_GAME_INSTANCE_ID)))
        .leftJoin(targetCommand)
        .on(
            targetCommand
                .TENANT_ID
                .eq(coordinator.TENANT_ID)
                .and(targetCommand.GAME_INSTANCE_ID.eq(coordinator.TARGET_GAME_INSTANCE_ID))
                .and(targetCommand.REGION_ID.isNotDistinctFrom(coordinator.TARGET_REGION_ID))
                .and(targetCommand.REGION_EPOCH.isNotDistinctFrom(coordinator.TARGET_REGION_EPOCH))
                .and(targetCommand.REMOTE_FOLLOWUP_ID.eq(coordinator.FOLLOWUP_ID)));
  }

  private Optional<RemoteCommandCoordinator> findById(Long id) {
    return dsl.selectFrom(REMOTE_COMMAND_COORDINATOR)
        .where(REMOTE_COMMAND_COORDINATOR.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(RemoteCommandCoordinatorRecord record, RemoteCommandCoordinator entity) {
    record.setCoordinatorId(entity.getCoordinatorId());
    record.setTenantId(entity.getTenantId());
    record.setCommandId(entity.getCommandId());
    record.setFollowupId(entity.getFollowupId());
    record.setOriginGameInstanceId(entity.getOriginGameInstanceId());
    record.setOriginRegionId(entity.getOriginRegionId());
    record.setOriginRegionEpoch(entity.getOriginRegionEpoch());
    record.setTargetGameInstanceId(entity.getTargetGameInstanceId());
    record.setTargetRegionId(entity.getTargetRegionId());
    record.setTargetRegionEpoch(entity.getTargetRegionEpoch());
    record.setTargetDueTickId(entity.getTargetDueTickId());
    record.setOriginDeadlineRegionEpoch(entity.getOriginDeadlineRegionEpoch());
    record.setOriginDeadlineTickId(entity.getOriginDeadlineTickId());
    record.setState(entity.getState());
    record.setLateResultPolicy(entity.getLateResultPolicy());
    record.setExecutionOutcome(entity.getExecutionOutcome());
    record.setGameplayResult(entity.getGameplayResult());
    record.setPlayableStateScope(entity.getPlayableStateScope());
    record.setWorldSlug(entity.getWorldSlug());
    record.setRealmSlug(entity.getRealmSlug());
    record.setPointerVersion(entity.getPointerVersion());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setScriptPinEpoch(entity.getScriptPinEpoch());
    record.setSourceScriptPatchVersion(entity.getSourceScriptPatchVersion());
    record.setSourceScriptPinEpoch(entity.getSourceScriptPinEpoch());
    record.setSourceScriptPinControlPlaneRequestId(entity.getSourceScriptPinControlPlaneRequestId());
    record.setTargetScriptPatchVersion(entity.getTargetScriptPatchVersion());
    record.setTargetScriptPinEpoch(entity.getTargetScriptPinEpoch());
    record.setTargetScriptPinControlPlaneRequestId(entity.getTargetScriptPinControlPlaneRequestId());
    record.setPluginId(entity.getPluginId());
    record.setPluginVersionId(entity.getPluginVersionId());
    record.setAutomationDispatchId(entity.getAutomationDispatchId());
    record.setAutomationWorkItemId(entity.getAutomationWorkItemId());
    record.setScriptId(entity.getScriptId());
    record.setUpdatedAt(toOffsetDateTime(entity.getUpdatedAt()));
  }

  private RemoteCommandCoordinator toEntity(Record record) {
    RemoteCommandCoordinator entity = new RemoteCommandCoordinator();
    entity.setId(record.get(REMOTE_COMMAND_COORDINATOR.ID));
    entity.setCoordinatorId(record.get(REMOTE_COMMAND_COORDINATOR.COORDINATOR_ID));
    entity.setTenantId(record.get(REMOTE_COMMAND_COORDINATOR.TENANT_ID));
    entity.setCommandId(record.get(REMOTE_COMMAND_COORDINATOR.COMMAND_ID));
    entity.setFollowupId(record.get(REMOTE_COMMAND_COORDINATOR.FOLLOWUP_ID));
    entity.setOriginGameInstanceId(record.get(REMOTE_COMMAND_COORDINATOR.ORIGIN_GAME_INSTANCE_ID));
    entity.setOriginRegionId(record.get(REMOTE_COMMAND_COORDINATOR.ORIGIN_REGION_ID));
    entity.setOriginRegionEpoch(record.get(REMOTE_COMMAND_COORDINATOR.ORIGIN_REGION_EPOCH));
    entity.setTargetGameInstanceId(record.get(REMOTE_COMMAND_COORDINATOR.TARGET_GAME_INSTANCE_ID));
    entity.setTargetRegionId(record.get(REMOTE_COMMAND_COORDINATOR.TARGET_REGION_ID));
    entity.setTargetRegionEpoch(record.get(REMOTE_COMMAND_COORDINATOR.TARGET_REGION_EPOCH));
    entity.setTargetDueTickId(record.get(REMOTE_COMMAND_COORDINATOR.TARGET_DUE_TICK_ID));
    entity.setOriginDeadlineRegionEpoch(
        record.get(REMOTE_COMMAND_COORDINATOR.ORIGIN_DEADLINE_REGION_EPOCH));
    entity.setOriginDeadlineTickId(record.get(REMOTE_COMMAND_COORDINATOR.ORIGIN_DEADLINE_TICK_ID));
    entity.setState(record.get(REMOTE_COMMAND_COORDINATOR.STATE));
    entity.setLateResultPolicy(record.get(REMOTE_COMMAND_COORDINATOR.LATE_RESULT_POLICY));
    entity.setExecutionOutcome(record.get(REMOTE_COMMAND_COORDINATOR.EXECUTION_OUTCOME));
    entity.setGameplayResult(record.get(REMOTE_COMMAND_COORDINATOR.GAMEPLAY_RESULT));
    entity.setPlayableStateScope(record.get(REMOTE_COMMAND_COORDINATOR.PLAYABLE_STATE_SCOPE));
    entity.setWorldSlug(record.get(REMOTE_COMMAND_COORDINATOR.WORLD_SLUG));
    entity.setRealmSlug(record.get(REMOTE_COMMAND_COORDINATOR.REALM_SLUG));
    entity.setPointerVersion(record.get(REMOTE_COMMAND_COORDINATOR.POINTER_VERSION));
    entity.setScriptPatchVersion(record.get(REMOTE_COMMAND_COORDINATOR.SCRIPT_PATCH_VERSION));
    entity.setScriptPinEpoch(record.get(REMOTE_COMMAND_COORDINATOR.SCRIPT_PIN_EPOCH));
    entity.setSourceScriptPatchVersion(
        record.get(REMOTE_COMMAND_COORDINATOR.SOURCE_SCRIPT_PATCH_VERSION));
    entity.setSourceScriptPinEpoch(
        record.get(REMOTE_COMMAND_COORDINATOR.SOURCE_SCRIPT_PIN_EPOCH));
    entity.setSourceScriptPinControlPlaneRequestId(
        record.get(REMOTE_COMMAND_COORDINATOR.SOURCE_SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID));
    entity.setTargetScriptPatchVersion(
        record.get(REMOTE_COMMAND_COORDINATOR.TARGET_SCRIPT_PATCH_VERSION));
    entity.setTargetScriptPinEpoch(
        record.get(REMOTE_COMMAND_COORDINATOR.TARGET_SCRIPT_PIN_EPOCH));
    entity.setTargetScriptPinControlPlaneRequestId(
        record.get(REMOTE_COMMAND_COORDINATOR.TARGET_SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID));
    entity.setPluginId(record.get(REMOTE_COMMAND_COORDINATOR.PLUGIN_ID));
    entity.setPluginVersionId(record.get(REMOTE_COMMAND_COORDINATOR.PLUGIN_VERSION_ID));
    entity.setAutomationDispatchId(record.get(REMOTE_COMMAND_COORDINATOR.AUTOMATION_DISPATCH_ID));
    entity.setAutomationWorkItemId(record.get(REMOTE_COMMAND_COORDINATOR.AUTOMATION_WORK_ITEM_ID));
    entity.setScriptId(record.get(REMOTE_COMMAND_COORDINATOR.SCRIPT_ID));
    entity.setUpdatedAt(toInstant(record.get(REMOTE_COMMAND_COORDINATOR.UPDATED_AT)));
    return entity;
  }
}
