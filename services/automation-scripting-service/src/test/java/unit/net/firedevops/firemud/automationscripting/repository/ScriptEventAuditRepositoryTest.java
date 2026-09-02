package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventAudit.SCRIPT_EVENT_AUDIT;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptEventAuditRecord;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class ScriptEventAuditRepositoryTest {
  @Test
  void insertIfAbsentByHandlerIdentityMapsConflictReturningMarkerToExistingResult() {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    ScriptEventAuditRecord row = auditRecord(11L, now, now.plusSeconds(1));
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          Field<Boolean> insertedField = DSL.field("xmax = 0", Boolean.class).as("inserted");
          List<Field<?>> fields = new ArrayList<>();
          Collections.addAll(fields, SCRIPT_EVENT_AUDIT.fields());
          fields.add(insertedField);
          Record returned = resultDsl.newRecord(fields.toArray(new Field<?>[0]));
          returned.from(row);
          returned.set(insertedField, false);
          Result<Record> result = resultDsl.newResult(fields.toArray(new Field<?>[0]));
          result.add(returned);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptEventAuditRepository.IdempotentInsertResult result =
        repository.insertIfAbsentByHandlerIdentity(auditEntity(now));

    assertThat(result.inserted()).isFalse();
    assertThat(result.audit().getId()).isEqualTo(11L);
  }

  @Test
  void insertIfAbsentByHandlerIdentityReturnsInsertedRowAndUsesFullIdentityConflictTarget() {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    ScriptEventAuditRecord row = auditRecord(7L, now, now);
    AtomicReference<String> sqlRef = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          Field<Boolean> insertedField = DSL.field("xmax = 0", Boolean.class).as("inserted");
          List<Field<?>> fields = new ArrayList<>();
          Collections.addAll(fields, SCRIPT_EVENT_AUDIT.fields());
          fields.add(insertedField);
          Result<Record> result = resultDsl.newResult(fields.toArray(new Field<?>[0]));
          Record returned = resultDsl.newRecord(fields.toArray(new Field<?>[0]));
          returned.from(row);
          returned.set(insertedField, true);
          result.add(returned);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptEventAuditRepository.IdempotentInsertResult result =
        repository.insertIfAbsentByHandlerIdentity(auditEntity(now));

    assertThat(result.inserted()).isTrue();
    assertThat(result.audit().getId()).isEqualTo(7L);
    assertThat(result.audit().getScriptEventId()).isEqualTo("event-1");
    assertThat(sqlRef.get().toLowerCase(Locale.ROOT))
        .contains("on conflict", " do update", "returning");
    String conflictClause = conflictClause(sqlRef.get());
    assertThat(conflictClause)
        .contains(
            "tenant_id",
            "game_instance_id",
            "region_id",
            "region_epoch",
            "entity_id",
            "playable_state_scope",
            "world_slug",
            "realm_slug",
            "pointer_version",
            "script_id",
            "event_type",
            "event_schema_version",
            "script_patch_version",
            "script_event_id",
            "dry_run");
  }

  @Test
  void insertIfAbsentByHandlerIdentityReadsBackExistingConflictWithoutChangingIdentity() {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    ScriptEventAuditRecord row = auditRecord(9L, now, now.plusSeconds(1));
    row.setFinalReason("original_reason");
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<Integer> calls = new AtomicReference<>(0);
    AtomicReference<String> insertSqlRef = new AtomicReference<>();
    AtomicReference<String> selectSqlRef = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          calls.updateAndGet(value -> value + 1);
          if (calls.get() == 1) {
            insertSqlRef.set(context.sql().toLowerCase(Locale.ROOT));
            return new MockResult[] {new MockResult(0, resultDsl.newResult(SCRIPT_EVENT_AUDIT))};
          }
          selectSqlRef.set(context.sql().toLowerCase(Locale.ROOT));
          Result<ScriptEventAuditRecord> result = resultDsl.newResult(SCRIPT_EVENT_AUDIT);
          result.add(row);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptEventAuditRepository.IdempotentInsertResult result =
        repository.insertIfAbsentByHandlerIdentity(auditEntity(now));

    assertThat(result.inserted()).isFalse();
    assertThat(result.audit().getId()).isEqualTo(9L);
    assertThat(result.audit().getTenantId()).isEqualTo("tenant-1");
    assertThat(result.audit().getScriptEventId()).isEqualTo("event-1");
    assertThat(result.audit().getFinalReason()).isEqualTo("original_reason");
    assertThat(result.audit().getCreatedAt()).isEqualTo(now);
    assertThat(result.audit().getUpdatedAt()).isEqualTo(now.plusSeconds(1));
    assertThat(calls.get()).isEqualTo(2);
    assertThat(insertSqlRef.get()).contains("on conflict", "do update");
    assertThat(selectSqlRef.get()).contains(" where ");
    assertThat(whereClause(selectSqlRef.get()))
        .contains(
            "tenant_id",
            "game_instance_id",
            "region_id",
            "region_epoch",
            "entity_id",
            "playable_state_scope",
            "world_slug",
            "realm_slug",
            "pointer_version",
            "script_id",
            "event_type",
            "event_schema_version",
            "script_patch_version",
            "script_event_id",
            "dry_run");
  }

  @Test
  void changedAfterIncludesOlderCreatedMutableSnapshotChangedAfterCursor() {
    Instant createdAt = Instant.parse("2026-08-01T00:00:00Z");
    Instant updatedAt = Instant.parse("2026-08-01T00:10:00Z");
    ScriptEventAuditRecord row = auditRecord(7L, createdAt, updatedAt);
    AtomicReference<String> sqlRef = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          Result<ScriptEventAuditRecord> result = resultDsl.newResult(SCRIPT_EVENT_AUDIT);
          result.add(row);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThat(
            repository.findTimerAuditEvents(
                "tenant-1",
                "game-1",
                "patch-1",
                "script-1",
                "onTimerExpire",
                "",
                Instant.parse("2026-08-01T00:05:00Z"),
                null,
                PageRequest.of(0, 25)))
        .singleElement()
        .satisfies(
            audit -> {
              assertThat(audit.getId()).isEqualTo(7L);
              assertThat(audit.getCreatedAt()).isEqualTo(createdAt);
              assertThat(audit.getUpdatedAt()).isEqualTo(updatedAt);
            });

    String whereClause = whereClause(sqlRef.get());
    assertThat(whereClause).contains("updated_at").doesNotContain("created_at");
    assertThat(whereClause).contains("source_kind", "tenant_id", "game_instance_id");
  }

  @Test
  void timerAuditUsesUpdatedAtForBoundsStableTieBreakAndPagination() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          return new MockResult[] {new MockResult(0, resultDsl.newResult(SCRIPT_EVENT_AUDIT))};
        };
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    repository.findTimerAuditEvents(
        "tenant-1",
        "game-1",
        "patch-1",
        "script-1",
        "onTimerExpire",
        "catch_up_truncated",
        Instant.parse("2026-08-01T00:05:00Z"),
        Instant.parse("2026-08-01T00:20:00Z"),
        PageRequest.of(2, 25));

    String sql = sqlRef.get().toLowerCase(Locale.ROOT);
    String whereClause = whereClause(sql);
    assertThat(whereClause)
        .contains(
            "tenant_id",
            "source_kind",
            "game_instance_id",
            "script_patch_version",
            "script_id",
            "event_type",
            "final_reason",
            "updated_at")
        .doesNotContain("created_at");
    assertThat(sql)
        .contains("order by \"script_event_audit\".\"updated_at\" desc")
        .contains("\"script_event_audit\".\"id\" desc");
    assertThat(sql).contains("offset ? rows fetch next ? rows only");
  }

  private static ScriptEventAuditRecord auditRecord(long id, Instant createdAt, Instant updatedAt) {
    ScriptEventAuditRecord record = new ScriptEventAuditRecord();
    record.setId(id);
    record.setTenantId("tenant-1");
    record.setGameInstanceId("game-1");
    record.setRegionId("region-1");
    record.setRegionEpoch(3L);
    record.setEntityId("entity-1");
    record.setPlayableStateScope("SHARED");
    record.setWorldSlug("world-1");
    record.setRealmSlug("realm-1");
    record.setPointerVersion("1");
    record.setScriptId("script-1");
    record.setEventType("onTimerExpire");
    record.setEventSchemaVersion("v1");
    record.setScriptPatchVersion("patch-1");
    record.setScriptEventId("event-1");
    record.setDryRun(false);
    record.setSourceService("automation-scripting-service");
    record.setTriggerMode("TRIGGER_MODE_CATCH_UP");
    record.setSourceKind("SCHEDULE_TIMER");
    record.setSourceState("SCHEDULE_DROPPED");
    record.setFinalStage("ADMISSION");
    record.setFinalOutcome("canceled");
    record.setFinalReason("catch_up_truncated");
    record.setCreatedAt(LocalDateTime.ofInstant(createdAt, java.time.ZoneOffset.UTC));
    record.setUpdatedAt(LocalDateTime.ofInstant(updatedAt, java.time.ZoneOffset.UTC));
    record.setRowVersion(1);
    return record;
  }

  private static ScriptEventAudit auditEntity(Instant now) {
    ScriptEventAudit audit = new ScriptEventAudit();
    audit.setTenantId("tenant-1");
    audit.setGameInstanceId("game-1");
    audit.setRegionId("region-1");
    audit.setRegionEpoch(3L);
    audit.setEntityId("entity-1");
    audit.setPlayableStateScope("SHARED");
    audit.setWorldSlug("world-1");
    audit.setRealmSlug("realm-1");
    audit.setPointerVersion("1");
    audit.setScriptId("script-1");
    audit.setEventType("onTimerExpire");
    audit.setEventSchemaVersion("v1");
    audit.setScriptPatchVersion("patch-1");
    audit.setScriptEventId("event-1");
    audit.setSourceService("automation-scripting-service");
    audit.setTriggerMode("TRIGGER_MODE_CATCH_UP");
    audit.setSourceKind("SCHEDULE_TIMER");
    audit.setSourceState("SCHEDULE_DROPPED");
    audit.setFinalStage("ADMISSION");
    audit.setFinalOutcome("canceled");
    audit.setFinalReason("catch_up_truncated");
    audit.setCreatedAt(now);
    audit.setUpdatedAt(now);
    return audit;
  }

  private static String whereClause(String sql) {
    String normalized = sql.toLowerCase(Locale.ROOT);
    int whereStart = normalized.indexOf(" where ");
    assertThat(whereStart).as("SQL must contain a WHERE marker").isGreaterThanOrEqualTo(0);
    int orderStart = normalized.indexOf(" order by ");
    return normalized.substring(whereStart, orderStart < 0 ? normalized.length() : orderStart);
  }

  private static String conflictClause(String sql) {
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
