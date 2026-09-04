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
import static org.jooq.impl.DSL.selectOne;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.RemoteCommandCoordinator;
import net.firedevops.firemud.gamesession.entity.RemoteFollowupResult;
import net.firedevops.firemud.gamesession.jooq.tables.records.RemoteFollowupResultRecord;
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
public class RemoteFollowupResultRepository {
  private static final int DEFAULT_CONTROL_PLANE_LIMIT = 200;

  private final DSLContext dsl;

  public RemoteFollowupResultRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<RemoteFollowupResult> findByTenantIdAndResultId(Long tenantId, String resultId) {
    return dsl.selectFrom(REMOTE_FOLLOWUP_RESULT)
        .where(
            REMOTE_FOLLOWUP_RESULT
                .TENANT_ID
                .eq(tenantId)
                .and(REMOTE_FOLLOWUP_RESULT.RESULT_ID.eq(resultId)))
        .fetchOptional(this::toEntity);
  }

  public Optional<RemoteFollowupResult> findFirstByTenantIdAndCoordinatorIdOrderByObservedAtDesc(
      Long tenantId, String coordinatorId) {
    return dsl.selectFrom(REMOTE_FOLLOWUP_RESULT)
        .where(
            REMOTE_FOLLOWUP_RESULT
                .TENANT_ID
                .eq(tenantId)
                .and(REMOTE_FOLLOWUP_RESULT.COORDINATOR_ID.eq(coordinatorId)))
        .orderBy(REMOTE_FOLLOWUP_RESULT.OBSERVED_AT.desc(), REMOTE_FOLLOWUP_RESULT.ID.desc())
        .limit(1)
        .fetchOptional(this::toEntity);
  }

  public List<RemoteFollowupResult> findByTenantIdAndCoordinatorIdOrderByObservedAtAsc(
      Long tenantId, String coordinatorId) {
    return dsl.selectFrom(REMOTE_FOLLOWUP_RESULT)
        .where(
            REMOTE_FOLLOWUP_RESULT
                .TENANT_ID
                .eq(tenantId)
                .and(REMOTE_FOLLOWUP_RESULT.COORDINATOR_ID.eq(coordinatorId)))
        .orderBy(REMOTE_FOLLOWUP_RESULT.OBSERVED_AT.asc(), REMOTE_FOLLOWUP_RESULT.ID.asc())
        .fetch(this::toEntity);
  }

  public List<RemoteFollowupResult> findByTenantIdAndCoordinatorIdInOrderByObservedAtAsc(
      Long tenantId, Collection<String> coordinatorIds) {
    if (coordinatorIds == null || coordinatorIds.isEmpty()) {
      return List.of();
    }
    return dsl.selectFrom(REMOTE_FOLLOWUP_RESULT)
        .where(
            REMOTE_FOLLOWUP_RESULT
                .TENANT_ID
                .eq(tenantId)
                .and(REMOTE_FOLLOWUP_RESULT.COORDINATOR_ID.in(coordinatorIds)))
        .orderBy(REMOTE_FOLLOWUP_RESULT.OBSERVED_AT.asc(), REMOTE_FOLLOWUP_RESULT.ID.asc())
        .fetch(this::toEntity);
  }

  public Optional<RemoteFollowupResult> findLatestForCoordinator(
      RemoteCommandCoordinator coordinator) {
    if (coordinator == null) {
      return Optional.empty();
    }
    return dsl.selectFrom(REMOTE_FOLLOWUP_RESULT)
        .where(exactCoordinatorScope(REMOTE_FOLLOWUP_RESULT, coordinator))
        .orderBy(REMOTE_FOLLOWUP_RESULT.OBSERVED_AT.desc(), REMOTE_FOLLOWUP_RESULT.ID.desc())
        .limit(1)
        .fetchOptional(this::toEntity);
  }

  public List<RemoteFollowupResult> findForCoordinatorScopes(
      Collection<RemoteCommandCoordinator> coordinators) {
    if (coordinators == null || coordinators.isEmpty()) {
      return List.of();
    }
    Condition combinedScope = null;
    for (RemoteCommandCoordinator coordinator : coordinators) {
      if (coordinator == null) {
        continue;
      }
      Condition scope = exactCoordinatorScope(REMOTE_FOLLOWUP_RESULT, coordinator);
      combinedScope = combinedScope == null ? scope : combinedScope.or(scope);
    }
    if (combinedScope == null) {
      return List.of();
    }
    return dsl.selectFrom(REMOTE_FOLLOWUP_RESULT)
        .where(combinedScope)
        .orderBy(REMOTE_FOLLOWUP_RESULT.OBSERVED_AT.asc(), REMOTE_FOLLOWUP_RESULT.ID.asc())
        .fetch(this::toEntity);
  }

