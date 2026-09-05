package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptHandoffEvents.SCRIPT_HANDOFF_EVENTS;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.blankToNull;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.limitOrDefault;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.offsetOrZero;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptHandoffEvent;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptHandoffEventsRecord;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ScriptHandoffEventRepository {
  private final DSLContext dsl;

  public ScriptHandoffEventRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<ScriptHandoffEvent> findEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      Long workItemId,
      String handoffOutcome,
      String targetGameInstanceId,
      String targetRegionId,
      long targetRegionEpoch,
      String remoteCoordinatorId,
      String remoteFollowupId,
      String scriptId,
      String pluginId,
      String automationDispatchId,
      String gameSessionCommandId,
      String targetEntityId,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      String pointerVersion,
      String sourceKind,
      String sourceState,
      Instant changedAfter,
      Instant changedBefore,
      Pageable pageable) {
    Condition condition = SCRIPT_HANDOFF_EVENTS.TENANT_ID.eq(tenantId);
    if (!gameInstanceId.isBlank()) {
      condition = condition.and(SCRIPT_HANDOFF_EVENTS.GAME_INSTANCE_ID.eq(gameInstanceId));
    }
    if (!scriptPatchVersion.isBlank()) {
      condition = condition.and(SCRIPT_HANDOFF_EVENTS.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion));
    }
    if (workItemId != null) {
      condition = condition.and(SCRIPT_HANDOFF_EVENTS.WORK_ITEM_ID.eq(workItemId));
    }
    if (!handoffOutcome.isBlank()) {
      condition = condition.and(SCRIPT_HANDOFF_EVENTS.HANDOFF_OUTCOME.eq(handoffOutcome));
    }
    if (!targetGameInstanceId.isBlank()) {
      condition =
          condition.and(SCRIPT_HANDOFF_EVENTS.TARGET_GAME_INSTANCE_ID.eq(targetGameInstanceId));
    }
    if (!targetRegionId.isBlank()) {
      condition = condition.and(SCRIPT_HANDOFF_EVENTS.TARGET_REGION_ID.eq(targetRegionId));
    }
    if (targetRegionEpoch > 0) {
      condition = condition.and(SCRIPT_HANDOFF_EVENTS.TARGET_REGION_EPOCH.eq(targetRegionEpoch));
    }
    if (!remoteCoordinatorId.isBlank()) {
      condition =
          condition.and(SCRIPT_HANDOFF_EVENTS.REMOTE_COORDINATOR_ID.eq(remoteCoordinatorId));
    }
    if (!remoteFollowupId.isBlank()) {
      condition = condition.and(SCRIPT_HANDOFF_EVENTS.REMOTE_FOLLOWUP_ID.eq(remoteFollowupId));
    }
    if (!scriptId.isBlank()) {
      condition = condition.and(SCRIPT_HANDOFF_EVENTS.SCRIPT_ID.eq(scriptId));
    }
    if (!pluginId.isBlank()) {
      condition = condition.and(SCRIPT_HANDOFF_EVENTS.PLUGIN_ID.eq(pluginId));
    }
    if (!automationDispatchId.isBlank()) {
      condition =
          condition.and(SCRIPT_HANDOFF_EVENTS.AUTOMATION_DISPATCH_ID.eq(automationDispatchId));
    }
    if (!gameSessionCommandId.isBlank()) {
      condition =
          condition.and(SCRIPT_HANDOFF_EVENTS.GAME_SESSION_COMMAND_ID.eq(gameSessionCommandId));
    }
    if (!targetEntityId.isBlank()) {
      condition = condition.and(SCRIPT_HANDOFF_EVENTS.TARGET_ENTITY_ID.eq(targetEntityId));
    }
    if (!playableStateScope.isBlank()) {
      condition = condition.and(SCRIPT_HANDOFF_EVENTS.PLAYABLE_STATE_SCOPE.eq(playableStateScope));
    }
    if (!worldSlug.isBlank()) {
      condition = condition.and(SCRIPT_HANDOFF_EVENTS.WORLD_SLUG.eq(worldSlug));
    }
    if (!realmSlug.isBlank()) {
      condition = condition.and(SCRIPT_HANDOFF_EVENTS.REALM_SLUG.eq(realmSlug));
    }
    if (!pointerVersion.isBlank()) {
      condition = condition.and(SCRIPT_HANDOFF_EVENTS.POINTER_VERSION.eq(pointerVersion));
    }
    if (!sourceKind.isBlank()) {
      condition = condition.and(SCRIPT_HANDOFF_EVENTS.SOURCE_KIND.eq(sourceKind));
    }
    if (!sourceState.isBlank()) {
      condition = condition.and(SCRIPT_HANDOFF_EVENTS.SOURCE_STATE.eq(sourceState));
    }
    if (changedAfter != null) {
      condition =
          condition.and(SCRIPT_HANDOFF_EVENTS.OBSERVED_AT.gt(toLocalDateTime(changedAfter)));
    }
    if (changedBefore != null) {
      condition =
          condition.and(SCRIPT_HANDOFF_EVENTS.OBSERVED_AT.lt(toLocalDateTime(changedBefore)));
    }
    return dsl.selectFrom(SCRIPT_HANDOFF_EVENTS)
        .where(condition)
        .orderBy(SCRIPT_HANDOFF_EVENTS.OBSERVED_AT.desc(), SCRIPT_HANDOFF_EVENTS.EVENT_ID.desc())
        .limit(limitOrDefault(pageable, 100))
        .offset(offsetOrZero(pageable))
        .fetch(this::toEntity);
  }

  public ScriptHandoffEvent save(ScriptHandoffEvent entity) {
    requireCoherentPinTuple(entity);
    if (entity.getId() == null) {
      ScriptHandoffEventsRecord record = dsl.newRecord(SCRIPT_HANDOFF_EVENTS);
      populate(record, entity);
      // event_id is the deterministic logical command identity assigned by the handoff service.
      // A retry may reach this boundary after Game Session has already accepted the command, so
      // converge the same child row instead of inserting another disposition.
      String normalizedRequestId = blankToNull(entity.getScriptPinControlPlaneRequestId());
      Condition ownerTupleMatches =
          SCRIPT_HANDOFF_EVENTS
              .SCRIPT_PATCH_VERSION
              .eq(entity.getScriptPatchVersion())
              .and(SCRIPT_HANDOFF_EVENTS.SCRIPT_PIN_EPOCH.eq(entity.getScriptPinEpoch()))
              .and(
                  SCRIPT_HANDOFF_EVENTS.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID.isNotDistinctFrom(
                      normalizedRequestId));
      Optional<ScriptHandoffEvent> replay =
          dsl.insertInto(SCRIPT_HANDOFF_EVENTS)
              .set(record)
              .onConflict(SCRIPT_HANDOFF_EVENTS.EVENT_ID)
              .doUpdate()
              .set(SCRIPT_HANDOFF_EVENTS.GAME_SESSION_COMMAND_ID, entity.getGameSessionCommandId())
              .set(SCRIPT_HANDOFF_EVENTS.REMOTE_COORDINATOR_ID, entity.getRemoteCoordinatorId())
              .set(SCRIPT_HANDOFF_EVENTS.REMOTE_FOLLOWUP_ID, entity.getRemoteFollowupId())
              .set(SCRIPT_HANDOFF_EVENTS.HANDOFF_OUTCOME, entity.getHandoffOutcome())
              .set(SCRIPT_HANDOFF_EVENTS.HANDOFF_REASON, entity.getHandoffReason())
              .set(SCRIPT_HANDOFF_EVENTS.OBSERVED_AT, toLocalDateTime(entity.getObservedAt()))
              .set(SCRIPT_HANDOFF_EVENTS.ROW_VERSION, SCRIPT_HANDOFF_EVENTS.ROW_VERSION.plus(1))
              // event_id is the child identity. A retry carrying a different script owner tuple is
              // conflicting input, not permission to rewrite the original handoff evidence.
              .where(ownerTupleMatches)
              .returning()
              .fetchOptional(this::toEntity);
      if (replay.isPresent()) {
        return replay.get();
      }
      ScriptHandoffEvent existing =
          findByEventId(entity.getEventId())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "handoff event conflict did not yield a persisted row"));
      if (!ownerTupleMatches(existing, entity)) {
        throw new IllegalStateException("Handoff event owner tuple conflict");
      }
      return existing;
    }
    int nextRowVersion = entity.getRowVersion() + 1;
    int updated =
        dsl.update(SCRIPT_HANDOFF_EVENTS)
            .set(SCRIPT_HANDOFF_EVENTS.EVENT_ID, entity.getEventId())
            .set(SCRIPT_HANDOFF_EVENTS.TENANT_ID, entity.getTenantId())
            .set(SCRIPT_HANDOFF_EVENTS.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(SCRIPT_HANDOFF_EVENTS.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
            .set(SCRIPT_HANDOFF_EVENTS.SCRIPT_PIN_EPOCH, entity.getScriptPinEpoch())
            .set(
                SCRIPT_HANDOFF_EVENTS.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID,
                blankToNull(entity.getScriptPinControlPlaneRequestId()))
            .set(SCRIPT_HANDOFF_EVENTS.SCRIPT_ID, entity.getScriptId())
            .set(SCRIPT_HANDOFF_EVENTS.BINDING_ID, entity.getBindingId())
            .set(SCRIPT_HANDOFF_EVENTS.PLUGIN_ID, entity.getPluginId())
            .set(SCRIPT_HANDOFF_EVENTS.PLUGIN_VERSION_ID, entity.getPluginVersionId())
            .set(SCRIPT_HANDOFF_EVENTS.WORK_ITEM_ID, entity.getWorkItemId())
            .set(SCRIPT_HANDOFF_EVENTS.COMMAND_ORDINAL, entity.getCommandOrdinal())
            .set(SCRIPT_HANDOFF_EVENTS.AUTOMATION_DISPATCH_ID, entity.getAutomationDispatchId())
            .set(SCRIPT_HANDOFF_EVENTS.GAME_SESSION_COMMAND_ID, entity.getGameSessionCommandId())
            .set(SCRIPT_HANDOFF_EVENTS.TARGET_GAME_INSTANCE_ID, entity.getTargetGameInstanceId())
            .set(SCRIPT_HANDOFF_EVENTS.TARGET_REGION_ID, entity.getTargetRegionId())
            .set(SCRIPT_HANDOFF_EVENTS.TARGET_REGION_EPOCH, entity.getTargetRegionEpoch())
            .set(SCRIPT_HANDOFF_EVENTS.REMOTE_COORDINATOR_ID, entity.getRemoteCoordinatorId())
            .set(SCRIPT_HANDOFF_EVENTS.REMOTE_FOLLOWUP_ID, entity.getRemoteFollowupId())
            .set(SCRIPT_HANDOFF_EVENTS.TARGET_ENTITY_ID, entity.getTargetEntityId())
            .set(SCRIPT_HANDOFF_EVENTS.PLAYABLE_STATE_SCOPE, entity.getPlayableStateScope())
            .set(SCRIPT_HANDOFF_EVENTS.WORLD_SLUG, entity.getWorldSlug())
            .set(SCRIPT_HANDOFF_EVENTS.REALM_SLUG, entity.getRealmSlug())
            .set(SCRIPT_HANDOFF_EVENTS.POINTER_VERSION, entity.getPointerVersion())
            .set(SCRIPT_HANDOFF_EVENTS.SOURCE_KIND, entity.getSourceKind())
            .set(SCRIPT_HANDOFF_EVENTS.SOURCE_STATE, entity.getSourceState())
            .set(SCRIPT_HANDOFF_EVENTS.SOURCE_ORDINAL, entity.getSourceOrdinal())
            .set(SCRIPT_HANDOFF_EVENTS.SOURCE_DUE_TICK_ID, entity.getSourceDueTickId())
            .set(SCRIPT_HANDOFF_EVENTS.SOURCE_DUE_AT_MS, entity.getSourceDueAtMs())
            .set(SCRIPT_HANDOFF_EVENTS.EMITTED_COMMAND_TEXT, entity.getEmittedCommandText())
            .set(SCRIPT_HANDOFF_EVENTS.HANDOFF_OUTCOME, entity.getHandoffOutcome())
            .set(SCRIPT_HANDOFF_EVENTS.HANDOFF_REASON, entity.getHandoffReason())
            .set(SCRIPT_HANDOFF_EVENTS.OBSERVED_AT, toLocalDateTime(entity.getObservedAt()))
            .set(SCRIPT_HANDOFF_EVENTS.ROW_VERSION, nextRowVersion)
            .where(
                SCRIPT_HANDOFF_EVENTS
                    .ID
                    .eq(entity.getId())
                    .and(SCRIPT_HANDOFF_EVENTS.ROW_VERSION.eq(entity.getRowVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite(
          "script_handoff_events", entity.getId());
    }
    entity.setRowVersion(nextRowVersion);
    return findById(entity.getId()).orElseThrow();
  }

  private Optional<ScriptHandoffEvent> findById(Long id) {
    return dsl.selectFrom(SCRIPT_HANDOFF_EVENTS)
        .where(SCRIPT_HANDOFF_EVENTS.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private Optional<ScriptHandoffEvent> findByEventId(String eventId) {
    return dsl.selectFrom(SCRIPT_HANDOFF_EVENTS)
        .where(SCRIPT_HANDOFF_EVENTS.EVENT_ID.eq(eventId))
        .fetchOptional(this::toEntity);
  }

  private static boolean ownerTupleMatches(
      ScriptHandoffEvent existing, ScriptHandoffEvent incoming) {
    return Objects.equals(existing.getScriptPatchVersion(), incoming.getScriptPatchVersion())
        && existing.getScriptPinEpoch() == incoming.getScriptPinEpoch()
        && Objects.equals(
            blankToNull(existing.getScriptPinControlPlaneRequestId()),
            blankToNull(incoming.getScriptPinControlPlaneRequestId()));
  }

  private void populate(ScriptHandoffEventsRecord record, ScriptHandoffEvent entity) {
    record.setEventId(entity.getEventId());
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setScriptPinEpoch(entity.getScriptPinEpoch());
    record.set(
        SCRIPT_HANDOFF_EVENTS.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID,
        blankToNull(entity.getScriptPinControlPlaneRequestId()));
    record.setScriptId(entity.getScriptId());
    record.setBindingId(entity.getBindingId());
    record.setPluginId(entity.getPluginId());
    record.setPluginVersionId(entity.getPluginVersionId());
    record.setWorkItemId(entity.getWorkItemId());
    record.setCommandOrdinal(entity.getCommandOrdinal());
    record.setAutomationDispatchId(entity.getAutomationDispatchId());
    record.setGameSessionCommandId(entity.getGameSessionCommandId());
    record.setTargetGameInstanceId(entity.getTargetGameInstanceId());
    record.setTargetRegionId(entity.getTargetRegionId());
    record.setTargetRegionEpoch(entity.getTargetRegionEpoch());
    record.setRemoteCoordinatorId(entity.getRemoteCoordinatorId());
    record.setRemoteFollowupId(entity.getRemoteFollowupId());
    record.setTargetEntityId(entity.getTargetEntityId());
    record.setPlayableStateScope(entity.getPlayableStateScope());
    record.setWorldSlug(entity.getWorldSlug());
    record.setRealmSlug(entity.getRealmSlug());
    record.setPointerVersion(entity.getPointerVersion());
    record.setSourceKind(entity.getSourceKind());
    record.setSourceState(entity.getSourceState());
    record.setSourceOrdinal(entity.getSourceOrdinal());
    record.setSourceDueTickId(entity.getSourceDueTickId());
    record.setSourceDueAtMs(entity.getSourceDueAtMs());
    record.setEmittedCommandText(entity.getEmittedCommandText());
    record.setHandoffOutcome(entity.getHandoffOutcome());
    record.setHandoffReason(entity.getHandoffReason());
    record.setObservedAt(toLocalDateTime(entity.getObservedAt()));
    record.setRowVersion(entity.getRowVersion());
  }

  private static void requireCoherentPinTuple(ScriptHandoffEvent entity) {
    if (entity.getScriptPinEpoch() < 0L) {
      throw new IllegalArgumentException("script_pin_epoch must be non-negative");
    }
    boolean hasRequestId = blankToNull(entity.getScriptPinControlPlaneRequestId()) != null;
    if ((entity.getScriptPinEpoch() > 0L) != hasRequestId) {
      throw new IllegalArgumentException(
          "script_pin_control_plane_request_id is required exactly when script_pin_epoch is positive");
    }
  }

  private ScriptHandoffEvent toEntity(Record record) {
    ScriptHandoffEvent entity = new ScriptHandoffEvent();
    entity.setId(record.get(SCRIPT_HANDOFF_EVENTS.ID));
    entity.setEventId(record.get(SCRIPT_HANDOFF_EVENTS.EVENT_ID));
    entity.setTenantId(record.get(SCRIPT_HANDOFF_EVENTS.TENANT_ID));
    entity.setGameInstanceId(record.get(SCRIPT_HANDOFF_EVENTS.GAME_INSTANCE_ID));
    entity.setScriptPatchVersion(record.get(SCRIPT_HANDOFF_EVENTS.SCRIPT_PATCH_VERSION));
    Long scriptPinEpoch = record.get(SCRIPT_HANDOFF_EVENTS.SCRIPT_PIN_EPOCH);
    entity.setScriptPinEpoch(scriptPinEpoch == null ? 0L : scriptPinEpoch);
    entity.setScriptPinControlPlaneRequestId(
        blankToNull(record.get(SCRIPT_HANDOFF_EVENTS.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID)));
    entity.setScriptId(record.get(SCRIPT_HANDOFF_EVENTS.SCRIPT_ID));
    entity.setBindingId(record.get(SCRIPT_HANDOFF_EVENTS.BINDING_ID));
    entity.setPluginId(record.get(SCRIPT_HANDOFF_EVENTS.PLUGIN_ID));
    entity.setPluginVersionId(record.get(SCRIPT_HANDOFF_EVENTS.PLUGIN_VERSION_ID));
    entity.setWorkItemId(record.get(SCRIPT_HANDOFF_EVENTS.WORK_ITEM_ID));
    Integer commandOrdinal = record.get(SCRIPT_HANDOFF_EVENTS.COMMAND_ORDINAL);
    entity.setCommandOrdinal(commandOrdinal == null ? 0 : commandOrdinal);
    entity.setAutomationDispatchId(record.get(SCRIPT_HANDOFF_EVENTS.AUTOMATION_DISPATCH_ID));
    entity.setGameSessionCommandId(record.get(SCRIPT_HANDOFF_EVENTS.GAME_SESSION_COMMAND_ID));
    entity.setTargetGameInstanceId(record.get(SCRIPT_HANDOFF_EVENTS.TARGET_GAME_INSTANCE_ID));
    entity.setTargetRegionId(record.get(SCRIPT_HANDOFF_EVENTS.TARGET_REGION_ID));
    Long targetRegionEpoch = record.get(SCRIPT_HANDOFF_EVENTS.TARGET_REGION_EPOCH);
    entity.setTargetRegionEpoch(targetRegionEpoch == null ? 0L : targetRegionEpoch);
    entity.setRemoteCoordinatorId(record.get(SCRIPT_HANDOFF_EVENTS.REMOTE_COORDINATOR_ID));
    entity.setRemoteFollowupId(record.get(SCRIPT_HANDOFF_EVENTS.REMOTE_FOLLOWUP_ID));
    entity.setTargetEntityId(record.get(SCRIPT_HANDOFF_EVENTS.TARGET_ENTITY_ID));
    entity.setPlayableStateScope(record.get(SCRIPT_HANDOFF_EVENTS.PLAYABLE_STATE_SCOPE));
    entity.setWorldSlug(record.get(SCRIPT_HANDOFF_EVENTS.WORLD_SLUG));
    entity.setRealmSlug(record.get(SCRIPT_HANDOFF_EVENTS.REALM_SLUG));
    entity.setPointerVersion(record.get(SCRIPT_HANDOFF_EVENTS.POINTER_VERSION));
    entity.setSourceKind(record.get(SCRIPT_HANDOFF_EVENTS.SOURCE_KIND));
    entity.setSourceState(record.get(SCRIPT_HANDOFF_EVENTS.SOURCE_STATE));
    entity.setSourceOrdinal(record.get(SCRIPT_HANDOFF_EVENTS.SOURCE_ORDINAL));
    entity.setSourceDueTickId(record.get(SCRIPT_HANDOFF_EVENTS.SOURCE_DUE_TICK_ID));
    entity.setSourceDueAtMs(record.get(SCRIPT_HANDOFF_EVENTS.SOURCE_DUE_AT_MS));
    entity.setEmittedCommandText(record.get(SCRIPT_HANDOFF_EVENTS.EMITTED_COMMAND_TEXT));
    entity.setHandoffOutcome(record.get(SCRIPT_HANDOFF_EVENTS.HANDOFF_OUTCOME));
    entity.setHandoffReason(record.get(SCRIPT_HANDOFF_EVENTS.HANDOFF_REASON));
    entity.setObservedAt(toInstant(record.get(SCRIPT_HANDOFF_EVENTS.OBSERVED_AT)));
    Integer rowVersion = record.get(SCRIPT_HANDOFF_EVENTS.ROW_VERSION);
    entity.setRowVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }
}
