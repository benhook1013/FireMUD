package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptHandoffEvents.SCRIPT_HANDOFF_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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

  @Test
  void newLogicalCommandUsesEventIdConflictUpsert() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<String> insertSql = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          insertSql.set(context.sql().toLowerCase(Locale.ROOT));
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
    assertThat(insertSql)
        .hasValueSatisfying(
            sql ->
                assertThat(sql)
                    .contains(
                        "on conflict",
                        "event_id",
                        "script_pin_epoch",
                        "script_pin_control_plane_request_id",
                        "do update",
                        "handoff_outcome")
                    .doesNotContain("uuid"));
    assertThat(conflictTarget(insertSql.get()))
        .contains("event_id")
        .doesNotContain("script_pin_epoch", "script_pin_control_plane_request_id");
  }

  @Test
  void preservesExistingEventWhenRetryCarriesDifferentPinOwnerTuple() {
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

    ScriptHandoffEvent saved = repository.save(retry);

    assertThat(saved.getScriptPatchVersion()).isEqualTo("patch-original");
    assertThat(saved.getScriptPinEpoch()).isEqualTo(2L);
    assertThat(saved.getScriptPinControlPlaneRequestId()).isEqualTo("owner-original");
    assertThat(insertSql.get()).contains("script_patch_version", "script_pin_epoch");
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
