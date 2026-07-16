package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.GameplayCommand.GAMEPLAY_COMMAND;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.jooq.tables.records.GameplayCommandRecord;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GameplayCommandRepository {
  private final DSLContext dsl;

  public GameplayCommandRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<GameplayCommand> findByCommandId(String commandId) {
    return dsl.selectFrom(GAMEPLAY_COMMAND)
        .where(GAMEPLAY_COMMAND.COMMAND_ID.eq(commandId))
        .fetchOptional(this::toEntity);
  }

  public Optional<GameplayCommand> findByTenantIdAndGameInstanceIdAndCommandId(
      Long tenantId, Long gameInstanceId, String commandId) {
    return dsl.selectFrom(GAMEPLAY_COMMAND)
        .where(
            GAMEPLAY_COMMAND
                .TENANT_ID
                .eq(tenantId)
                .and(GAMEPLAY_COMMAND.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(GAMEPLAY_COMMAND.COMMAND_ID.eq(commandId)))
        .fetchOptional(this::toEntity);
  }

  public Optional<GameplayCommand>
      findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
          Long tenantId,
          Long gameInstanceId,
          String regionId,
          Long regionEpoch,
          String automationDispatchId) {
    return dsl.selectFrom(GAMEPLAY_COMMAND)
        .where(
            GAMEPLAY_COMMAND
                .TENANT_ID
                .eq(tenantId)
                .and(GAMEPLAY_COMMAND.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(GAMEPLAY_COMMAND.REGION_ID.eq(regionId))
                .and(GAMEPLAY_COMMAND.REGION_EPOCH.eq(regionEpoch))
                .and(GAMEPLAY_COMMAND.AUTOMATION_DISPATCH_ID.eq(automationDispatchId)))
        .fetchOptional(this::toEntity);
  }

  public Optional<GameplayCommand>
      findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndRemoteFollowupId(
          Long tenantId,
          Long gameInstanceId,
          String regionId,
          Long regionEpoch,
          String remoteFollowupId) {
    return dsl.selectFrom(GAMEPLAY_COMMAND)
        .where(
            GAMEPLAY_COMMAND
                .TENANT_ID
                .eq(tenantId)
                .and(GAMEPLAY_COMMAND.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(GAMEPLAY_COMMAND.REGION_ID.eq(regionId))
                .and(GAMEPLAY_COMMAND.REGION_EPOCH.eq(regionEpoch))
                .and(GAMEPLAY_COMMAND.REMOTE_FOLLOWUP_ID.eq(remoteFollowupId)))
        .fetchOptional(this::toEntity);
  }

  public Optional<GameplayCommand> findFirstByTenantIdAndRemoteFollowupId(
      Long tenantId, String remoteFollowupId) {
    return dsl.selectFrom(GAMEPLAY_COMMAND)
        .where(
            GAMEPLAY_COMMAND
                .TENANT_ID
                .eq(tenantId)
                .and(GAMEPLAY_COMMAND.REMOTE_FOLLOWUP_ID.eq(remoteFollowupId)))
        .limit(1)
        .fetchOptional(this::toEntity);
  }

  public List<GameplayCommand> findByTenantIdAndRemoteFollowupIdIn(
      Long tenantId, Collection<String> remoteFollowupIds) {
    if (remoteFollowupIds == null || remoteFollowupIds.isEmpty()) {
      return List.of();
    }
    return dsl.selectFrom(GAMEPLAY_COMMAND)
        .where(
            GAMEPLAY_COMMAND
                .TENANT_ID
                .eq(tenantId)
                .and(GAMEPLAY_COMMAND.REMOTE_FOLLOWUP_ID.in(remoteFollowupIds)))
        .orderBy(GAMEPLAY_COMMAND.ID.asc())
        .fetch(this::toEntity);
  }

  public List<GameplayCommand> findByCommandIdIn(Collection<String> commandIds) {
    if (commandIds == null || commandIds.isEmpty()) {
      return List.of();
    }
    return dsl.selectFrom(GAMEPLAY_COMMAND)
        .where(GAMEPLAY_COMMAND.COMMAND_ID.in(commandIds))
        .orderBy(GAMEPLAY_COMMAND.ID.asc())
        .fetch(this::toEntity);
  }

  public long countByTenantIdAndGameInstanceIdAndCompletedAtIsNullAndExecutionOutcomeIn(
      Long tenantId, Long gameInstanceId, Collection<String> executionOutcomes) {
    if (executionOutcomes == null || executionOutcomes.isEmpty()) {
      return 0L;
    }
    return dsl.fetchCount(
        GAMEPLAY_COMMAND,
        GAMEPLAY_COMMAND
            .TENANT_ID
            .eq(tenantId)
            .and(GAMEPLAY_COMMAND.GAME_INSTANCE_ID.eq(gameInstanceId))
            .and(GAMEPLAY_COMMAND.COMPLETED_AT.isNull())
            .and(GAMEPLAY_COMMAND.EXECUTION_OUTCOME.in(executionOutcomes)));
  }

  public List<GameplayCommand> findByExecutionOutcomeAndStagedAtIsNullAndAcceptedAtBefore(
      String executionOutcome, Instant acceptedBefore) {
    return dsl.selectFrom(GAMEPLAY_COMMAND)
        .where(
            GAMEPLAY_COMMAND
                .EXECUTION_OUTCOME
                .eq(executionOutcome)
                .and(GAMEPLAY_COMMAND.STAGED_AT.isNull())
                .and(GAMEPLAY_COMMAND.ACCEPTED_AT.lt(toLocalDateTime(acceptedBefore))))
        .orderBy(GAMEPLAY_COMMAND.ACCEPTED_AT.asc(), GAMEPLAY_COMMAND.ID.asc())
        .fetch(this::toEntity);
  }

  public List<GameplayCommand> findQueuedAutomationCommandsForScriptPatch(
      Long tenantId, Long gameInstanceId, String regionId, String scriptPatchVersion) {
    Condition condition =
        GAMEPLAY_COMMAND
            .TENANT_ID
            .eq(tenantId)
            .and(GAMEPLAY_COMMAND.GAME_INSTANCE_ID.eq(gameInstanceId))
            .and(GAMEPLAY_COMMAND.SOURCE_TYPE.eq("AUTOMATION"))
            .and(GAMEPLAY_COMMAND.COMPLETED_AT.isNull())
            .and(GAMEPLAY_COMMAND.EXECUTION_OUTCOME.in("ACCEPTED", "STAGED", "RETRY_QUEUED"))
            .and(GAMEPLAY_COMMAND.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion));
    if (regionId != null && !regionId.isBlank()) {
      condition = condition.and(GAMEPLAY_COMMAND.REGION_ID.eq(regionId));
    }
    return dsl.selectFrom(GAMEPLAY_COMMAND)
        .where(condition)
        .orderBy(GAMEPLAY_COMMAND.ACCEPTED_AT.asc(), GAMEPLAY_COMMAND.ID.asc())
        .fetch(this::toEntity);
  }

  public List<GameplayCommand> findQueuedAutomationCommandsForPluginVersion(
      Long tenantId,
      Long gameInstanceId,
      String regionId,
      String pluginId,
      String pluginVersionId) {
    Condition condition =
        GAMEPLAY_COMMAND
            .TENANT_ID
            .eq(tenantId)
            .and(GAMEPLAY_COMMAND.GAME_INSTANCE_ID.eq(gameInstanceId))
            .and(GAMEPLAY_COMMAND.SOURCE_TYPE.eq("AUTOMATION"))
            .and(GAMEPLAY_COMMAND.COMPLETED_AT.isNull())
            .and(GAMEPLAY_COMMAND.EXECUTION_OUTCOME.in("ACCEPTED", "STAGED", "RETRY_QUEUED"))
            .and(GAMEPLAY_COMMAND.PLUGIN_ID.eq(pluginId))
            .and(GAMEPLAY_COMMAND.PLUGIN_VERSION_ID.eq(pluginVersionId));
    if (regionId != null && !regionId.isBlank()) {
      condition = condition.and(GAMEPLAY_COMMAND.REGION_ID.eq(regionId));
    }
    return dsl.selectFrom(GAMEPLAY_COMMAND)
        .where(condition)
        .orderBy(GAMEPLAY_COMMAND.ACCEPTED_AT.asc(), GAMEPLAY_COMMAND.ID.asc())
        .fetch(this::toEntity);
  }

  public List<GameplayCommand> saveAll(Iterable<GameplayCommand> entities) {
    List<GameplayCommand> saved = new ArrayList<>();
    for (GameplayCommand entity : entities) {
      saved.add(save(entity));
    }
    return List.copyOf(saved);
  }

  public GameplayCommand save(GameplayCommand entity) {
    if (entity.getId() == null) {
      GameplayCommandRecord record = dsl.newRecord(GAMEPLAY_COMMAND);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int updated =
        dsl.update(GAMEPLAY_COMMAND)
            .set(GAMEPLAY_COMMAND.COMMAND_ID, entity.getCommandId())
            .set(GAMEPLAY_COMMAND.TENANT_ID, entity.getTenantId())
            .set(GAMEPLAY_COMMAND.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(GAMEPLAY_COMMAND.SESSION_ID, entity.getSessionId())
            .set(GAMEPLAY_COMMAND.ACCOUNT_ID, entity.getAccountId())
            .set(GAMEPLAY_COMMAND.CHARACTER_ID, entity.getCharacterId())
            .set(GAMEPLAY_COMMAND.COMMAND_NAME, entity.getCommandName())
            .set(GAMEPLAY_COMMAND.COMMAND_TEXT, entity.getCommandText())
            .set(GAMEPLAY_COMMAND.SANITIZED_COMMAND_TEXT, entity.getSanitizedCommandText())
            .set(GAMEPLAY_COMMAND.REQUIRES_SOLO_TICK, entity.isRequiresSoloTick())
            .set(GAMEPLAY_COMMAND.EXECUTION_OUTCOME, entity.getExecutionOutcome())
            .set(GAMEPLAY_COMMAND.GAMEPLAY_RESULT, entity.getGameplayResult())
            .set(GAMEPLAY_COMMAND.ACCEPTED_AT, toLocalDateTime(entity.getAcceptedAt()))
            .set(GAMEPLAY_COMMAND.STAGED_AT, toLocalDateTime(entity.getStagedAt()))
            .set(GAMEPLAY_COMMAND.COMPLETED_AT, toLocalDateTime(entity.getCompletedAt()))
            .set(GAMEPLAY_COMMAND.LAST_ATTEMPT_AT, toLocalDateTime(entity.getLastAttemptAt()))
            .set(GAMEPLAY_COMMAND.ATTEMPT_COUNT, entity.getAttemptCount())
            .set(GAMEPLAY_COMMAND.FAILURE_CODE, entity.getFailureCode())
            .set(GAMEPLAY_COMMAND.FAILURE_MESSAGE, entity.getFailureMessage())
            .set(GAMEPLAY_COMMAND.SOURCE_TYPE, entity.getSourceType())
            .set(GAMEPLAY_COMMAND.AUTOMATION_DISPATCH_ID, entity.getAutomationDispatchId())
            .set(GAMEPLAY_COMMAND.AUTOMATION_WORK_ITEM_ID, entity.getAutomationWorkItemId())
            .set(GAMEPLAY_COMMAND.SCRIPT_ID, entity.getScriptId())
            .set(GAMEPLAY_COMMAND.EXECUTION_HOOK, entity.getExecutionHook())
            .set(GAMEPLAY_COMMAND.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
            .set(GAMEPLAY_COMMAND.PLUGIN_ID, entity.getPluginId())
            .set(GAMEPLAY_COMMAND.PLUGIN_VERSION_ID, entity.getPluginVersionId())
            .set(GAMEPLAY_COMMAND.PLAYABLE_STATE_SCOPE, entity.getPlayableStateScope())
            .set(GAMEPLAY_COMMAND.WORLD_SLUG, entity.getWorldSlug())
            .set(GAMEPLAY_COMMAND.REALM_SLUG, entity.getRealmSlug())
            .set(GAMEPLAY_COMMAND.POINTER_VERSION, entity.getPointerVersion())
            .set(GAMEPLAY_COMMAND.ORIGIN_SOURCE_KIND, entity.getOriginSourceKind())
            .set(GAMEPLAY_COMMAND.ORIGIN_SOURCE_STATE, entity.getOriginSourceState())
            .set(GAMEPLAY_COMMAND.ORIGIN_SOURCE_ORDINAL, entity.getOriginSourceOrdinal())
            .set(GAMEPLAY_COMMAND.ORIGIN_SOURCE_DUE_TICK_ID, entity.getOriginSourceDueTickId())
            .set(GAMEPLAY_COMMAND.ORIGIN_SOURCE_DUE_AT_MS, entity.getOriginSourceDueAtMs())
            .set(GAMEPLAY_COMMAND.QUEUE_SOURCE_KIND, entity.getQueueSourceKind())
            .set(GAMEPLAY_COMMAND.QUEUE_SOURCE_STATE, entity.getQueueSourceState())
            .set(GAMEPLAY_COMMAND.QUEUE_SOURCE_ORDINAL, entity.getQueueSourceOrdinal())
            .set(GAMEPLAY_COMMAND.QUEUE_SOURCE_DUE_TICK_ID, entity.getQueueSourceDueTickId())
            .set(GAMEPLAY_COMMAND.QUEUE_SOURCE_DUE_AT_MS, entity.getQueueSourceDueAtMs())
            .set(GAMEPLAY_COMMAND.TARGET_ENTITY_ID, entity.getTargetEntityId())
            .set(GAMEPLAY_COMMAND.REMOTE_COORDINATOR_ID, entity.getRemoteCoordinatorId())
            .set(GAMEPLAY_COMMAND.REMOTE_FOLLOWUP_ID, entity.getRemoteFollowupId())
            .set(GAMEPLAY_COMMAND.REGION_ID, entity.getRegionId())
            .set(GAMEPLAY_COMMAND.REGION_EPOCH, entity.getRegionEpoch())
            .set(GAMEPLAY_COMMAND.DUE_TICK_ID, entity.getDueTickId())
            .where(GAMEPLAY_COMMAND.ID.eq(entity.getId()))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("Failed to update gameplay_command id=" + entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<GameplayCommand> findById(Long id) {
    return dsl.selectFrom(GAMEPLAY_COMMAND)
        .where(GAMEPLAY_COMMAND.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(GameplayCommandRecord record, GameplayCommand entity) {
    record.setCommandId(entity.getCommandId());
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setSessionId(entity.getSessionId());
    record.setAccountId(entity.getAccountId());
    record.setCharacterId(entity.getCharacterId());
    record.setCommandName(entity.getCommandName());
    record.setCommandText(entity.getCommandText());
    record.setSanitizedCommandText(entity.getSanitizedCommandText());
    record.setRequiresSoloTick(entity.isRequiresSoloTick());
    record.setExecutionOutcome(entity.getExecutionOutcome());
    record.setGameplayResult(entity.getGameplayResult());
    record.setAcceptedAt(toLocalDateTime(entity.getAcceptedAt()));
    record.setStagedAt(toLocalDateTime(entity.getStagedAt()));
    record.setCompletedAt(toLocalDateTime(entity.getCompletedAt()));
    record.setLastAttemptAt(toLocalDateTime(entity.getLastAttemptAt()));
    record.setAttemptCount(entity.getAttemptCount());
    record.setFailureCode(entity.getFailureCode());
    record.setFailureMessage(entity.getFailureMessage());
    record.setSourceType(entity.getSourceType());
    record.setAutomationDispatchId(entity.getAutomationDispatchId());
    record.setAutomationWorkItemId(entity.getAutomationWorkItemId());
    record.setScriptId(entity.getScriptId());
    record.setExecutionHook(entity.getExecutionHook());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setPluginId(entity.getPluginId());
    record.setPluginVersionId(entity.getPluginVersionId());
    record.setPlayableStateScope(entity.getPlayableStateScope());
    record.setWorldSlug(entity.getWorldSlug());
    record.setRealmSlug(entity.getRealmSlug());
    record.setPointerVersion(entity.getPointerVersion());
    record.setOriginSourceKind(entity.getOriginSourceKind());
    record.setOriginSourceState(entity.getOriginSourceState());
    record.setOriginSourceOrdinal(entity.getOriginSourceOrdinal());
    record.setOriginSourceDueTickId(entity.getOriginSourceDueTickId());
    record.setOriginSourceDueAtMs(entity.getOriginSourceDueAtMs());
    record.setQueueSourceKind(entity.getQueueSourceKind());
    record.setQueueSourceState(entity.getQueueSourceState());
    record.setQueueSourceOrdinal(entity.getQueueSourceOrdinal());
    record.setQueueSourceDueTickId(entity.getQueueSourceDueTickId());
    record.setQueueSourceDueAtMs(entity.getQueueSourceDueAtMs());
    record.setTargetEntityId(entity.getTargetEntityId());
    record.setRemoteCoordinatorId(entity.getRemoteCoordinatorId());
    record.setRemoteFollowupId(entity.getRemoteFollowupId());
    record.setRegionId(entity.getRegionId());
    record.setRegionEpoch(entity.getRegionEpoch());
    record.setDueTickId(entity.getDueTickId());
    record.setAdmittedReleaseBundleId(entity.getAdmittedReleaseBundleId());
    record.setAdmittedVersionId(entity.getAdmittedVersionId());
    record.setDeclaredEffectsJson(entity.getDeclaredEffectsJson());
  }

  private GameplayCommand toEntity(Record record) {
    GameplayCommand entity = new GameplayCommand();
    entity.setId(record.get(GAMEPLAY_COMMAND.ID));
    entity.setCommandId(record.get(GAMEPLAY_COMMAND.COMMAND_ID));
    entity.setTenantId(record.get(GAMEPLAY_COMMAND.TENANT_ID));
    entity.setGameInstanceId(record.get(GAMEPLAY_COMMAND.GAME_INSTANCE_ID));
    entity.setSessionId(record.get(GAMEPLAY_COMMAND.SESSION_ID));
    entity.setAccountId(record.get(GAMEPLAY_COMMAND.ACCOUNT_ID));
    entity.setCharacterId(record.get(GAMEPLAY_COMMAND.CHARACTER_ID));
    entity.setCommandName(record.get(GAMEPLAY_COMMAND.COMMAND_NAME));
    entity.setCommandText(record.get(GAMEPLAY_COMMAND.COMMAND_TEXT));
    entity.setSanitizedCommandText(record.get(GAMEPLAY_COMMAND.SANITIZED_COMMAND_TEXT));
    entity.setRequiresSoloTick(
        Boolean.TRUE.equals(record.get(GAMEPLAY_COMMAND.REQUIRES_SOLO_TICK)));
    entity.setExecutionOutcome(record.get(GAMEPLAY_COMMAND.EXECUTION_OUTCOME));
    entity.setGameplayResult(record.get(GAMEPLAY_COMMAND.GAMEPLAY_RESULT));
    entity.setAcceptedAt(toInstant(record.get(GAMEPLAY_COMMAND.ACCEPTED_AT)));
    entity.setStagedAt(toInstant(record.get(GAMEPLAY_COMMAND.STAGED_AT)));
    entity.setCompletedAt(toInstant(record.get(GAMEPLAY_COMMAND.COMPLETED_AT)));
    entity.setLastAttemptAt(toInstant(record.get(GAMEPLAY_COMMAND.LAST_ATTEMPT_AT)));
    entity.setAttemptCount(record.get(GAMEPLAY_COMMAND.ATTEMPT_COUNT));
    entity.setEnqueueSeq(record.get(GAMEPLAY_COMMAND.ENQUEUE_SEQ));
    entity.setFailureCode(record.get(GAMEPLAY_COMMAND.FAILURE_CODE));
    entity.setFailureMessage(record.get(GAMEPLAY_COMMAND.FAILURE_MESSAGE));
    entity.setSourceType(record.get(GAMEPLAY_COMMAND.SOURCE_TYPE));
    entity.setAutomationDispatchId(record.get(GAMEPLAY_COMMAND.AUTOMATION_DISPATCH_ID));
    entity.setAutomationWorkItemId(record.get(GAMEPLAY_COMMAND.AUTOMATION_WORK_ITEM_ID));
    entity.setScriptId(record.get(GAMEPLAY_COMMAND.SCRIPT_ID));
    entity.setExecutionHook(record.get(GAMEPLAY_COMMAND.EXECUTION_HOOK));
    entity.setScriptPatchVersion(record.get(GAMEPLAY_COMMAND.SCRIPT_PATCH_VERSION));
    entity.setPluginId(record.get(GAMEPLAY_COMMAND.PLUGIN_ID));
    entity.setPluginVersionId(record.get(GAMEPLAY_COMMAND.PLUGIN_VERSION_ID));
    entity.setPlayableStateScope(record.get(GAMEPLAY_COMMAND.PLAYABLE_STATE_SCOPE));
    entity.setWorldSlug(record.get(GAMEPLAY_COMMAND.WORLD_SLUG));
    entity.setRealmSlug(record.get(GAMEPLAY_COMMAND.REALM_SLUG));
    entity.setPointerVersion(record.get(GAMEPLAY_COMMAND.POINTER_VERSION));
    entity.setOriginSourceKind(record.get(GAMEPLAY_COMMAND.ORIGIN_SOURCE_KIND));
    entity.setOriginSourceState(record.get(GAMEPLAY_COMMAND.ORIGIN_SOURCE_STATE));
    entity.setOriginSourceOrdinal(record.get(GAMEPLAY_COMMAND.ORIGIN_SOURCE_ORDINAL));
    entity.setOriginSourceDueTickId(record.get(GAMEPLAY_COMMAND.ORIGIN_SOURCE_DUE_TICK_ID));
    entity.setOriginSourceDueAtMs(record.get(GAMEPLAY_COMMAND.ORIGIN_SOURCE_DUE_AT_MS));
    entity.setQueueSourceKind(record.get(GAMEPLAY_COMMAND.QUEUE_SOURCE_KIND));
    entity.setQueueSourceState(record.get(GAMEPLAY_COMMAND.QUEUE_SOURCE_STATE));
    entity.setQueueSourceOrdinal(record.get(GAMEPLAY_COMMAND.QUEUE_SOURCE_ORDINAL));
    entity.setQueueSourceDueTickId(record.get(GAMEPLAY_COMMAND.QUEUE_SOURCE_DUE_TICK_ID));
    entity.setQueueSourceDueAtMs(record.get(GAMEPLAY_COMMAND.QUEUE_SOURCE_DUE_AT_MS));
    entity.setTargetEntityId(record.get(GAMEPLAY_COMMAND.TARGET_ENTITY_ID));
    entity.setRemoteCoordinatorId(record.get(GAMEPLAY_COMMAND.REMOTE_COORDINATOR_ID));
    entity.setRemoteFollowupId(record.get(GAMEPLAY_COMMAND.REMOTE_FOLLOWUP_ID));
    entity.setRegionId(record.get(GAMEPLAY_COMMAND.REGION_ID));
    entity.setRegionEpoch(record.get(GAMEPLAY_COMMAND.REGION_EPOCH));
    entity.setDueTickId(record.get(GAMEPLAY_COMMAND.DUE_TICK_ID));
    entity.setAdmittedReleaseBundleId(record.get(GAMEPLAY_COMMAND.ADMITTED_RELEASE_BUNDLE_ID));
    entity.setAdmittedVersionId(record.get(GAMEPLAY_COMMAND.ADMITTED_VERSION_ID));
    entity.setDeclaredEffectsJson(record.get(GAMEPLAY_COMMAND.DECLARED_EFFECTS_JSON));
    return entity;
  }
}
