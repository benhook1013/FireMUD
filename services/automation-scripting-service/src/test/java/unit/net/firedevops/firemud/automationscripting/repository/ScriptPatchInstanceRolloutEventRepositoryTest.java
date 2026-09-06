package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptPatchInstanceRolloutEvents.SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
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
import org.springframework.dao.OptimisticLockingFailureException;
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
  void allowsExplicitZeroPinEpochWithoutOwnerRequestIdAsUnpinnedFilter() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql().toLowerCase(Locale.ROOT));
          bindingsRef.set(context.bindings());
          return new MockResult[] {
            new MockResult(0, resultDsl.newResult(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS))
          };
        };
    ScriptPatchInstanceRolloutEventRepository repository =
        new ScriptPatchInstanceRolloutEventRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThat(
            repository.findEvents(
                "tenant-1", "game-1", "patch-1", 0L, null, "", null, null, Pageable.unpaged()))
        .isEmpty();
    assertThat(sqlRef.get()).contains("script_pin_epoch");
    assertThat(bindingsRef.get()).contains(0L).doesNotContain("pin-request-1");
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
  void storesEmptyStringForBlankOwnerRequestIdBeforeUpdate() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<String> sqlRef = new AtomicReference<>();
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          String sql = context.sql().toLowerCase(java.util.Locale.ROOT);
          if (sql.startsWith("update")) {
            sqlRef.set(sql);
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
          row.setLastObservedControlPlaneRequestId("");
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
    assertThat(sqlRef.get()).contains("script_pin_epoch");
    assertThat(bindingsRef.get()).contains(0L);
    assertThat(bindingsRef.get()).doesNotContain(" ");
    assertThat(bindingsRef.get()).contains("");
  }

  @Test
  void rejectsExistingIdUpdateWhenOwnerTupleChanges() {
    AtomicReference<String> updateSql = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          String sql = context.sql().toLowerCase(Locale.ROOT);
          if (sql.startsWith("update")) {
            updateSql.set(sql);
            return new MockResult[] {new MockResult(0)};
          }
          return new MockResult[] {
            new MockResult(
                0,
                DSL.using(SQLDialect.POSTGRES)
                    .newResult(SCRIPT_PATCH_INSTANCE_ROLLOUT_EVENTS.fields()))
          };
        };
    ScriptPatchInstanceRolloutEventRepository repository =
        new ScriptPatchInstanceRolloutEventRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptPatchInstanceRolloutEvent changed = new ScriptPatchInstanceRolloutEvent();
    changed.setId(9L);
    changed.setEventId("rollout-event-1");
    changed.setTenantId("tenant-1");
    changed.setGameInstanceId("game-1");
    changed.setScriptPatchVersion("patch-new");
    changed.setScriptPinEpoch(2L);
    changed.setLastObservedControlPlaneRequestId("owner-new");
    changed.setRolloutStatus("ROLLED_BACK");
    changed.setStatusReason("runtime_pin_differs_from_patch");

    assertThatThrownBy(() -> repository.save(changed))
        .isInstanceOf(OptimisticLockingFailureException.class)
        .hasMessage("Stale write rejected for script_patch_instance_rollout_events id=9");
    assertThat(updateSql)
        .hasValueSatisfying(
            sql -> {
              int whereStart = sql.indexOf(" where ");
              assertThat(whereStart).isGreaterThanOrEqualTo(0);
              assertThat(sql.substring(whereStart))
                  .contains(
                      "row_version",
                      "script_patch_version",
                      "script_pin_epoch",
                      "last_observed_control_plane_request_id");
            });
  }

  @Test
  void classifiesReloadedOwnerTupleMismatchSeparatelyFromStaleVersion() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<String> updateSql = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          String sql = context.sql().toLowerCase(Locale.ROOT);
          if (sql.startsWith("update")) {
            updateSql.set(sql);
            return new MockResult[] {new MockResult(0)};
          }
          ScriptPatchInstanceRolloutEventsRecord row = new ScriptPatchInstanceRolloutEventsRecord();
          row.setId(9L);
          row.setEventId("rollout-event-1");
          row.setTenantId("tenant-1");
          row.setGameInstanceId("game-1");
          row.setScriptPatchVersion("patch-current");
          row.setScriptPinEpoch(3L);
          row.setLastObservedControlPlaneRequestId("owner-current");
          row.setRolloutStatus("ROLLED_BACK");
          row.setStatusReason("runtime_pin_differs_from_patch");
          row.setObservedAt(LocalDateTime.parse("2026-08-01T00:00:01"));
          row.setProjectionRefreshedAt(LocalDateTime.parse("2026-08-01T00:00:02"));
          row.setRowVersion(1);
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

    ScriptPatchInstanceRolloutEvent changed = new ScriptPatchInstanceRolloutEvent();
    changed.setId(9L);
    changed.setEventId("rollout-event-1");
    changed.setTenantId("tenant-1");
    changed.setGameInstanceId("game-1");
    changed.setScriptPatchVersion("patch-requested");
    changed.setScriptPinEpoch(2L);
    changed.setLastObservedControlPlaneRequestId("owner-requested");
    changed.setRolloutStatus("ROLLED_BACK");
    changed.setStatusReason("runtime_pin_differs_from_patch");

    assertThatThrownBy(() -> repository.save(changed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Rollout event owner tuple conflict");
    assertThat(updateSql).hasValueSatisfying(sql -> assertThat(sql).startsWith("update"));
  }
}
