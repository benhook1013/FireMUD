package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptHandoffEvents.SCRIPT_HANDOFF_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.entity.ScriptHandoffEvent;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptHandoffEventsRecord;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class ScriptHandoffEventRepositoryTest {
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
  void newLogicalChildUsesAtomicNaturalKeyConflictUpsert() {
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
    assertThat(renderedSql.substring(conflictIndex, updateIndex))
        .contains("tenant_id", "work_item_id", "command_ordinal");
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
        .hasMessageContaining("returned no durable row");
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
}
