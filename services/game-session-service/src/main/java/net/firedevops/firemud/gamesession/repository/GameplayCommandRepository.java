package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.GameplayCommand.GAMEPLAY_COMMAND;
import static net.firedevops.firemud.gamesession.jooq.tables.TickEffect.TICK_EFFECT;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.jooq.tables.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.jooq.tables.records.GameplayCommandRecord;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Select;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GameplayCommandRepository {
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Repository results intentionally return the managed command entity")
  public record IdempotentInsertResult(GameplayCommand command, boolean inserted) {}

  /** Raised when a routed command could not be inserted against the current pointer authority. */
  public static final class AdmissionPointerUnavailableException extends IllegalStateException {
    public AdmissionPointerUnavailableException(String message) {
      super(message);
    }
  }

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

  public boolean hasDurableTickEffect(String commandId) {
    return dsl.fetchExists(
        dsl.selectOne().from(TICK_EFFECT).where(TICK_EFFECT.COMMAND_ID.eq(commandId)));
  }

  /**
   * Serializes the durable admission decision with queue materialization.
   *
   * <p>The row lock is intentionally held by the caller's transaction while it performs the Redis
   * materialization. A concurrent staging or terminal transition therefore either wins before this
   * check, or observes the staged result after this transaction commits. The target and payload
   * fields are compared under that lock so a caller cannot reuse a command identity with different
   * queue materialization evidence.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean lockAcceptedCommandForStaging(
      Long tenantId,
      Long gameInstanceId,
      String commandId,
      String commandText,
      boolean requiresSoloTick) {
    Optional<GameplayCommand> maybeCommand =
        dsl.selectFrom(GAMEPLAY_COMMAND)
            .where(
                GAMEPLAY_COMMAND
                    .TENANT_ID
                    .eq(tenantId)
                    .and(GAMEPLAY_COMMAND.GAME_INSTANCE_ID.eq(gameInstanceId))
                    .and(GAMEPLAY_COMMAND.COMMAND_ID.eq(commandId)))
            .forUpdate()
            .fetchOptional(this::toEntity);
    if (maybeCommand.isEmpty()) {
      return false;
    }
    GameplayCommand command = maybeCommand.orElseThrow();
    if (!Objects.equals(command.getTenantId(), tenantId)
        || !Objects.equals(command.getGameInstanceId(), gameInstanceId)
        || !Objects.equals(command.getCommandText(), commandText)
        || command.isRequiresSoloTick() != requiresSoloTick) {
      throw new IllegalArgumentException(
          "Gameplay command identity was reused with conflicting target or queue payload: "
              + commandId);
    }
    return "ACCEPTED".equals(command.getExecutionOutcome())
        && command.getStagedAt() == null
        && command.getCompletedAt() == null;
  }

  public boolean markAcceptedCommandStaged(String commandId, Instant stagedAt) {
    return dsl.update(GAMEPLAY_COMMAND)
            .set(GAMEPLAY_COMMAND.EXECUTION_OUTCOME, "STAGED")
            .set(GAMEPLAY_COMMAND.STAGED_AT, toLocalDateTime(stagedAt))
            .set(GAMEPLAY_COMMAND.LAST_ATTEMPT_AT, toLocalDateTime(stagedAt))
            .where(
                GAMEPLAY_COMMAND
                    .COMMAND_ID
                    .eq(commandId)
                    .and(GAMEPLAY_COMMAND.EXECUTION_OUTCOME.eq("ACCEPTED"))
                    .and(GAMEPLAY_COMMAND.STAGED_AT.isNull())
                    .and(GAMEPLAY_COMMAND.COMPLETED_AT.isNull()))
            .execute()
        == 1;
  }

  public boolean markAcceptedCommandFailed(
      String commandId, String failureCode, String failureMessage, Instant completedAt) {
    return dsl.update(GAMEPLAY_COMMAND)
            .set(GAMEPLAY_COMMAND.EXECUTION_OUTCOME, "FAILED")
            .set(GAMEPLAY_COMMAND.GAMEPLAY_RESULT, "NOT_APPLIED")
            .set(GAMEPLAY_COMMAND.COMPLETED_AT, toLocalDateTime(completedAt))
            .set(GAMEPLAY_COMMAND.LAST_ATTEMPT_AT, toLocalDateTime(completedAt))
            .set(GAMEPLAY_COMMAND.FAILURE_CODE, failureCode)
            .set(GAMEPLAY_COMMAND.FAILURE_MESSAGE, truncate(failureMessage, 500))
            .where(
                GAMEPLAY_COMMAND
                    .COMMAND_ID
                    .eq(commandId)
                    .and(GAMEPLAY_COMMAND.EXECUTION_OUTCOME.eq("ACCEPTED"))
                    .and(GAMEPLAY_COMMAND.COMPLETED_AT.isNull()))
            .execute()
        == 1;
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

  public List<GameplayCommand> findAcceptedButUnstagedPage(
      Instant acceptedBefore, Instant afterAcceptedAt, long afterId, int pageSize) {
    Condition condition =
        GAMEPLAY_COMMAND
            .EXECUTION_OUTCOME
            .eq("ACCEPTED")
            .and(GAMEPLAY_COMMAND.STAGED_AT.isNull())
            .and(GAMEPLAY_COMMAND.ACCEPTED_AT.lt(toLocalDateTime(acceptedBefore)));
    if (afterAcceptedAt != null) {
      condition =
          condition.and(
              GAMEPLAY_COMMAND
                  .ACCEPTED_AT
                  .gt(toLocalDateTime(afterAcceptedAt))
                  .or(
                      GAMEPLAY_COMMAND
                          .ACCEPTED_AT
                          .eq(toLocalDateTime(afterAcceptedAt))
                          .and(GAMEPLAY_COMMAND.ID.gt(afterId))));
    }
    return dsl.selectFrom(GAMEPLAY_COMMAND)
        .where(condition)
        .orderBy(GAMEPLAY_COMMAND.ACCEPTED_AT.asc(), GAMEPLAY_COMMAND.ID.asc())
        .limit(pageSize)
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
            .set(GAMEPLAY_COMMAND.SCRIPT_PIN_EPOCH, entity.getScriptPinEpoch())
            .set(
                GAMEPLAY_COMMAND.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID,
                entity.getScriptPinControlPlaneRequestId())
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

  @Transactional
  public IdempotentInsertResult insertIfAbsentByIdempotencyIdentity(GameplayCommand entity) {
    if (entity.getId() != null || !hasIdempotencyIdentity(entity)) {
      throw new IllegalArgumentException(
          "A new gameplay command with an idempotency identity is required");
    }
    GameplayCommandRecord record = dsl.newRecord(GAMEPLAY_COMMAND);
    populate(record, entity);

    boolean hasRoutingBundle =
        hasText(entity.getWorldSlug())
            && hasText(entity.getRealmSlug())
            && entity.getPointerVersion() != null
            && entity.getPointerVersion() > 0L;
    if (hasRoutingBundle && !hasText(entity.getPlayableStateScope())) {
      throw new IllegalArgumentException(
          "A routed gameplay command requires a nonblank playable state scope");
    }

    Optional<GameplayCommand> inserted;
    if (hasRoutingBundle) {
      inserted = insertAgainstCurrentAdmissionPointer(record, entity);
    } else {
      inserted = insertWithoutAdmissionPointer(record, entity);
    }

    if (inserted.isPresent()) {
      return new IdempotentInsertResult(inserted.orElseThrow(), true);
    }

    Optional<GameplayCommand> existing;
    if (hasText(entity.getRemoteFollowupId())) {
      existing =
          findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndRemoteFollowupId(
              entity.getTenantId(),
              entity.getGameInstanceId(),
              entity.getRegionId(),
              entity.getRegionEpoch(),
              entity.getRemoteFollowupId());
    } else {
      existing =
          findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
              entity.getTenantId(),
              entity.getGameInstanceId(),
              entity.getRegionId(),
              entity.getRegionEpoch(),
              entity.getAutomationDispatchId());
    }
    if (existing.isPresent()) {
      return new IdempotentInsertResult(existing.orElseThrow(), false);
    }
    if (hasRoutingBundle) {
      throw new AdmissionPointerUnavailableException(
          "Current gameplay admission pointer does not match the routed command");
    }
    throw new IllegalStateException("Idempotency conflict did not yield a gameplay_command row");
  }

  private Optional<GameplayCommand> insertWithoutAdmissionPointer(
      GameplayCommandRecord record, GameplayCommand entity) {
    if (hasText(entity.getRemoteFollowupId())) {
      return dsl.insertInto(GAMEPLAY_COMMAND)
          .set(record)
          .onConflict(
              GAMEPLAY_COMMAND.TENANT_ID,
              GAMEPLAY_COMMAND.GAME_INSTANCE_ID,
              GAMEPLAY_COMMAND.REGION_ID,
              GAMEPLAY_COMMAND.REGION_EPOCH,
              GAMEPLAY_COMMAND.REMOTE_FOLLOWUP_ID)
          .where(GAMEPLAY_COMMAND.REMOTE_FOLLOWUP_ID.isNotNull())
          .doNothing()
          .returning()
          .fetchOptional(this::toEntity);
    }
    return dsl.insertInto(GAMEPLAY_COMMAND)
        .set(record)
        .onConflict(
            GAMEPLAY_COMMAND.TENANT_ID,
            GAMEPLAY_COMMAND.GAME_INSTANCE_ID,
            GAMEPLAY_COMMAND.REGION_ID,
            GAMEPLAY_COMMAND.REGION_EPOCH,
            GAMEPLAY_COMMAND.AUTOMATION_DISPATCH_ID)
        .doNothing()
        .returning()
        .fetchOptional(this::toEntity);
  }

  private Optional<GameplayCommand> insertAgainstCurrentAdmissionPointer(
      GameplayCommandRecord record, GameplayCommand entity) {
    Field<?>[] fields =
        Arrays.stream(GAMEPLAY_COMMAND.fields()).filter(record::changed).toArray(Field<?>[]::new);
    GameplayAdmissionPointer pointer = GameplayAdmissionPointer.GAMEPLAY_ADMISSION_POINTER;
    List<SelectFieldOrAsterisk> values =
        Arrays.stream(fields)
            .map(
                field ->
                    field.equals(GAMEPLAY_COMMAND.WORLD_SLUG)
                        ? pointer.WORLD_SLUG
                        : field.equals(GAMEPLAY_COMMAND.REALM_SLUG)
                            ? pointer.REALM_SLUG
                            : field.equals(GAMEPLAY_COMMAND.POINTER_VERSION)
                                ? pointer.POINTER_VERSION
                                : field.equals(GAMEPLAY_COMMAND.PLAYABLE_STATE_SCOPE)
                                    ? pointer.STATE_SCOPE
                                    : (SelectFieldOrAsterisk)
                                        DSL.val(record.get(field), field.getDataType()))
            .toList();
    Condition pointerMatch =
        pointer
            .TENANT_ID
            .eq(entity.getTenantId())
            .and(pointer.GAME_INSTANCE_ID.eq(entity.getGameInstanceId()))
            .and(
                DSL.lower(pointer.WORLD_SLUG)
                    .eq(entity.getWorldSlug().toLowerCase(java.util.Locale.ROOT)))
            .and(
                DSL.lower(pointer.REALM_SLUG)
                    .eq(entity.getRealmSlug().toLowerCase(java.util.Locale.ROOT)))
            .and(pointer.POINTER_VERSION.eq(entity.getPointerVersion()))
            .and(
                DSL.upper(DSL.trim(pointer.STATE_SCOPE))
                    .eq(entity.getPlayableStateScope().trim().toUpperCase(java.util.Locale.ROOT)));
    Condition targetMatch =
        pointer
            .TENANT_ID
            .eq(entity.getTenantId())
            .and(pointer.GAME_INSTANCE_ID.eq(entity.getGameInstanceId()));
    dsl.select(pointer.ID)
        .from(pointer)
        .where(pointerMatch.and(exactlyOne(targetMatch)))
        .forUpdate()
        .fetch();
    Select<Record> source =
        dsl.select(values).from(pointer).where(pointerMatch.and(exactlyOne(targetMatch)));
    if (hasText(entity.getRemoteFollowupId())) {
      return dsl.insertInto(GAMEPLAY_COMMAND)
          .columns(fields)
          .select(source)
          .onConflict(
              GAMEPLAY_COMMAND.TENANT_ID,
              GAMEPLAY_COMMAND.GAME_INSTANCE_ID,
              GAMEPLAY_COMMAND.REGION_ID,
              GAMEPLAY_COMMAND.REGION_EPOCH,
              GAMEPLAY_COMMAND.REMOTE_FOLLOWUP_ID)
          .where(GAMEPLAY_COMMAND.REMOTE_FOLLOWUP_ID.isNotNull())
          .doNothing()
          .returning()
          .fetchOptional(this::toEntity);
    }
    return dsl.insertInto(GAMEPLAY_COMMAND)
        .columns(fields)
        .select(source)
        .onConflict(
            GAMEPLAY_COMMAND.TENANT_ID,
            GAMEPLAY_COMMAND.GAME_INSTANCE_ID,
            GAMEPLAY_COMMAND.REGION_ID,
            GAMEPLAY_COMMAND.REGION_EPOCH,
            GAMEPLAY_COMMAND.AUTOMATION_DISPATCH_ID)
        .doNothing()
        .returning()
        .fetchOptional(this::toEntity);
  }

  private static Condition exactlyOne(Condition pointerMatch) {
    GameplayAdmissionPointer pointer = GameplayAdmissionPointer.GAMEPLAY_ADMISSION_POINTER;
    return DSL.selectCount().from(pointer).where(pointerMatch).asField().eq(1);
  }

  private static boolean hasIdempotencyIdentity(GameplayCommand entity) {
    return hasText(entity.getRemoteFollowupId()) || hasText(entity.getAutomationDispatchId());
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String truncate(String value, int maxLength) {
    return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
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
    record.setScriptPinEpoch(entity.getScriptPinEpoch());
    record.setScriptPinControlPlaneRequestId(entity.getScriptPinControlPlaneRequestId());
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
    entity.setScriptPinEpoch(record.get(GAMEPLAY_COMMAND.SCRIPT_PIN_EPOCH));
    entity.setScriptPinControlPlaneRequestId(
        record.get(GAMEPLAY_COMMAND.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID));
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