  public List<RemoteFollowupResult> findForControlPlane(
      Long tenantId,
      String coordinatorId,
      String followupId,
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
      String outcome,
      String scriptId,
      String pluginId,
      String scriptPatchVersion,
      String pluginVersionId,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      Long pointerVersion,
      String resultErrorCode,
      String automationWorkItemId,
      String resultCommandId,
      String resultCommandExecutionOutcome,
      String resultCommandGameplayResult,
      String targetEntityId,
      String claimTargetAggregate,
      String effectKey,
      String failureCode,
      String payloadKind,
      String originSourceKind,
      String originSourceState,
      String eventType,
      String scriptEventId,
      String resultMessage,
      Boolean requiresSoloTick,
      String queueSourceKind,
      String queueSourceState,
      long queueSourceOrdinal,
      long queueSourceDueTickId,
      long queueSourceDueAtMs,
      String lateResultPolicy,
      String claimedTickBatchId,
      String automationDispatchId,
      String commandId,
      Pageable pageable) {
    var result = REMOTE_FOLLOWUP_RESULT.as("result");
    var currentOrigin = RUNTIME_REGION_STATUS.as("currentOrigin");
    var currentTarget = RUNTIME_REGION_STATUS.as("currentTarget");
    var linkedFollowup = REMOTE_FOLLOWUP.as("linkedFollowup");
    var resultCommand = GAMEPLAY_COMMAND.as("resultCommand");
    var coordinator = REMOTE_COMMAND_COORDINATOR.as("coordinator");

    List<Condition> conditions = new ArrayList<>();
    conditions.add(result.TENANT_ID.eq(tenantId));
    addIfNotBlank(conditions, coordinatorId, () -> result.COORDINATOR_ID.eq(coordinatorId));
    addIfNotBlank(conditions, followupId, () -> result.FOLLOWUP_ID.eq(followupId));
    addIfNonNull(
        conditions,
        originGameInstanceId,
        () -> result.ORIGIN_GAME_INSTANCE_ID.eq(originGameInstanceId));
    addIfNotBlank(conditions, originRegionId, () -> result.ORIGIN_REGION_ID.eq(originRegionId));
    addIfPositive(
        conditions, originRegionEpoch, () -> result.ORIGIN_REGION_EPOCH.eq(originRegionEpoch));
    addIfNonNull(
        conditions,
        targetGameInstanceId,
        () -> result.TARGET_GAME_INSTANCE_ID.eq(targetGameInstanceId));
    addIfNotBlank(conditions, targetRegionId, () -> result.TARGET_REGION_ID.eq(targetRegionId));
    addIfPositive(
        conditions, targetRegionEpoch, () -> result.TARGET_REGION_EPOCH.eq(targetRegionEpoch));
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
    addIfNotBlank(conditions, outcome, () -> result.OUTCOME.eq(outcome));
    addIfNotBlank(conditions, scriptId, () -> result.SCRIPT_ID.eq(scriptId));
    addIfNotBlank(conditions, pluginId, () -> result.PLUGIN_ID.eq(pluginId));
    addIfNotBlank(
        conditions, scriptPatchVersion, () -> result.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion));
    addIfNotBlank(conditions, pluginVersionId, () -> result.PLUGIN_VERSION_ID.eq(pluginVersionId));
    addIfNotBlank(
        conditions, playableStateScope, () -> result.PLAYABLE_STATE_SCOPE.eq(playableStateScope));
    addIfNotBlank(conditions, worldSlug, () -> result.WORLD_SLUG.eq(worldSlug));
    addIfNotBlank(conditions, realmSlug, () -> result.REALM_SLUG.eq(realmSlug));
    addIfNonNull(conditions, pointerVersion, () -> result.POINTER_VERSION.eq(pointerVersion));
    addIfNotBlank(conditions, resultErrorCode, () -> result.RESULT_ERROR_CODE.eq(resultErrorCode));
    addIfNotBlank(
        conditions,
        automationWorkItemId,
        () -> result.AUTOMATION_WORK_ITEM_ID.eq(automationWorkItemId));
    addIfNotBlank(conditions, resultCommandId, () -> result.RESULT_COMMAND_ID.eq(resultCommandId));
    addIfNotBlank(
        conditions,
        resultCommandExecutionOutcome,
        () -> resultCommand.EXECUTION_OUTCOME.eq(resultCommandExecutionOutcome));
    addIfNotBlank(
        conditions,
        resultCommandGameplayResult,
        () -> resultCommand.GAMEPLAY_RESULT.eq(resultCommandGameplayResult));
    addIfNotBlank(
        conditions, targetEntityId, () -> linkedFollowup.TARGET_ENTITY_ID.eq(targetEntityId));
    addIfNotBlank(
        conditions,
        claimTargetAggregate,
        () -> linkedFollowup.CLAIM_TARGET_AGGREGATE.eq(claimTargetAggregate));
    addIfNotBlank(conditions, effectKey, () -> linkedFollowup.EFFECT_KEY.eq(effectKey));
    addIfNotBlank(conditions, failureCode, () -> linkedFollowup.FAILURE_CODE.eq(failureCode));
    addIfNotBlank(conditions, payloadKind, () -> linkedFollowup.PAYLOAD_KIND.eq(payloadKind));
    addIfNotBlank(
        conditions, originSourceKind, () -> linkedFollowup.ORIGIN_SOURCE_KIND.eq(originSourceKind));
    addIfNotBlank(
        conditions,
        originSourceState,
        () -> linkedFollowup.ORIGIN_SOURCE_STATE.eq(originSourceState));
    addIfNotBlank(conditions, eventType, () -> linkedFollowup.EVENT_TYPE.eq(eventType));
    addIfNotBlank(
        conditions, scriptEventId, () -> linkedFollowup.SCRIPT_EVENT_ID.eq(scriptEventId));
    addIfNotBlank(conditions, resultMessage, () -> result.RESULT_MESSAGE.eq(resultMessage));
    addIfNonNull(
        conditions, requiresSoloTick, () -> linkedFollowup.REQUIRES_SOLO_TICK.eq(requiresSoloTick));
    addIfNotBlank(
        conditions, queueSourceKind, () -> linkedFollowup.QUEUE_SOURCE_KIND.eq(queueSourceKind));
    addIfNotBlank(
        conditions, queueSourceState, () -> linkedFollowup.QUEUE_SOURCE_STATE.eq(queueSourceState));
    addIfPositive(
        conditions,
        queueSourceOrdinal,
        () -> linkedFollowup.QUEUE_SOURCE_ORDINAL.eq(queueSourceOrdinal));
    addIfPositive(
        conditions,
        queueSourceDueTickId,
        () -> linkedFollowup.QUEUE_SOURCE_DUE_TICK_ID.eq(queueSourceDueTickId));
    addIfPositive(
        conditions,
        queueSourceDueAtMs,
        () -> linkedFollowup.QUEUE_SOURCE_DUE_AT_MS.eq(queueSourceDueAtMs));
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
                            .eq(result.TENANT_ID)
                            .and(coordinator.COORDINATOR_ID.eq(result.COORDINATOR_ID))
                            .and(coordinator.FOLLOWUP_ID.eq(result.FOLLOWUP_ID))
                            .and(
                                coordinator.ORIGIN_GAME_INSTANCE_ID.eq(
                                    result.ORIGIN_GAME_INSTANCE_ID))
                            .and(coordinator.ORIGIN_REGION_ID.eq(result.ORIGIN_REGION_ID))
                            .and(coordinator.ORIGIN_REGION_EPOCH.eq(result.ORIGIN_REGION_EPOCH))
                            .and(
                                coordinator.TARGET_GAME_INSTANCE_ID.eq(
                                    result.TARGET_GAME_INSTANCE_ID))
                            .and(coordinator.TARGET_REGION_ID.eq(result.TARGET_REGION_ID))
                            .and(coordinator.TARGET_REGION_EPOCH.eq(result.TARGET_REGION_EPOCH))
                            .and(coordinator.LATE_RESULT_POLICY.eq(lateResultPolicy)))));
    addIfNotBlank(
        conditions,
        claimedTickBatchId,
        () -> linkedFollowup.CLAIMED_TICK_BATCH_ID.eq(claimedTickBatchId));
    addIfNotBlank(
        conditions,
        automationDispatchId,
        () -> result.AUTOMATION_DISPATCH_ID.eq(automationDispatchId));
    addIfNotBlank(conditions, commandId, () -> result.COMMAND_ID.eq(commandId));

    return baseControlPlaneQuery(
            result, currentOrigin, currentTarget, linkedFollowup, resultCommand)
        .where(conditions)
        .orderBy(result.OBSERVED_AT.asc(), result.ID.asc())
        .limit(limitOrDefault(pageable, DEFAULT_CONTROL_PLANE_LIMIT))
        .offset(offsetOrZero(pageable))
        .fetch(this::toEntity);
  }

  public RemoteFollowupResult save(RemoteFollowupResult entity) {
    if (entity.getId() == null) {
      RemoteFollowupResultRecord record = dsl.newRecord(REMOTE_FOLLOWUP_RESULT);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(REMOTE_FOLLOWUP_RESULT)
            .set(REMOTE_FOLLOWUP_RESULT.RESULT_ID, entity.getResultId())
            .set(REMOTE_FOLLOWUP_RESULT.TENANT_ID, entity.getTenantId())
            .set(REMOTE_FOLLOWUP_RESULT.COORDINATOR_ID, entity.getCoordinatorId())
            .set(REMOTE_FOLLOWUP_RESULT.FOLLOWUP_ID, entity.getFollowupId())
            .set(REMOTE_FOLLOWUP_RESULT.ORIGIN_GAME_INSTANCE_ID, entity.getOriginGameInstanceId())
            .set(REMOTE_FOLLOWUP_RESULT.ORIGIN_REGION_ID, entity.getOriginRegionId())
            .set(REMOTE_FOLLOWUP_RESULT.ORIGIN_REGION_EPOCH, entity.getOriginRegionEpoch())
            .set(REMOTE_FOLLOWUP_RESULT.TARGET_GAME_INSTANCE_ID, entity.getTargetGameInstanceId())
            .set(REMOTE_FOLLOWUP_RESULT.TARGET_REGION_ID, entity.getTargetRegionId())
            .set(REMOTE_FOLLOWUP_RESULT.TARGET_REGION_EPOCH, entity.getTargetRegionEpoch())
            .set(REMOTE_FOLLOWUP_RESULT.OUTCOME, entity.getOutcome())
            .set(REMOTE_FOLLOWUP_RESULT.RESULT_PAYLOAD_JSON, entity.getResultPayloadJson())
            .set(REMOTE_FOLLOWUP_RESULT.RESULT_COMMAND_ID, entity.getResultCommandId())
            .set(REMOTE_FOLLOWUP_RESULT.RESULT_ERROR_CODE, entity.getResultErrorCode())
            .set(REMOTE_FOLLOWUP_RESULT.RESULT_MESSAGE, entity.getResultMessage())
            .set(REMOTE_FOLLOWUP_RESULT.PLAYABLE_STATE_SCOPE, entity.getPlayableStateScope())
            .set(REMOTE_FOLLOWUP_RESULT.WORLD_SLUG, entity.getWorldSlug())
            .set(REMOTE_FOLLOWUP_RESULT.REALM_SLUG, entity.getRealmSlug())
            .set(REMOTE_FOLLOWUP_RESULT.POINTER_VERSION, entity.getPointerVersion())
            .set(REMOTE_FOLLOWUP_RESULT.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
            .set(REMOTE_FOLLOWUP_RESULT.PLUGIN_ID, entity.getPluginId())
            .set(REMOTE_FOLLOWUP_RESULT.PLUGIN_VERSION_ID, entity.getPluginVersionId())
            .set(REMOTE_FOLLOWUP_RESULT.COMMAND_ID, entity.getCommandId())
            .set(REMOTE_FOLLOWUP_RESULT.AUTOMATION_DISPATCH_ID, entity.getAutomationDispatchId())
            .set(REMOTE_FOLLOWUP_RESULT.AUTOMATION_WORK_ITEM_ID, entity.getAutomationWorkItemId())
            .set(REMOTE_FOLLOWUP_RESULT.SCRIPT_ID, entity.getScriptId())
            .set(REMOTE_FOLLOWUP_RESULT.OBSERVED_AT, toOffsetDateTime(entity.getObservedAt()))
            .where(REMOTE_FOLLOWUP_RESULT.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException(
          "Failed to update remote_followup_result id=" + entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private SelectJoinStep<Record> baseControlPlaneQuery(
      net.firedevops.firemud.gamesession.jooq.tables.RemoteFollowupResult result,
      net.firedevops.firemud.gamesession.jooq.tables.RuntimeRegionStatus currentOrigin,
      net.firedevops.firemud.gamesession.jooq.tables.RuntimeRegionStatus currentTarget,
      net.firedevops.firemud.gamesession.jooq.tables.RemoteFollowup linkedFollowup,
      net.firedevops.firemud.gamesession.jooq.tables.GameplayCommand resultCommand) {
    return dsl.select(result.fields())
        .from(result)
        .leftJoin(currentOrigin)
        .on(
            currentOrigin
                .TENANT_ID
                .eq(result.TENANT_ID)
                .and(currentOrigin.GAME_INSTANCE_ID.eq(result.ORIGIN_GAME_INSTANCE_ID)))
        .leftJoin(currentTarget)
        .on(
            currentTarget
                .TENANT_ID
                .eq(result.TENANT_ID)
                .and(currentTarget.GAME_INSTANCE_ID.eq(result.TARGET_GAME_INSTANCE_ID)))
        .leftJoin(linkedFollowup)
        .on(
            linkedFollowup
                .TENANT_ID
                .eq(result.TENANT_ID)
                .and(linkedFollowup.ORIGIN_GAME_INSTANCE_ID.eq(result.ORIGIN_GAME_INSTANCE_ID))
                .and(linkedFollowup.ORIGIN_REGION_ID.eq(result.ORIGIN_REGION_ID))
                .and(linkedFollowup.ORIGIN_REGION_EPOCH.eq(result.ORIGIN_REGION_EPOCH))
                .and(linkedFollowup.TARGET_GAME_INSTANCE_ID.eq(result.TARGET_GAME_INSTANCE_ID))
                .and(linkedFollowup.TARGET_REGION_ID.eq(result.TARGET_REGION_ID))
                .and(linkedFollowup.TARGET_REGION_EPOCH.eq(result.TARGET_REGION_EPOCH))
                .and(linkedFollowup.FOLLOWUP_ID.eq(result.FOLLOWUP_ID)))
        .leftJoin(resultCommand)
        .on(
            resultCommand
                .TENANT_ID
                .eq(result.TENANT_ID)
                .and(resultCommand.GAME_INSTANCE_ID.eq(result.TARGET_GAME_INSTANCE_ID))
                .and(resultCommand.REGION_ID.isNotDistinctFrom(result.TARGET_REGION_ID))
                .and(resultCommand.REGION_EPOCH.isNotDistinctFrom(result.TARGET_REGION_EPOCH))
                .and(resultCommand.REMOTE_FOLLOWUP_ID.eq(result.FOLLOWUP_ID))
                .and(resultCommand.COMMAND_ID.eq(result.RESULT_COMMAND_ID)));
  }

  private Optional<RemoteFollowupResult> findById(Long id) {
    return dsl.selectFrom(REMOTE_FOLLOWUP_RESULT)
        .where(REMOTE_FOLLOWUP_RESULT.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private static Condition exactCoordinatorScope(
      net.firedevops.firemud.gamesession.jooq.tables.RemoteFollowupResult result,
      RemoteCommandCoordinator coordinator) {
    return result
        .TENANT_ID
        .eq(coordinator.getTenantId())
        .and(result.COORDINATOR_ID.eq(coordinator.getCoordinatorId()))
        .and(result.FOLLOWUP_ID.eq(coordinator.getFollowupId()))
        .and(result.ORIGIN_GAME_INSTANCE_ID.eq(coordinator.getOriginGameInstanceId()))
        .and(result.ORIGIN_REGION_ID.eq(coordinator.getOriginRegionId()))
        .and(result.ORIGIN_REGION_EPOCH.eq(coordinator.getOriginRegionEpoch()))
        .and(result.TARGET_GAME_INSTANCE_ID.eq(coordinator.getTargetGameInstanceId()))
        .and(result.TARGET_REGION_ID.eq(coordinator.getTargetRegionId()))
        .and(result.TARGET_REGION_EPOCH.eq(coordinator.getTargetRegionEpoch()));
  }

  private void populate(RemoteFollowupResultRecord record, RemoteFollowupResult entity) {
    record.setResultId(entity.getResultId());
    record.setTenantId(entity.getTenantId());
    record.setCoordinatorId(entity.getCoordinatorId());
    record.setFollowupId(entity.getFollowupId());
    record.setOriginGameInstanceId(entity.getOriginGameInstanceId());
    record.setOriginRegionId(entity.getOriginRegionId());
    record.setOriginRegionEpoch(entity.getOriginRegionEpoch());
    record.setTargetGameInstanceId(entity.getTargetGameInstanceId());
    record.setTargetRegionId(entity.getTargetRegionId());
    record.setTargetRegionEpoch(entity.getTargetRegionEpoch());
    record.setOutcome(entity.getOutcome());
    record.setResultPayloadJson(entity.getResultPayloadJson());
    record.setResultCommandId(entity.getResultCommandId());
    record.setResultErrorCode(entity.getResultErrorCode());
    record.setResultMessage(entity.getResultMessage());
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
    record.setObservedAt(toOffsetDateTime(entity.getObservedAt()));
  }

  private RemoteFollowupResult toEntity(Record record) {
    RemoteFollowupResult entity = new RemoteFollowupResult();
    entity.setId(record.get(REMOTE_FOLLOWUP_RESULT.ID));
    entity.setResultId(record.get(REMOTE_FOLLOWUP_RESULT.RESULT_ID));
    entity.setTenantId(record.get(REMOTE_FOLLOWUP_RESULT.TENANT_ID));
    entity.setCoordinatorId(record.get(REMOTE_FOLLOWUP_RESULT.COORDINATOR_ID));
    entity.setFollowupId(record.get(REMOTE_FOLLOWUP_RESULT.FOLLOWUP_ID));
    entity.setOriginGameInstanceId(record.get(REMOTE_FOLLOWUP_RESULT.ORIGIN_GAME_INSTANCE_ID));
    entity.setOriginRegionId(record.get(REMOTE_FOLLOWUP_RESULT.ORIGIN_REGION_ID));
    entity.setOriginRegionEpoch(record.get(REMOTE_FOLLOWUP_RESULT.ORIGIN_REGION_EPOCH));
    entity.setTargetGameInstanceId(record.get(REMOTE_FOLLOWUP_RESULT.TARGET_GAME_INSTANCE_ID));
    entity.setTargetRegionId(record.get(REMOTE_FOLLOWUP_RESULT.TARGET_REGION_ID));
    entity.setTargetRegionEpoch(record.get(REMOTE_FOLLOWUP_RESULT.TARGET_REGION_EPOCH));
    entity.setOutcome(record.get(REMOTE_FOLLOWUP_RESULT.OUTCOME));
    entity.setResultPayloadJson(record.get(REMOTE_FOLLOWUP_RESULT.RESULT_PAYLOAD_JSON));
    entity.setResultCommandId(record.get(REMOTE_FOLLOWUP_RESULT.RESULT_COMMAND_ID));
    entity.setResultErrorCode(record.get(REMOTE_FOLLOWUP_RESULT.RESULT_ERROR_CODE));
    entity.setResultMessage(record.get(REMOTE_FOLLOWUP_RESULT.RESULT_MESSAGE));
    entity.setPlayableStateScope(record.get(REMOTE_FOLLOWUP_RESULT.PLAYABLE_STATE_SCOPE));
    entity.setWorldSlug(record.get(REMOTE_FOLLOWUP_RESULT.WORLD_SLUG));
    entity.setRealmSlug(record.get(REMOTE_FOLLOWUP_RESULT.REALM_SLUG));
    entity.setPointerVersion(record.get(REMOTE_FOLLOWUP_RESULT.POINTER_VERSION));
    entity.setScriptPatchVersion(record.get(REMOTE_FOLLOWUP_RESULT.SCRIPT_PATCH_VERSION));
    entity.setPluginId(record.get(REMOTE_FOLLOWUP_RESULT.PLUGIN_ID));
    entity.setPluginVersionId(record.get(REMOTE_FOLLOWUP_RESULT.PLUGIN_VERSION_ID));
    entity.setCommandId(record.get(REMOTE_FOLLOWUP_RESULT.COMMAND_ID));
    entity.setAutomationDispatchId(record.get(REMOTE_FOLLOWUP_RESULT.AUTOMATION_DISPATCH_ID));
    entity.setAutomationWorkItemId(record.get(REMOTE_FOLLOWUP_RESULT.AUTOMATION_WORK_ITEM_ID));
    entity.setScriptId(record.get(REMOTE_FOLLOWUP_RESULT.SCRIPT_ID));
    entity.setObservedAt(toInstant(record.get(REMOTE_FOLLOWUP_RESULT.OBSERVED_AT)));
    return entity;
  }
}
