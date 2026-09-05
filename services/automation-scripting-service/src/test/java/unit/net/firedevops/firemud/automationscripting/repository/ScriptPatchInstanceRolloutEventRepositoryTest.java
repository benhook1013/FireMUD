package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptPatchInstanceRolloutEvents.SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchInstanceRolloutEvent;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptPatchInstanceRolloutEventsRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class ScriptPatchInstanceRolloutEventRepositoryTest {
  @Test
  void rejectsNegativePinEpochBeforeInsert() {
    ScriptPatchInstanceRolloutEventRepository repository =
        new ScriptPatchInstanceRolloutEventRepository(DSL.using(SQLDialect.POSTGRES));
    ScriptPatchInstanceRolloutEvent event = new ScriptPatchInstanceRolloutEvent();
    event.setScriptPinEpoch(-1L);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> repository.save(event))
        .withMessage("script_pin_epoch must be non-negative");
  }

  @Test
  void rejectsPositivePinEpochWithoutOwnerRequestId() {
    ScriptPatchInstanceRolloutEventRepository repository =
        new ScriptPatchInstanceRolloutEventRepository(DSL.using(SQLDialect.POSTGRES));
    ScriptPatchInstanceRolloutEvent event = new ScriptPatchInstanceRolloutEvent();
    event.setScriptPinEpoch(2L);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> repository.save(event))
        .withMessage(
            "script_pin_control_plane_request_id must be present exactly when script_pin_epoch is positive");
  }

  @Test
  void rejectsOwnerRequestIdWithUnpinnedEpoch() {
    ScriptPatchInstanceRolloutEventRepository repository =
        new ScriptPatchInstanceRolloutEventRepository(DSL.using(SQLDialect.POSTGRES));
    ScriptPatchInstanceRolloutEvent event = new ScriptPatchInstanceRolloutEvent();
    event.setLastObservedControlPlaneRequestId("pin-request-1");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> repository.save(event))
        .withMessage(
            "script_pin_control_plane_request_id must be present exactly when script_pin_epoch is positive");
  }

  @Test
  void rejectsPositivePinEpochWithoutOwnerRequestIdBeforeQuery() {
    ScriptPatchInstanceRolloutEventRepository repository =
        new ScriptPatchInstanceRolloutEventRepository(DSL.using(SQLDialect.POSTGRES));

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                repository.findEvents(
                    "tenant-1", "game-1", "patch-1", 2L, null, "", null, null, Pageable.unpaged()))
        .withMessage(
            "script_pin_control_plane_request_id must be present exactly when script_pin_epoch is positive");
  }

  @Test
  void rejectsOwnerRequestIdWithoutPositivePinEpochBeforeQuery() {
    ScriptPatchInstanceRolloutEventRepository repository =
        new ScriptPatchInstanceRolloutEventRepository(DSL.using(SQLDialect.POSTGRES));

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                repository.findEvents(
                    "tenant-1",
                    "game-1",
                    "patch-1",
                    null,
                    "pin-request-1",
                    "",
                    null,
                    null,
                    Pageable.unpaged()))
        .withMessage(
            "script_pin_control_plane_request_id must be present exactly when script_pin_epoch is positive");
  }

  @Test
  void rejectsZeroPinEpochWithOwnerRequestIdBeforeQuery() {
    ScriptPatchInstanceRolloutEventRepository repository =
        new ScriptPatchInstanceRolloutEventRepository(DSL.using(SQLDialect.POSTGRES));

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                repository.findEvents(
                    "tenant-1",
                    "game-1",
                    "patch-1",
                    0L,
                    "pin-request-1",
                    "",
                    null,
                    null,
                    Pageable.unpaged()))
        .withMessage(
            "script_pin_control_plane_request_id must be present exactly when script_pin_epoch is positive");
  }

  @Test
  void rejectsExplicitZeroPinEpochWithoutOwnerRequestIdBeforeQuery() {
    ScriptPatchInstanceRolloutEventRepository repository =
        new ScriptPatchInstanceRolloutEventRepository(DSL.using(SQLDialect.POSTGRES));

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                repository.findEvents(
                    "tenant-1", "game-1", "patch-1", 0L, null, "", null, null, Pageable.unpaged()))
        .withMessage(
            "script_pin_control_plane_request_id must be present exactly when script_pin_epoch is positive");
  }

  @Test
  void rejectsNegativePinEpochBeforeQuery() {
    ScriptPatchInstanceRolloutEventRepository repository =
        new ScriptPatchInstanceRolloutEventRepository(DSL.using(SQLDialect.POSTGRES));

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                repository.findEvents(
                    "tenant-1", "game-1", "patch-1", -1L, null, "", null, null, Pageable.unpaged()))
        .withMessage("script_pin_epoch must be non-negative");
  }

  @Test
  void normalizesBlankOwnerRequestIdBeforeUpdate() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          String sql = context.sql().toLowerCase(java.util.Locale.ROOT);
          if (sql.startsWith("update")) {
            bindingsRef.set(context.bindings());
            return new MockResult[] {new MockResult(1)};
          }
          ScriptPatchInstanceRolloutEventsRecord row = new ScriptPatchInstanceRolloutEventsRecord();
          row.setId(9L);
          row.setEventId("rollout-event-1");
          row.setTenantId("tenant-1");
          row.setGameInstanceId("game-1");
          row.setScriptPatchVersion("patch-1");
          row.setScriptPinEpoch(0L);
          row.setLastObservedControlPlaneRequestId(null);
          row.setRolloutStatus("ROLLED_BACK");
          row.setStatusReason("runtime_pin_differs_from_patch");
          row.setObservedAt(LocalDateTime.parse("2026-08-01T00:00:01"));
          row.setProjectionRefreshedAt(LocalDateTime.parse("2026-08-01T00:00:02"));
          Record returned = resultDsl.newRecord(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.fields());
          returned.from(row);
          Result<Record> result =
              resultDsl.newResult(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.fields());
          result.add(returned);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptPatchInstanceRolloutEventRepository repository =
        new ScriptPatchInstanceRolloutEventRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptPatchInstanceRolloutEvent event = new ScriptPatchInstanceRolloutEvent();
    event.setId(9L);
    event.setEventId("rollout-event-1");
    event.setTenantId("tenant-1");
    event.setGameInstanceId("game-1");
    event.setScriptPatchVersion("patch-1");
    event.setLastObservedControlPlaneRequestId(" ");
    event.setObservedAt(Instant.parse("2026-08-01T00:00:01Z"));
    event.setProjectionRefreshedAt(Instant.parse("2026-08-01T00:00:02Z"));

    ScriptPatchInstanceRolloutEvent saved = repository.save(event);

    assertThat(saved.getLastObservedControlPlaneRequestId()).isNull();
    assertThat(bindingsRef.get()).doesNotContain(" ");
  }
}
