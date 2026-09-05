package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptEventAudit.SCRIPT_EVENT_AUDIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
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
  void newAuditUsesEmptyPluginIdentitySentinels() {
    ScriptEventAudit audit = new ScriptEventAudit();

    assertThat(audit.getPluginId()).isEmpty();
    assertThat(audit.getPluginVersionId()).isEmpty();
  }

  @Test
  void exactOwnerEvidenceLookupIncludesControlPlaneRequestId() {
    AtomicReference<String> sql = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          sql.set(context.sql().toLowerCase(Locale.ROOT));
          return new MockResult[] {new MockResult(0)};
        };
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThat(
            repository
                .existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndPluginIdAndPluginVersionIdAndBindingIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptPinEpochAndScriptPinControlPlaneRequestIdAndScriptEventIdAndDryRun(
                    "tenant-1",
                    "game-1",
                    "region-1",
                    7L,
                    "entity-1",
                    "SHARED",
                    "world-1",
                    "realm-1",
                    "pointer-1",
                    "script-1",
                    "plugin-1",
                    "plugin-v1",
                    "binding-1",
                    "onCommand",
                    "v1",
                    "patch-1",
                    2L,
                    "pin-request-1",
                    "event-1",
                    false))
        .isFalse();

    int whereStart = sql.get().indexOf(" where ");
    assertThat(whereStart).isGreaterThanOrEqualTo(0);
    assertThat(sql.get().substring(whereStart))
        .contains("script_pin_epoch", "script_pin_control_plane_request_id", "script_event_id");
  }

  @Test
  void timerAuditLookupAllowsIndependentPinFilters() {
    List<String> queries = new ArrayList<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          queries.add(context.sql().toLowerCase(Locale.ROOT));
          return new MockResult[] {new MockResult(0, resultDsl.newResult(SCRIPT_EVENT_AUDIT))};
        };
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    repository.findTimerAuditEvents(
        "tenant-1",
        "game-1",
        "patch-1",
        2L,
        null,
        "script-1",
        "onTimerExpire",
        "",
        null,
        null,
        PageRequest.of(0, 25));
    repository.findTimerAuditEvents(
        "tenant-1",
        "game-1",
        "patch-1",
        null,
        "request-1",
        "script-1",
        "onTimerExpire",
        "",
        null,
        null,
        PageRequest.of(0, 25));

    assertThat(queries).hasSize(2);
    String epochOnlyWhere = queries.get(0).substring(queries.get(0).indexOf(" where "));
    String requestOnlyWhere = queries.get(1).substring(queries.get(1).indexOf(" where "));
    assertThat(epochOnlyWhere).contains("\"script_pin_epoch\" = ?");
    assertThat(epochOnlyWhere).doesNotContain("script_pin_control_plane_request_id");
    assertThat(requestOnlyWhere).doesNotContain("\"script_pin_epoch\" = ?");
    assertThat(requestOnlyWhere).contains("script_pin_control_plane_request_id");
  }

  @Test
  void timerAuditLookupRejectsNegativeEpochWithoutNormalizingToUnpinned() {
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(DSL.using(SQLDialect.POSTGRES));

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                repository.findTimerAuditEvents(
                    "tenant-1",
                    "game-1",
                    "patch-1",
                    -1L,
                    null,
                    "script-1",
                    "onTimerExpire",
                    "",
                    null,
                    null,
                    PageRequest.of(0, 25)))
        .withMessage("script_pin_epoch must be non-negative");
  }

  @Test
  void saveRejectsAnIncompletePinTupleBeforeUpdatingAnExistingAudit() {
    ScriptEventAudit entity = auditEntity(Instant.parse("2026-08-01T00:00:00Z"));
    entity.setId(7L);
    entity.setRowVersion(1);
    entity.setScriptPinEpoch(2L);
    entity.setScriptPinControlPlaneRequestId(null);
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(DSL.using(SQLDialect.POSTGRES));

    assertThatIllegalArgumentException()
        .isThrownBy(() -> repository.save(entity))
        .withMessage(
            "script_pin_control_plane_request_id is required exactly when script_pin_epoch is positive");
  }

  @Test
  void insertRejectsEpochWithoutOwnerRequestBeforeConflictSelection() {
    ScriptEventAudit entity = auditEntity(Instant.parse("2026-08-01T00:00:00Z"));
    entity.setScriptPinEpoch(2L);
    entity.setScriptPinControlPlaneRequestId(null);
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(DSL.using(SQLDialect.POSTGRES));

    assertThatIllegalArgumentException()
        .isThrownBy(() -> repository.insertIfAbsentByHandlerIdentity(entity))
        .withMessage(
            "script_pin_control_plane_request_id is required exactly when script_pin_epoch is positive");
  }

  @Test
  void insertRejectsOwnerRequestWithoutEpochBeforeConflictSelection() {
    ScriptEventAudit entity = auditEntity(Instant.parse("2026-08-01T00:00:00Z"));
    entity.setScriptPinEpoch(null);
    entity.setScriptPinControlPlaneRequestId("pin-request-1");
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(DSL.using(SQLDialect.POSTGRES));

    assertThatIllegalArgumentException()
        .isThrownBy(() -> repository.insertIfAbsentByHandlerIdentity(entity))
        .withMessage(
            "script_pin_control_plane_request_id is required exactly when script_pin_epoch is positive");
  }

  @Test
  void insertIfAbsentByHandlerIdentityMapsConflictReturningMarkerToExistingResult() {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    ScriptEventAuditRecord row = auditRecord(11L, now, now.plusSeconds(1));
    row.setScriptPinEpoch(null);
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          Field<Boolean> insertedField = DSL.field("xmax = 0", Boolean.class).as("inserted");
          Field<String> requestIdField = SCRIPT_EVENT_AUDIT.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID;
          List<Field<?>> fields = new ArrayList<>();
          Collections.addAll(fields, SCRIPT_EVENT_AUDIT.fields());
          fields.add(insertedField);
          Record returned = resultDsl.newRecord(fields.toArray(new Field<?>[0]));
          returned.from(row);
          returned.set(requestIdField, "");
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
    assertThat(result.audit().getScriptPinEpoch()).isNull();
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
          Field<String> requestIdField = SCRIPT_EVENT_AUDIT.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID;
          List<Field<?>> fields = new ArrayList<>();
          Collections.addAll(fields, SCRIPT_EVENT_AUDIT.fields());
          fields.add(insertedField);
          Result<Record> result = resultDsl.newResult(fields.toArray(new Field<?>[0]));
          Record returned = resultDsl.newRecord(fields.toArray(new Field<?>[0]));
          returned.from(row);
          returned.set(requestIdField, null);
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
    assertThat(result.audit().getScriptPatchVersion()).isEqualTo("patch-1");
    assertThat(result.audit().getScriptPinEpoch()).isNull();
    assertThat(result.audit().getScriptPinControlPlaneRequestId()).isNull();
    assertThat(sqlRef.get().toLowerCase(Locale.ROOT))
        .contains("on conflict", " do update", "returning", "xmax = 0");
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
            "plugin_id",
            "plugin_version_id",
            "binding_id",
            "event_type",
            "event_schema_version",
            "script_patch_version",
            "script_event_id",
            "dry_run");
    assertThat(conflictClause).doesNotContain("script_pin_control_plane_request_id");
    assertThat(conflictClause).contains("where", "script_pin_epoch", "is null");
  }

  @Test
  void insertIfAbsentByHandlerIdentityUsesPinnedPartialIndexPredicate() {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    ScriptEventAuditRecord row = auditRecord(8L, now, now);
    row.setScriptPinEpoch(2L);
    AtomicReference<String> sqlRef = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          Field<Boolean> insertedField = DSL.field("xmax = 0", Boolean.class).as("inserted");
          Field<String> requestIdField = SCRIPT_EVENT_AUDIT.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID;
          List<Field<?>> fields = new ArrayList<>();
          Collections.addAll(fields, SCRIPT_EVENT_AUDIT.fields());
          fields.add(insertedField);
          Result<Record> result = resultDsl.newResult(fields.toArray(new Field<?>[0]));
          Record returned = resultDsl.newRecord(fields.toArray(new Field<?>[0]));
          returned.from(row);
          returned.set(requestIdField, "pin-request-1");
          returned.set(insertedField, false);
          result.add(returned);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptEventAudit entity = auditEntity(now);
    entity.setScriptPinEpoch(2L);
    entity.setScriptPinControlPlaneRequestId("pin-request-2");
    assertThatThrownBy(() -> repository.insertIfAbsentByHandlerIdentity(entity))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("script_pin_control_plane_request_id conflicts with existing identity");
    String conflictClause = conflictClause(sqlRef.get());
    assertThat(conflictClause).contains("script_pin_epoch", "script_event_id", "dry_run");
    assertThat(conflictClause).doesNotContain("script_pin_control_plane_request_id");
    assertThat(conflictClause).contains("where", "script_pin_epoch", "> 0");
    assertThat(conflictClause).doesNotContain("is not null");
  }

  @Test
  void insertIfAbsentByHandlerIdentityNormalizesZeroEpochToTheUnpinnedPredicate() {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    ScriptEventAuditRecord row = auditRecord(8L, now, now);
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
          returned.set(insertedField, false);
          result.add(returned);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptEventAudit entity = auditEntity(now);
    entity.setScriptPinEpoch(0L);
    ScriptEventAuditRepository.IdempotentInsertResult result =
        repository.insertIfAbsentByHandlerIdentity(entity);

    assertThat(result.inserted()).isFalse();
    String conflictClause = conflictClause(sqlRef.get());
    assertThat(conflictClause).contains("where", "script_pin_epoch", "is null");
    assertThat(conflictClause).doesNotContain("script_pin_epoch,");
  }

  @Test
  void insertIfAbsentByHandlerIdentityReadsBackLegacyZeroEpochAsCanonicalNull() {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    ScriptEventAuditRecord row = auditRecord(9L, now, now.plusSeconds(1));
    row.setScriptPinEpoch(0L);
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

    ScriptEventAudit entity = auditEntity(now);
    entity.setPluginId(null);
    entity.setPluginVersionId(null);
    ScriptEventAuditRepository.IdempotentInsertResult result =
        repository.insertIfAbsentByHandlerIdentity(entity);

    assertThat(result.inserted()).isFalse();
    assertThat(result.audit().getId()).isEqualTo(9L);
    assertThat(result.audit().getTenantId()).isEqualTo("tenant-1");
    assertThat(result.audit().getScriptEventId()).isEqualTo("event-1");
    assertThat(result.audit().getScriptPatchVersion()).isEqualTo("patch-1");
    assertThat(result.audit().getScriptPinEpoch()).isNull();
    assertThat(result.audit().getScriptPinControlPlaneRequestId()).isNull();
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
            "plugin_id",
            "plugin_version_id",
            "binding_id",
            "event_type",
            "event_schema_version",
            "script_patch_version",
            "script_pin_epoch",
            "script_event_id",
            "dry_run");
    assertThat(whereClause(selectSqlRef.get()))
        .contains("\"plugin_id\" = ?", "\"plugin_version_id\" = ?");
    assertThat(whereClause(selectSqlRef.get()))
        .doesNotContain("script_pin_control_plane_request_id");
  }

  @Test
  void insertIfAbsentByHandlerIdentityRejectsConflictingOwnerEvidenceFromFallbackLookup() {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    ScriptEventAuditRecord row = auditRecord(10L, now, now.plusSeconds(1));
    row.setScriptPinEpoch(2L);
    row.setScriptPinControlPlaneRequestId("pin-request-1");
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<Integer> calls = new AtomicReference<>(0);
    MockDataProvider provider =
        context -> {
          calls.updateAndGet(value -> value + 1);
          if (calls.get() == 1) {
            return new MockResult[] {new MockResult(0, resultDsl.newResult(SCRIPT_EVENT_AUDIT))};
          }
          Result<ScriptEventAuditRecord> result = resultDsl.newResult(SCRIPT_EVENT_AUDIT);
          result.add(row);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));
    ScriptEventAudit entity = auditEntity(now);
    entity.setScriptPinEpoch(2L);
    entity.setScriptPinControlPlaneRequestId("pin-request-2");

    assertThatThrownBy(() -> repository.insertIfAbsentByHandlerIdentity(entity))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("script_pin_control_plane_request_id conflicts with existing identity");
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void existingAuditUpdateCasIncludesNormalizedPinTuple() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicInteger calls = new AtomicInteger();
    MockDataProvider provider =
        context -> {
          if (calls.incrementAndGet() == 1) {
            sqlRef.set(context.sql().toLowerCase(Locale.ROOT));
            bindingsRef.set(context.bindings());
          }
          return new MockResult[] {new MockResult(0, resultDsl.newResult(SCRIPT_EVENT_AUDIT))};
        };
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptEventAudit entity = auditEntity(Instant.parse("2026-08-01T00:00:00Z"));
    entity.setId(9L);
    entity.setRowVersion(3);
    entity.setScriptPinEpoch(2L);
    entity.setScriptPinControlPlaneRequestId("pin-request-2");
    entity.setScriptPatchVersion("patch-2");

    assertThatThrownBy(() -> repository.save(entity))
        .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class)
        .hasMessage("Stale write rejected for script_event_audit id=9");
    assertThat(sqlRef.get())
        .contains(
            "script_patch_version", "script_pin_epoch", "script_pin_control_plane_request_id");
    assertThat(bindingsRef.get()).contains("patch-2", 2L, "pin-request-2");
  }

  @Test
  void existingAuditUpdateReturnsTheUpdatedRowWhenCasSucceeds() {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    ScriptEventAuditRecord row = auditRecord(9L, now, now);
    row.setScriptPatchVersion("patch-2");
    row.setScriptPinEpoch(2L);
    row.setScriptPinControlPlaneRequestId("pin-request-2");
    row.setRowVersion(4);
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicInteger calls = new AtomicInteger();
    MockDataProvider provider =
        context -> {
          if (calls.incrementAndGet() == 1) {
            return new MockResult[] {new MockResult(1)};
          }
          Result<ScriptEventAuditRecord> result = resultDsl.newResult(SCRIPT_EVENT_AUDIT);
          result.add(row);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptEventAudit entity = auditEntity(now);
    entity.setId(9L);
    entity.setRowVersion(3);
    entity.setScriptPatchVersion("patch-2");
    entity.setScriptPinEpoch(2L);
    entity.setScriptPinControlPlaneRequestId("pin-request-2");

    ScriptEventAudit saved = repository.save(entity);

    assertThat(saved.getId()).isEqualTo(9L);
    assertThat(saved.getRowVersion()).isEqualTo(4);
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void existingAuditUpdateClassifiesChangedOwnerEvidenceAsConflict() {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    ScriptEventAuditRecord row = auditRecord(9L, now, now);
    row.setScriptPatchVersion("patch-2");
    row.setScriptPinEpoch(2L);
    row.setScriptPinControlPlaneRequestId("pin-request-1");
    row.setRowVersion(3);
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicInteger calls = new AtomicInteger();
    MockDataProvider provider =
        context -> {
          if (calls.incrementAndGet() == 1) {
            return new MockResult[] {new MockResult(0)};
          }
          Result<ScriptEventAuditRecord> result = resultDsl.newResult(SCRIPT_EVENT_AUDIT);
          result.add(row);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptEventAudit entity = auditEntity(now);
    entity.setId(9L);
    entity.setRowVersion(3);
    entity.setScriptPatchVersion("patch-2");
    entity.setScriptPinEpoch(2L);
    entity.setScriptPinControlPlaneRequestId("pin-request-2");

    assertThatThrownBy(() -> repository.save(entity))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("script_pin_control_plane_request_id conflicts with existing identity");
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void existingAuditUpdateKeepsStaleClassificationForChangedIdentity() {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    ScriptEventAuditRecord row = auditRecord(9L, now, now);
    row.setScriptPatchVersion("patch-1");
    row.setScriptPinEpoch(2L);
    row.setScriptPinControlPlaneRequestId("pin-request-2");
    row.setRowVersion(3);
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicInteger calls = new AtomicInteger();
    MockDataProvider provider =
        context -> {
          if (calls.incrementAndGet() == 1) {
            return new MockResult[] {new MockResult(0)};
          }
          Result<ScriptEventAuditRecord> result = resultDsl.newResult(SCRIPT_EVENT_AUDIT);
          result.add(row);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptEventAuditRepository repository =
        new ScriptEventAuditRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptEventAudit entity = auditEntity(now);
    entity.setId(9L);
    entity.setRowVersion(3);
    entity.setScriptPatchVersion("patch-2");
    entity.setScriptPinEpoch(2L);
    entity.setScriptPinControlPlaneRequestId("pin-request-2");

    assertThatThrownBy(() -> repository.save(entity))
        .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class)
        .hasMessage("Stale write rejected for script_event_audit id=9");
    assertThat(calls.get()).isEqualTo(2);
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
    audit.setPluginId("plugin-1");
    audit.setPluginVersionId("plugin-v1");
    audit.setBindingId("binding-1");
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
