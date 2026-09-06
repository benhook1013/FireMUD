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
              .where(immutableIdentityCondition(entity))
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
      if (!immutableIdentityMatches(existing, entity)) {
        throw identityConflict(existing, entity);
      }
      return existing;
    }
    int nextRowVersion = entity.getRowVersion() + 1;
    int updated =
        dsl.update(SCRIPT_HANDOFF_EVENTS)
            // Identity and binding evidence is immutable after the child is created. Retries only
            // advance the downstream disposition/attempt projection below.
            .set(SCRIPT_HANDOFF_EVENTS.GAME_SESSION_COMMAND_ID, entity.getGameSessionCommandId())
            .set(SCRIPT_HANDOFF_EVENTS.REMOTE_COORDINATOR_ID, entity.getRemoteCoordinatorId())
            .set(SCRIPT_HANDOFF_EVENTS.REMOTE_FOLLOWUP_ID, entity.getRemoteFollowupId())
            .set(SCRIPT_HANDOFF_EVENTS.HANDOFF_OUTCOME, entity.getHandoffOutcome())
            .set(SCRIPT_HANDOFF_EVENTS.HANDOFF_REASON, entity.getHandoffReason())
            .set(SCRIPT_HANDOFF_EVENTS.OBSERVED_AT, toLocalDateTime(entity.getObservedAt()))
            .set(SCRIPT_HANDOFF_EVENTS.ROW_VERSION, nextRowVersion)
            .where(
                SCRIPT_HANDOFF_EVENTS
                    .ID
                    .eq(entity.getId())
                    .and(SCRIPT_HANDOFF_EVENTS.ROW_VERSION.eq(entity.getRowVersion()))
                    .and(immutableIdentityCondition(entity)))
            .execute();
    if (updated != 1) {
      Optional<ScriptHandoffEvent> existing = findById(entity.getId());
      if (existing.isPresent() && !immutableIdentityMatches(existing.orElseThrow(), entity)) {
        throw identityConflict(existing.orElseThrow(), entity);
      }
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

  private static IllegalStateException identityConflict(
      ScriptHandoffEvent existing, ScriptHandoffEvent incoming) {
    if (!ownerTupleMatches(existing, incoming)) {
      return new IllegalStateException("Handoff event owner tuple conflict");
    }
    return new IllegalStateException("Handoff event immutable identity conflict");
  }

  private static boolean immutableIdentityMatches(
      ScriptHandoffEvent existing, ScriptHandoffEvent incoming) {
    return Objects.equals(existing.getEventId(), incoming.getEventId())
        && Objects.equals(existing.getTenantId(), incoming.getTenantId())
        && Objects.equals(existing.getGameInstanceId(), incoming.getGameInstanceId())
        && Objects.equals(existing.getScriptPatchVersion(), incoming.getScriptPatchVersion())
        && existing.getScriptPinEpoch() == incoming.getScriptPinEpoch()
        && Objects.equals(
            blankToNull(existing.getScriptPinControlPlaneRequestId()),
            blankToNull(incoming.getScriptPinControlPlaneRequestId()))
        && Objects.equals(existing.getScriptId(), incoming.getScriptId())
        && Objects.equals(existing.getBindingId(), incoming.getBindingId())
        && Objects.equals(existing.getPluginId(), incoming.getPluginId())
        && Objects.equals(existing.getPluginVersionId(), incoming.getPluginVersionId())
        && Objects.equals(existing.getWorkItemId(), incoming.getWorkItemId())
        && existing.getCommandOrdinal() == incoming.getCommandOrdinal()
        && Objects.equals(existing.getAutomationDispatchId(), incoming.getAutomationDispatchId())
        && Objects.equals(existing.getTargetGameInstanceId(), incoming.getTargetGameInstanceId())
        && Objects.equals(existing.getTargetRegionId(), incoming.getTargetRegionId())
        && existing.getTargetRegionEpoch() == incoming.getTargetRegionEpoch()
        && Objects.equals(existing.getTargetEntityId(), incoming.getTargetEntityId())
        && Objects.equals(existing.getPlayableStateScope(), incoming.getPlayableStateScope())
        && Objects.equals(existing.getWorldSlug(), incoming.getWorldSlug())
        && Objects.equals(existing.getRealmSlug(), incoming.getRealmSlug())
        && Objects.equals(existing.getPointerVersion(), incoming.getPointerVersion())
        && Objects.equals(existing.getSourceKind(), incoming.getSourceKind())
        && Objects.equals(existing.getSourceState(), incoming.getSourceState())
        && Objects.equals(existing.getSourceOrdinal(), incoming.getSourceOrdinal())
        && Objects.equals(existing.getSourceDueTickId(), incoming.getSourceDueTickId())
        && Objects.equals(existing.getSourceDueAtMs(), incoming.getSourceDueAtMs())
        && Objects.equals(existing.getEmittedCommandText(), incoming.getEmittedCommandText());
  }

  private static Condition immutableIdentityCondition(ScriptHandoffEvent entity) {
    String normalizedRequestId = blankToNull(entity.getScriptPinControlPlaneRequestId());
    return SCRIPT_HANDOFF_EVENTS
        .EVENT_ID
        .isNotDistinctFrom(entity.getEventId())
        .and(SCRIPT_HANDOFF_EVENTS.TENANT_ID.isNotDistinctFrom(entity.getTenantId()))
        .and(SCRIPT_HANDOFF_EVENTS.GAME_INSTANCE_ID.isNotDistinctFrom(entity.getGameInstanceId()))
        .and(
            SCRIPT_HANDOFF_EVENTS.SCRIPT_PATCH_VERSION.isNotDistinctFrom(
                entity.getScriptPatchVersion()))
        .and(SCRIPT_HANDOFF_EVENTS.SCRIPT_PIN_EPOCH.eq(entity.getScriptPinEpoch()))
        .and(
            SCRIPT_HANDOFF_EVENTS.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID.isNotDistinctFrom(
                normalizedRequestId))
        .and(SCRIPT_HANDOFF_EVENTS.SCRIPT_ID.isNotDistinctFrom(entity.getScriptId()))
        .and(SCRIPT_HANDOFF_EVENTS.BINDING_ID.isNotDistinctFrom(entity.getBindingId()))
        .and(SCRIPT_HANDOFF_EVENTS.PLUGIN_ID.isNotDistinctFrom(entity.getPluginId()))
        .and(SCRIPT_HANDOFF_EVENTS.PLUGIN_VERSION_ID.isNotDistinctFrom(entity.getPluginVersionId()))
        .and(SCRIPT_HANDOFF_EVENTS.WORK_ITEM_ID.eq(entity.getWorkItemId()))
        .and(SCRIPT_HANDOFF_EVENTS.COMMAND_ORDINAL.eq(entity.getCommandOrdinal()))
        .and(
            SCRIPT_HANDOFF_EVENTS.AUTOMATION_DISPATCH_ID.isNotDistinctFrom(
                entity.getAutomationDispatchId()))
        .and(
            SCRIPT_HANDOFF_EVENTS.TARGET_GAME_INSTANCE_ID.isNotDistinctFrom(
                entity.getTargetGameInstanceId()))
        .and(SCRIPT_HANDOFF_EVENTS.TARGET_REGION_ID.isNotDistinctFrom(entity.getTargetRegionId()))
        .and(SCRIPT_HANDOFF_EVENTS.TARGET_REGION_EPOCH.eq(entity.getTargetRegionEpoch()))
        .and(SCRIPT_HANDOFF_EVENTS.TARGET_ENTITY_ID.isNotDistinctFrom(entity.getTargetEntityId()))
        .and(
            SCRIPT_HANDOFF_EVENTS.PLAYABLE_STATE_SCOPE.isNotDistinctFrom(
                entity.getPlayableStateScope()))
        .and(SCRIPT_HANDOFF_EVENTS.WORLD_SLUG.isNotDistinctFrom(entity.getWorldSlug()))
        .and(SCRIPT_HANDOFF_EVENTS.REALM_SLUG.isNotDistinctFrom(entity.getRealmSlug()))
        .and(SCRIPT_HANDOFF_EVENTS.POINTER_VERSION.isNotDistinctFrom(entity.getPointerVersion()))
        .and(SCRIPT_HANDOFF_EVENTS.SOURCE_KIND.isNotDistinctFrom(entity.getSourceKind()))
        .and(SCRIPT_HANDOFF_EVENTS.SOURCE_STATE.isNotDistinctFrom(entity.getSourceState()))
        .and(SCRIPT_HANDOFF_EVENTS.SOURCE_ORDINAL.isNotDistinctFrom(entity.getSourceOrdinal()))
        .and(
            SCRIPT_HANDOFF_EVENTS.SOURCE_DUE_TICK_ID.isNotDistinctFrom(entity.getSourceDueTickId()))
        .and(SCRIPT_HANDOFF_EVENTS.SOURCE_DUE_AT_MS.isNotDistinctFrom(entity.getSourceDueAtMs()))
        .and(
            SCRIPT_HANDOFF_EVENTS.EMITTED_COMMAND_TEXT.isNotDistinctFrom(
                entity.getEmittedCommandText()));
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
