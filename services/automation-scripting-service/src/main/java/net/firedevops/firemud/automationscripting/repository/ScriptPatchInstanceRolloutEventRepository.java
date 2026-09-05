package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptPatchInstanceRolloutEvents.SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.blankToNull;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.limitOrDefault;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.offsetOrZero;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchInstanceRolloutEvent;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptPatchInstanceRolloutEventsRecord;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ScriptPatchInstanceRolloutEventRepository {
  private final DSLContext dsl;

  public ScriptPatchInstanceRolloutEventRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<ScriptPatchInstanceRolloutEvent> findEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      Long scriptPinEpoch,
      String lastObservedControlPlaneRequestId,
      String rolloutStatus,
      Instant changedAfter,
      Instant changedBefore,
      Pageable pageable) {
    requireCoherentPinFilter(scriptPinEpoch, lastObservedControlPlaneRequestId);
    Condition condition = SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.TENANT_ID.eq(tenantId);
    if (!gameInstanceId.isBlank()) {
      condition =
          condition.and(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.GAME_INSTANCE_ID.eq(gameInstanceId));
    }
    if (!scriptPatchVersion.isBlank()) {
      condition =
          condition.and(
              SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.SCRIPT_PATCH_VERSION.eq(scriptPatchVersion));
    }
    if (scriptPinEpoch != null) {
      condition =
          condition.and(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.SCRIPT_PIN_EPOCH.eq(scriptPinEpoch));
    }
    if (lastObservedControlPlaneRequestId != null && !lastObservedControlPlaneRequestId.isBlank()) {
      condition =
          condition.and(
              SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.LAST_OBSERVED_CONTROL_PLANE_REQUEST_ID.eq(
                  lastObservedControlPlaneRequestId));
    }
    if (!rolloutStatus.isBlank()) {
      condition =
          condition.and(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.ROLLOUT_STATUS.eq(rolloutStatus));
    }
    if (changedAfter != null) {
      condition =
          condition.and(
              SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.OBSERVED_AT.gt(toLocalDateTime(changedAfter)));
    }
    if (changedBefore != null) {
      condition =
          condition.and(
              SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.OBSERVED_AT.lt(toLocalDateTime(changedBefore)));
    }
    return dsl.selectFrom(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS)
        .where(condition)
        .orderBy(
            SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.OBSERVED_AT.desc(),
            SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.EVENT_ID.desc())
        .limit(limitOrDefault(pageable, 100))
        .offset(offsetOrZero(pageable))
        .fetch(this::toEntity);
  }

  public List<ScriptPatchInstanceRolloutEvent> findEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      String rolloutStatus,
      Instant changedAfter,
      Instant changedBefore,
      Pageable pageable) {
    return findEvents(
        tenantId,
        gameInstanceId,
        scriptPatchVersion,
        null,
        null,
        rolloutStatus,
        changedAfter,
        changedBefore,
        pageable);
  }

  public ScriptPatchInstanceRolloutEvent save(ScriptPatchInstanceRolloutEvent entity) {
    requireCoherentPinTuple(entity);
    if (entity.getId() == null) {
      ScriptPatchInstanceRolloutEventsRecord record =
          dsl.newRecord(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS);
      populate(record, entity);
      record.store();
      return findById(record.getId()).orElseThrow();
    }
    int nextRowVersion = entity.getRowVersion() + 1;
    int updated =
        dsl.update(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS)
            .set(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.EVENT_ID, entity.getEventId())
            .set(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.TENANT_ID, entity.getTenantId())
            .set(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.GAME_INSTANCE_ID, entity.getGameInstanceId())
            .set(
                SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.SCRIPT_PATCH_VERSION,
                entity.getScriptPatchVersion())
            .set(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.SCRIPT_PIN_EPOCH, entity.getScriptPinEpoch())
            .set(
                SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.LAST_OBSERVED_CONTROL_PLANE_REQUEST_ID,
                blankToNull(entity.getLastObservedControlPlaneRequestId()))
            .set(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.ROLLOUT_STATUS, entity.getRolloutStatus())
            .set(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.STATUS_REASON, entity.getStatusReason())
            .set(
                SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.OBSERVED_AT,
                toLocalDateTime(entity.getObservedAt()))
            .set(
                SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.PROJECTION_REFRESHED_AT,
                toLocalDateTime(entity.getProjectionRefreshedAt()))
            .set(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.ROW_VERSION, nextRowVersion)
            .where(
                SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS
                    .ID
                    .eq(entity.getId())
                    .and(
                        SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.ROW_VERSION.eq(
                            entity.getRowVersion())))
            .execute();
    if (updated != 1) {
      throw AutomationScriptingJooqRepositorySupport.staleWrite(
          "script_patch_instance_rollout_events", entity.getId());
    }
    entity.setRowVersion(nextRowVersion);
    return findById(entity.getId()).orElseThrow();
  }

  private java.util.Optional<ScriptPatchInstanceRolloutEvent> findById(Long id) {
    return dsl.selectFrom(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS)
        .where(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.ID.eq(id))
        .fetchOptional(this::toEntity);
  }

  private void populate(
      ScriptPatchInstanceRolloutEventsRecord record, ScriptPatchInstanceRolloutEvent entity) {
    record.setEventId(entity.getEventId());
    record.setTenantId(entity.getTenantId());
    record.setGameInstanceId(entity.getGameInstanceId());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setScriptPinEpoch(entity.getScriptPinEpoch());
    record.setLastObservedControlPlaneRequestId(
        blankToNull(entity.getLastObservedControlPlaneRequestId()));
    record.setRolloutStatus(entity.getRolloutStatus());
    record.setStatusReason(entity.getStatusReason());
    record.setObservedAt(toLocalDateTime(entity.getObservedAt()));
    record.setProjectionRefreshedAt(toLocalDateTime(entity.getProjectionRefreshedAt()));
    record.setRowVersion(entity.getRowVersion());
  }

  private ScriptPatchInstanceRolloutEvent toEntity(Record record) {
    ScriptPatchInstanceRolloutEvent entity = new ScriptPatchInstanceRolloutEvent();
    entity.setId(record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.ID));
    entity.setEventId(record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.EVENT_ID));
    entity.setTenantId(record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.TENANT_ID));
    entity.setGameInstanceId(record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.GAME_INSTANCE_ID));
    entity.setScriptPatchVersion(
        record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.SCRIPT_PATCH_VERSION));
    Long scriptPinEpoch = record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.SCRIPT_PIN_EPOCH);
    entity.setScriptPinEpoch(scriptPinEpoch == null ? 0L : scriptPinEpoch);
    entity.setLastObservedControlPlaneRequestId(
        blankToNull(
            record.get(
                SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.LAST_OBSERVED_CONTROL_PLANE_REQUEST_ID)));
    entity.setRolloutStatus(record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.ROLLOUT_STATUS));
    entity.setStatusReason(record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.STATUS_REASON));
    entity.setObservedAt(toInstant(record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.OBSERVED_AT)));
    entity.setProjectionRefreshedAt(
        toInstant(record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.PROJECTION_REFRESHED_AT)));
    Integer rowVersion = record.get(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.ROW_VERSION);
    entity.setRowVersion(rowVersion == null ? 0 : rowVersion);
    return entity;
  }

  private static void requireCoherentPinTuple(ScriptPatchInstanceRolloutEvent entity) {
    requireCoherentPinTuple(
        entity.getScriptPinEpoch(), entity.getLastObservedControlPlaneRequestId());
  }

  private static void requireCoherentPinFilter(Long scriptPinEpoch, String requestId) {
    if (scriptPinEpoch != null && scriptPinEpoch < 0L) {
      throw new IllegalArgumentException("script_pin_epoch must be non-negative");
    }
    boolean hasPositiveEpoch = scriptPinEpoch != null && scriptPinEpoch > 0L;
    boolean hasRequestId = blankToNull(requestId) != null;
    if (hasPositiveEpoch != hasRequestId || (scriptPinEpoch != null && scriptPinEpoch == 0L)) {
      throw new IllegalArgumentException(
          "script_pin_control_plane_request_id must be present exactly when script_pin_epoch is positive");
    }
  }

  private static void requireCoherentPinTuple(Long scriptPinEpoch, String requestId) {
    if (scriptPinEpoch != null && scriptPinEpoch < 0L) {
      throw new IllegalArgumentException("script_pin_epoch must be non-negative");
    }
    boolean hasRequestId = blankToNull(requestId) != null;
    if ((scriptPinEpoch != null && scriptPinEpoch > 0L) != hasRequestId) {
      throw new IllegalArgumentException(
          "script_pin_control_plane_request_id must be present exactly when script_pin_epoch is positive");
    }
  }
}
