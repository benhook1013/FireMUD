package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptHandoffEvents.SCRIPT_HANDOFF_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.entity.ScriptHandoffEvent;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptHandoffEventsRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScriptHandoffEventRepositoryTest {
  @Test
  void rejectsPositivePinEpochWithoutOwnerRequestId() {
    ScriptHandoffEventRepository repository =
        new ScriptHandoffEventRepository(DSL.using(SQLDialect.POSTGRES));
    ScriptHandoffEvent event = new ScriptHandoffEvent();
    event.setScriptPinEpoch(2L);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> repository.save(event))
        .withMessage(
            "script_pin_control_plane_request_id is required exactly when script_pin_epoch is positive");
  }

  @Test
  void rejectsOwnerRequestIdOnUnpinnedHandoff() {
    ScriptHandoffEventRepository repository =
        new ScriptHandoffEventRepository(DSL.using(SQLDialect.POSTGRES));
    ScriptHandoffEvent event = new ScriptHandoffEvent();
    event.setScriptPinControlPlaneRequestId("pin-request-1");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> repository.save(event))
        .withMessage(
            "script_pin_control_plane_request_id is required exactly when script_pin_epoch is positive");
  }

  @ParameterizedTest
  @CsvSource({"-1, 0", "0, -1"})
  void rejectsNegativePluginFenceValues(long activationEpoch, long lifecycleRevision) {
    ScriptHandoffEventRepository repository =
        new ScriptHandoffEventRepository(DSL.using(SQLDialect.POSTGRES));
    ScriptHandoffEvent event = new ScriptHandoffEvent();
    event.setPluginActivationEpoch(activationEpoch);
    event.setLifecycleRevision(lifecycleRevision);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> repository.save(event))
        .withMessage("plugin fence values must be non-negative");
  }

  @ParameterizedTest
  @CsvSource({"1, 0", "0, 1"})
  void rejectsIncoherentPluginFenceValues(long activationEpoch, long lifecycleRevision) {
    ScriptHandoffEventRepository repository =
        new ScriptHandoffEventRepository(DSL.using(SQLDialect.POSTGRES));
    ScriptHandoffEvent event = new ScriptHandoffEvent();
    event.setPluginActivationEpoch(activationEpoch);
    event.setLifecycleRevision(lifecycleRevision);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> repository.save(event))
        .withMessage(
            "plugin_activation_epoch and lifecycle_revision must both be zero or both be positive");
  }

  @Test
  void newLogicalCommandUsesEventIdConflictUpsert() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<String> insertSql = new AtomicReference<>();
    AtomicReference<Object[]> bindings = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          insertSql.set(context.sql().toLowerCase(Locale.ROOT));
          bindings.set(context.bindings());
          ScriptHandoffEventsRecord row = new ScriptHandoffEventsRecord();
          row.setId(9L);
          row.setEventId("she-work-item-99-command-0");
          row.setTenantId("tenant-1");
          row.setGameInstanceId("game-1");
          row.setScriptPatchVersion("patch-1");
          row.setScriptPinEpoch(2L);
          row.setScriptPinControlPlaneRequestId("pin-request-1");
          row.setScriptId("script-1");
          row.setBindingId("binding-1");
          row.setPluginId("plugin-1");
          row.setPluginVersionId("plugin-version-1");
          row.setPluginActivationEpoch(7L);
          row.setLifecycleRevision(9L);
          row.setWorkItemId(99L);
          row.setCommandOrdinal(0);
          row.setAutomationDispatchId("workItem:99#0");
          row.setTargetEntityId("entity-1");
          row.setHandoffOutcome("duplicate_noop");
          row.setHandoffReason("game_session_accepted");
          row.setObservedAt(LocalDateTime.parse("2026-08-01T00:00:01"));
          row.setRowVersion(1);
          Record returned = resultDsl.newRecord(SCRIPT_HANDOFF_EVENTS.fields());
          returned.from(row);
          Result<Record> result = resultDsl.newResult(SCRIPT_HANDOFF_EVENTS.fields());
          result.add(returned);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptHandoffEventRepository repository =
        new ScriptHandoffEventRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptHandoffEvent event = new ScriptHandoffEvent();
    event.setEventId("she-work-item-99-command-0");
    event.setTenantId("tenant-1");
    event.setGameInstanceId("game-1");
    event.setScriptPatchVersion("patch-1");
    event.setScriptPinEpoch(2L);
    event.setScriptPinControlPlaneRequestId("pin-request-1");
    event.setScriptId("script-1");
    event.setBindingId("binding-1");
    event.setPluginId("plugin-1");
    event.setPluginVersionId("plugin-version-1");
    event.setPluginActivationEpoch(7L);
    event.setLifecycleRevision(9L);
    event.setWorkItemId(99L);
    event.setCommandOrdinal(0);
    event.setAutomationDispatchId("workItem:99#0");
    event.setTargetEntityId("entity-1");
    event.setHandoffOutcome("duplicate_noop");
    event.setHandoffReason("game_session_accepted");
    event.setObservedAt(java.time.Instant.parse("2026-08-01T00:00:01Z"));

    ScriptHandoffEvent saved = repository.save(event);

    assertThat(saved.getId()).isEqualTo(9L);
    assertThat(saved.getEventId()).isEqualTo("she-work-item-99-command-0");
    assertThat(saved.getScriptPatchVersion()).isEqualTo("patch-1");
    assertThat(saved.getScriptPinEpoch()).isEqualTo(2L);
    assertThat(saved.getScriptPinControlPlaneRequestId()).isEqualTo("pin-request-1");
    assertThat(saved.getBindingId()).isEqualTo("binding-1");
    assertThat(saved.getPluginId()).isEqualTo("plugin-1");
    assertThat(saved.getPluginVersionId()).isEqualTo("plugin-version-1");
    assertThat(saved.getPluginActivationEpoch()).isEqualTo(7L);
    assertThat(saved.getLifecycleRevision()).isEqualTo(9L);
    assertThat(bindings).hasValueSatisfying(values -> assertThat(values).contains(7L, 9L));
    assertThat(insertSql)
        .hasValueSatisfying(
            sql ->
                assertThat(sql)
                    .contains(
                        "on conflict",
                        "event_id",
                        "script_pin_epoch",
                        "script_pin_control_plane_request_id",
                        "plugin_activation_epoch",
                        "lifecycle_revision",
                        "do update",
                        "handoff_outcome")
                    .doesNotContain("uuid"));
    assertThat(conflictTarget(insertSql.get()))
        .contains("event_id")
        .doesNotContain("script_pin_epoch", "script_pin_control_plane_request_id");
    assertThat(insertSql)
        .hasValueSatisfying(
            sql -> {
              int updateStart = sql.indexOf(" do update");
              int whereStart = sql.indexOf(" where ", updateStart);
              assertThat(updateStart).isGreaterThanOrEqualTo(0);
              assertThat(whereStart).isGreaterThan(updateStart);
              assertThat(sql.substring(updateStart, whereStart))
                  .doesNotContain(
                      "tenant_id",
                      "game_instance_id",
                      "script_patch_version",
                      "script_pin_epoch",
                      "script_id",
                      "binding_id",
                      "plugin_id",
                      "plugin_version_id",
                      "plugin_activation_epoch",
                      "lifecycle_revision",
                      "work_item_id",
                      "command_ordinal",
                      "automation_dispatch_id",
                      "target_game_instance_id",
                      "target_region_id",
                      "target_region_epoch",
                      "target_entity_id",
                      "playable_state_scope",
                      "world_slug",
                      "realm_slug",
                      "pointer_version",
                      "source_kind",
                      "source_state",
                      "source_ordinal",
                      "source_due_tick_id",
                      "source_due_at_ms",
                      "emitted_command_text");
              assertThat(sql.substring(whereStart))
                  .contains(
                      "event_id",
                      "tenant_id",
                      "game_instance_id",
                      "script_patch_version",
                      "script_pin_epoch",
                      "script_pin_control_plane_request_id",
                      "script_id",
                      "binding_id",
                      "plugin_id",
                      "plugin_version_id",
                      "plugin_activation_epoch",
                      "lifecycle_revision",
                      "work_item_id",
                      "command_ordinal",
                      "automation_dispatch_id",
                      "target_game_instance_id",
                      "target_region_id",
                      "target_region_epoch",
                      "target_entity_id",
                      "playable_state_scope",
                      "world_slug",
                      "realm_slug",
                      "pointer_version",
                      "source_kind",
                      "source_state",
                      "source_ordinal",
                      "source_due_tick_id",
                      "source_due_at_ms",
                      "emitted_command_text");
            });
  }

  @Test
  void normalizesBlankOwnerRequestIdBeforeConflictComparison() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          bindingsRef.set(context.bindings());
          ScriptHandoffEventsRecord row = new ScriptHandoffEventsRecord();
          row.setId(9L);
          row.setEventId("event-1");
          row.setTenantId("tenant-1");
          row.setGameInstanceId("game-1");
          row.setScriptPatchVersion("patch-1");
          row.setScriptPinEpoch(0L);
          row.setPluginActivationEpoch(0L);
          row.setLifecycleRevision(0L);
          row.setScriptId("script-1");
          row.setHandoffOutcome("enqueued");
          row.setObservedAt(LocalDateTime.parse("2026-08-01T00:00:01"));
          Record returned = resultDsl.newRecord(SCRIPT_HANDOFF_EVENTS.fields());
          returned.from(row);
          Result<Record> result = resultDsl.newResult(SCRIPT_HANDOFF_EVENTS.fields());
          result.add(returned);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptHandoffEventRepository repository =
        new ScriptHandoffEventRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptHandoffEvent event = new ScriptHandoffEvent();
    event.setEventId("event-1");
    event.setTenantId("tenant-1");
    event.setGameInstanceId("game-1");
    event.setScriptPatchVersion("patch-1");
    event.setScriptPinControlPlaneRequestId(" ");
    event.setPluginActivationEpoch(0L);
    event.setLifecycleRevision(0L);
    event.setHandoffOutcome("enqueued");

    ScriptHandoffEvent saved = repository.save(event);

    assertThat(saved.getScriptPinControlPlaneRequestId()).isNull();
    assertThat(saved.getPluginActivationEpoch()).isZero();
    assertThat(saved.getLifecycleRevision()).isZero();
    assertThat(bindingsRef.get()).doesNotContain(" ");
  }

  @ParameterizedTest
  @CsvSource({"3, 2", "2, 3"})
  void rejectsRetryWhenPluginFenceChanges(long activationEpoch, long lifecycleRevision) {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          String sql = context.sql().toLowerCase(Locale.ROOT);
          if (sql.startsWith("insert")) {
            return new MockResult[] {
              new MockResult(0, resultDsl.newResult(SCRIPT_HANDOFF_EVENTS.fields()))
            };
          }
          ScriptHandoffEventsRecord row = new ScriptHandoffEventsRecord();
          row.setId(9L);
          row.setEventId("event-1");
          row.setTenantId("tenant-1");
          row.setGameInstanceId("game-1");
          row.setScriptPatchVersion("patch-1");
          row.setPluginActivationEpoch(2L);
          row.setLifecycleRevision(2L);
          row.setHandoffOutcome("enqueued");
          Result<Record> result = resultDsl.newResult(SCRIPT_HANDOFF_EVENTS.fields());
          Record returned = resultDsl.newRecord(SCRIPT_HANDOFF_EVENTS.fields());
          returned.from(row);
          result.add(returned);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptHandoffEventRepository repository =
        new ScriptHandoffEventRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptHandoffEvent retry = new ScriptHandoffEvent();
    retry.setEventId("event-1");
    retry.setTenantId("tenant-1");
    retry.setGameInstanceId("game-1");
    retry.setScriptPatchVersion("patch-1");
    retry.setPluginActivationEpoch(activationEpoch);
    retry.setLifecycleRevision(lifecycleRevision);
    retry.setHandoffOutcome("enqueued");

    assertThatThrownBy(() -> repository.save(retry))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Handoff event immutable identity conflict");
  }

  @Test
  void rejectsRetryWhenExistingEventCarriesDifferentPinOwnerTuple() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<String> insertSql = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          String sql = context.sql().toLowerCase(Locale.ROOT);
          if (sql.startsWith("insert")) {
            insertSql.set(sql);
            return new MockResult[] {
              new MockResult(0, resultDsl.newResult(SCRIPT_HANDOFF_EVENTS.fields()))
            };
          }
          ScriptHandoffEventsRecord row = new ScriptHandoffEventsRecord();
          row.setId(9L);
          row.setEventId("event-1");
          row.setTenantId("tenant-1");
          row.setGameInstanceId("game-1");
          row.setScriptPatchVersion("patch-original");
          row.setScriptPinEpoch(2L);
          row.setScriptPinControlPlaneRequestId("owner-original");
          row.setHandoffOutcome("enqueued");
          row.setHandoffReason("game_session_accepted");
          Result<Record> result = resultDsl.newResult(SCRIPT_HANDOFF_EVENTS.fields());
          Record returned = resultDsl.newRecord(SCRIPT_HANDOFF_EVENTS.fields());
          returned.from(row);
          result.add(returned);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptHandoffEventRepository repository =
        new ScriptHandoffEventRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptHandoffEvent retry = new ScriptHandoffEvent();
    retry.setEventId("event-1");
    retry.setTenantId("tenant-1");
    retry.setGameInstanceId("game-1");
    retry.setScriptPatchVersion("patch-new");
    retry.setScriptPinEpoch(3L);
    retry.setScriptPinControlPlaneRequestId("owner-new");
    retry.setHandoffOutcome("enqueued");
    retry.setHandoffReason("changed-input");

    assertThatThrownBy(() -> repository.save(retry))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Handoff event owner tuple conflict");
    assertThat(insertSql.get()).contains("script_patch_version", "script_pin_epoch");
  }

  @Test
  void rejectsExistingIdUpdateWhenOwnerRequestChanges() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<String> updateSql = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          String sql = context.sql().toLowerCase(Locale.ROOT);
          if (sql.startsWith("update")) {
            updateSql.set(sql);
            return new MockResult[] {new MockResult(0)};
          }
          ScriptHandoffEventsRecord row = new ScriptHandoffEventsRecord();
          row.setId(9L);
          row.setEventId("event-1");
          row.setTenantId("tenant-1");
          row.setGameInstanceId("game-1");
          row.setScriptPatchVersion("patch-1");
          row.setScriptPinEpoch(2L);
          row.setScriptPinControlPlaneRequestId("owner-original");
          row.setHandoffOutcome("enqueued");
          row.setRowVersion(0);
          Record returned = resultDsl.newRecord(SCRIPT_HANDOFF_EVENTS.fields());
          returned.from(row);
          Result<Record> result = resultDsl.newResult(SCRIPT_HANDOFF_EVENTS.fields());
          result.add(returned);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptHandoffEventRepository repository =
        new ScriptHandoffEventRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptHandoffEvent changed = new ScriptHandoffEvent();
    changed.setId(9L);
    changed.setEventId("event-1");
    changed.setTenantId("tenant-1");
    changed.setGameInstanceId("game-1");
    changed.setScriptPatchVersion("patch-1");
    changed.setScriptPinEpoch(2L);
    changed.setScriptPinControlPlaneRequestId("owner-new");
    changed.setHandoffOutcome("enqueued");

    assertThatThrownBy(() -> repository.save(changed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Handoff event owner tuple conflict");
    assertThat(updateSql)
        .hasValueSatisfying(
            sql -> {
              int whereStart = sql.indexOf(" where ");
              assertThat(whereStart).isGreaterThanOrEqualTo(0);
              assertThat(sql.substring(0, whereStart))
                  .doesNotContain(
                      "event_id",
                      "tenant_id",
                      "game_instance_id",
                      "script_patch_version",
                      "script_pin_epoch",
                      "script_id",
                      "binding_id",
                      "plugin_id",
                      "plugin_version_id",
                      "plugin_activation_epoch",
                      "lifecycle_revision",
                      "work_item_id",
                      "command_ordinal",
                      "automation_dispatch_id",
                      "target_game_instance_id",
                      "target_region_id",
                      "target_region_epoch",
                      "target_entity_id",
                      "playable_state_scope",
                      "world_slug",
                      "realm_slug",
                      "pointer_version",
                      "source_kind",
                      "source_state",
                      "source_ordinal",
                      "source_due_tick_id",
                      "source_due_at_ms",
                      "emitted_command_text");
              assertThat(sql.substring(whereStart))
                  .contains(
                      "event_id",
                      "tenant_id",
                      "game_instance_id",
                      "row_version",
                      "script_patch_version",
                      "script_pin_epoch",
                      "script_pin_control_plane_request_id",
                      "script_id",
                      "binding_id",
                      "plugin_id",
                      "plugin_version_id",
                      "plugin_activation_epoch",
                      "lifecycle_revision",
                      "work_item_id",
                      "command_ordinal",
                      "automation_dispatch_id",
                      "target_game_instance_id",
                      "target_region_id",
                      "target_region_epoch",
                      "target_entity_id",
                      "playable_state_scope",
                      "world_slug",
                      "realm_slug",
                      "pointer_version",
                      "source_kind",
                      "source_state",
                      "source_ordinal",
                      "source_due_tick_id",
                      "source_due_at_ms",
                      "emitted_command_text");
            });
  }

  @Test
  void treatsEventIdAsImmutableOnExistingIdUpdate() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<String> updateSql = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          String sql = context.sql().toLowerCase(Locale.ROOT);
          if (sql.startsWith("update")) {
            updateSql.set(sql);
            return new MockResult[] {new MockResult(0)};
          }
          ScriptHandoffEventsRecord row = new ScriptHandoffEventsRecord();
          row.setId(9L);
          row.setEventId("event-original");
          row.setTenantId("tenant-1");
          row.setGameInstanceId("game-1");
          row.setScriptPatchVersion("patch-1");
          row.setScriptPinEpoch(2L);
          row.setScriptPinControlPlaneRequestId("owner-1");
          row.setHandoffOutcome("enqueued");
          row.setRowVersion(0);
          Record returned = resultDsl.newRecord(SCRIPT_HANDOFF_EVENTS.fields());
          returned.from(row);
          Result<Record> result = resultDsl.newResult(SCRIPT_HANDOFF_EVENTS.fields());
          result.add(returned);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptHandoffEventRepository repository =
        new ScriptHandoffEventRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptHandoffEvent changed = new ScriptHandoffEvent();
    changed.setId(9L);
    changed.setEventId("event-replacement");
    changed.setTenantId("tenant-1");
    changed.setGameInstanceId("game-1");
    changed.setScriptPatchVersion("patch-1");
    changed.setScriptPinEpoch(2L);
    changed.setScriptPinControlPlaneRequestId("owner-1");
    changed.setHandoffOutcome("enqueued");

    assertThatThrownBy(() -> repository.save(changed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Handoff event immutable identity conflict");
    assertThat(updateSql)
        .hasValueSatisfying(
            sql -> {
              int whereStart = sql.indexOf(" where ");
              assertThat(whereStart).isGreaterThanOrEqualTo(0);
              assertThat(sql.substring(0, whereStart)).doesNotContain("event_id =");
              assertThat(sql.substring(whereStart)).contains("event_id", "row_version");
            });
  }

  @Test
  void retentionCleanupBindsUtcOffsetDateTimeForNullableHoldComparison() {
    Instant safeWatermark = Instant.parse("2026-08-01T00:00:00Z");
    Instant now = Instant.parse("2026-08-02T00:00:00Z");
    AtomicReference<String> sqlRef = new AtomicReference<>();
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          bindingsRef.set(context.bindings());
          return new MockResult[] {new MockResult(0)};
        };
    ScriptHandoffEventRepository repository =
        new ScriptHandoffEventRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThat(repository.deleteExpiredRetentionEvidence(safeWatermark, now)).isZero();

    assertThat(bindingsRef.get()[0]).isInstanceOf(java.sql.Timestamp.class);
    assertThat(bindingsRef.get()).contains("2026-08-02 00:00:00+00:00");
    assertThat(bindingsRef.get()).contains("DEAD_LETTERED");
    assertThat(bindingsRef.get())
        .anySatisfy(
            binding -> {
              assertThat(binding).isInstanceOf(Number.class);
              assertThat(((Number) binding).longValue()).isEqualTo(500L);
            });
    String renderedSql = sqlRef.get().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    int outerTuplePredicate = renderedSql.indexOf(") in (");
    assertThat(outerTuplePredicate).as(renderedSql).isGreaterThanOrEqualTo(0);
    assertThat(renderedSql.substring(0, outerTuplePredicate))
        .contains("\"script_handoff_events\".\"id\"", "\"script_handoff_events\".\"tenant_id\"");
    assertThat(renderedSql)
        .contains(
            "select \"retention_candidates\".\"id\", \"retention_candidates\".\"tenant_id\"",
            "tenant_id",
            "observed_at",
            " < ",
            "retention_hold_until",
            "handoff_outcome",
            "regexp_replace",
            "retention_siblings",
            "is null",
            " <= ",
            "status",
            "not in",
            "order by \"retention_candidates\".\"event_id\" asc",
            "fetch next ? rows only");
    assertThat(renderedSql).doesNotContain("select \"retention_candidates\".\"event_id\"");
  }

  @Test
  void retentionHoldWriteUsesUtcOffsetDateTimeAndAllowsClearingHold() {
    Instant holdUntil = Instant.parse("2026-08-03T00:00:00Z");
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    AtomicReference<String> sqlRef = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          bindingsRef.set(context.bindings());
          sqlRef.set(context.sql());
          return new MockResult[] {new MockResult(1)};
        };
    ScriptHandoffEventRepository repository =
        new ScriptHandoffEventRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThat(repository.setRetentionHold("tenant-1", 41L, holdUntil)).isTrue();
    assertThat(bindingsRef.get()).contains("2026-08-03 00:00:00+00:00");

    assertThat(repository.setRetentionHold("tenant-1", 41L, null)).isTrue();
    assertThat(retentionHoldSetClause(sqlRef.get())).contains("retention_hold_until");
    assertThat(bindingsRef.get()).contains((Object) null);
  }

  @Test
  void newLogicalChildUsesAtomicCurrentEventIdConflictUpsert() {
    AtomicReference<String> sql = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sql.set(context.sql());
          ScriptHandoffEventsRecord row = handoffRecord();
          var result = resultDsl.newResult(SCRIPT_HANDOFF_EVENTS);
          result.add(row);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptHandoffEventRepository repository =
        new ScriptHandoffEventRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptHandoffEvent saved = repository.save(handoffEvent());

    assertThat(saved.getId()).isEqualTo(41L);
    String renderedSql = sql.get().toLowerCase(Locale.ROOT);
    int conflictIndex = renderedSql.indexOf("on conflict");
    int updateIndex = renderedSql.indexOf("do update", conflictIndex);
    assertThat(conflictIndex).as(renderedSql).isGreaterThanOrEqualTo(0);
    assertThat(updateIndex).as(renderedSql).isGreaterThan(conflictIndex);
    assertThat(renderedSql.substring(conflictIndex, updateIndex)).contains("event_id");
    assertThat(renderedSql).contains("where");
    assertThat(renderedSql).contains("returning", "do update");
  }

  @Test
  void newLogicalChildFailsWhenConflictResolutionReturnsNoDurableRow() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> new MockResult[] {new MockResult(0, resultDsl.newResult(SCRIPT_HANDOFF_EVENTS))};
    ScriptHandoffEventRepository repository =
        new ScriptHandoffEventRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThatThrownBy(() -> repository.save(handoffEvent()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not yield a persisted row");
  }

  private static ScriptHandoffEventsRecord handoffRecord() {
    ScriptHandoffEventsRecord record = new ScriptHandoffEventsRecord();
    record.setId(41L);
    record.setEventId("event-1");
    record.setTenantId("tenant-1");
    record.setGameInstanceId("game-1");
    record.setScriptPatchVersion("patch-1");
    record.setScriptId("script-1");
    record.setWorkItemId(99L);
    record.setCommandOrdinal(0);
    record.setAutomationDispatchId("dispatch-1");
    record.setTargetEntityId("entity-1");
    record.setEmittedCommandText("look");
    record.setHandoffOutcome("enqueued");
    record.setHandoffReason("game_session_accepted");
    record.setObservedAt(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
    record.setRowVersion(0);
    return record;
  }

  private static ScriptHandoffEvent handoffEvent() {
    ScriptHandoffEvent event = new ScriptHandoffEvent();
    event.setEventId("event-1");
    event.setTenantId("tenant-1");
    event.setGameInstanceId("game-1");
    event.setScriptPatchVersion("patch-1");
    event.setScriptId("script-1");
    event.setWorkItemId(99L);
    event.setCommandOrdinal(0);
    event.setAutomationDispatchId("dispatch-1");
    event.setTargetEntityId("entity-1");
    event.setEmittedCommandText("look");
    event.setHandoffOutcome("enqueued");
    event.setHandoffReason("game_session_accepted");
    event.setObservedAt(Instant.parse("2026-01-01T00:00:00Z"));
    return event;
  }

  private static String retentionHoldSetClause(String sql) {
    String normalized = sql.toLowerCase(Locale.ROOT);
    int setStart = normalized.indexOf(" set ");
    int whereStart = normalized.indexOf(" where ", setStart);
    assertThat(setStart).as(sql).isGreaterThanOrEqualTo(0);
    assertThat(whereStart).as(sql).isGreaterThan(setStart);
    return normalized.substring(setStart, whereStart);
  }

  private static String conflictTarget(String sql) {
    String normalized = sql.toLowerCase(Locale.ROOT);
    int conflictStart = normalized.indexOf("on conflict");
    assertThat(conflictStart)
        .as("SQL must contain an ON CONFLICT marker")
        .isGreaterThanOrEqualTo(0);
    int actionStart = normalized.indexOf(" do update", conflictStart);
    assertThat(actionStart).as("SQL must contain a DO UPDATE marker").isGreaterThan(conflictStart);
    return normalized.substring(conflictStart, actionStart);
  }
}
