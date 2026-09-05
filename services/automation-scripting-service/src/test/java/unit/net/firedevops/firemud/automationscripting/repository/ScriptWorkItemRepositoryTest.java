package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptWorkItems.SCRIPT_WORK_ITEMS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptWorkItemsRecord;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class ScriptWorkItemRepositoryTest {
  @Test
  void insertRejectsIncompleteScriptPinTuple() {
    ScriptWorkItemRepository repository =
        new ScriptWorkItemRepository(DSL.using(SQLDialect.POSTGRES));
    ScriptWorkItem item = new ScriptWorkItem();
    item.setScriptPinEpoch(2L);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> repository.insertIfAbsentByTriggerIdentity(item))
        .withMessage(
            "script_pin_control_plane_request_id is required for a positive script_pin_epoch");
  }

  @Test
  void insertRejectsOwnerRequestIdOnUnpinnedTuple() {
    ScriptWorkItemRepository repository =
        new ScriptWorkItemRepository(DSL.using(SQLDialect.POSTGRES));
    ScriptWorkItem item = new ScriptWorkItem();
    item.setScriptPinControlPlaneRequestId("pin-request-1");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> repository.insertIfAbsentByTriggerIdentity(item))
        .withMessage("script_pin_control_plane_request_id requires a positive script_pin_epoch");
  }

  @Test
  void insertAndHydratePositiveScriptPinEpoch() {
    ScriptWorkItemsRecord row = workItemRecord(9L, 4, 7L);
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<String> insertSql = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          insertSql.set(context.sql().toLowerCase(Locale.ROOT));
          Field<Boolean> insertedField = DSL.field("xmax = 0", Boolean.class).as("inserted");
          Field<String> requestIdField = SCRIPT_WORK_ITEMS.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID;
          List<Field<?>> fields = new ArrayList<>();
          Collections.addAll(fields, SCRIPT_WORK_ITEMS.fields());
          fields.add(requestIdField);
          fields.add(insertedField);
          Record returned = resultDsl.newRecord(fields.toArray(new Field<?>[0]));
          returned.from(row);
          returned.set(requestIdField, "pin-request-1");
          returned.set(insertedField, true);
          Result<Record> result = resultDsl.newResult(fields.toArray(new Field<?>[0]));
          result.add(returned);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptWorkItemRepository repository =
        new ScriptWorkItemRepository(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptWorkItem item = new ScriptWorkItem();
    item.setTenantId("tenant-1");
    item.setGameInstanceId("game-1");
    item.setScriptId("script-1");
    item.setPluginId("plugin-1");
    item.setPluginVersionId("plugin-v1");
    item.setBindingId("binding-1");
    item.setScriptPinEpoch(7L);
    item.setScriptPinControlPlaneRequestId("pin-request-1");

    ScriptWorkItem saved = repository.insertIfAbsentByTriggerIdentity(item).workItem();

    assertThat(insertSql)
        .hasValueSatisfying(
            sql ->
                assertThat(conflictClause(sql))
                    .contains(
                        "where",
                        "script_pin_epoch",
                        "> 0",
                        "plugin_id",
                        "plugin_version_id",
                        "binding_id",
                        "script_pin_control_plane_request_id"));
    assertThat(saved.getScriptPinEpoch()).isEqualTo(7L);
    assertThat(saved.getScriptPinControlPlaneRequestId()).isEqualTo("pin-request-1");
    assertThat(saved.getId()).isEqualTo(9L);
  }

  @Test
  void insertAndHydrateUnpinnedEpochUsesEpochIdentityWithoutRequestId() {
    ScriptWorkItemsRecord row = workItemRecord(10L, 4, 0L);
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<String> insertSql = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          insertSql.set(context.sql().toLowerCase(Locale.ROOT));
          Field<Boolean> insertedField = DSL.field("xmax = 0", Boolean.class).as("inserted");
          Field<String> requestIdField = SCRIPT_WORK_ITEMS.SCRIPT_PIN_CONTROL_PLANE_REQUEST_ID;
          List<Field<?>> fields = new ArrayList<>();
          Collections.addAll(fields, SCRIPT_WORK_ITEMS.fields());
          fields.add(requestIdField);
          fields.add(insertedField);
          Record returned = resultDsl.newRecord(fields.toArray(new Field<?>[0]));
          returned.from(row);
          returned.set(requestIdField, (String) null);
          returned.set(insertedField, true);
          Result<Record> result = resultDsl.newResult(fields.toArray(new Field<?>[0]));
          result.add(returned);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptWorkItemRepository repository =
        new ScriptWorkItemRepository(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptWorkItem item = new ScriptWorkItem();
    item.setTenantId("tenant-1");
    item.setGameInstanceId("game-1");
    item.setScriptPinEpoch(0L);

    ScriptWorkItem saved = repository.insertIfAbsentByTriggerIdentity(item).workItem();

    assertThat(saved.getScriptPinEpoch()).isZero();
    assertThat(conflictClause(insertSql.get()))
        .contains("where", "script_pin_epoch", "= 0")
        .doesNotContain("script_pin_control_plane_request_id");
    assertThat(saved.getScriptPinControlPlaneRequestId()).isNull();
  }

  @Test
  void triggerIdentityNormalizesNullBindingIdForWriteAndLookupPredicate() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<Object[]> insertBindings = new AtomicReference<>();
    AtomicReference<Object[]> selectBindings = new AtomicReference<>();
    AtomicReference<String> selectSql = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          String sql = context.sql().toLowerCase(Locale.ROOT);
          if (sql.startsWith("insert")) {
            insertBindings.set(context.bindings());
            return new MockResult[] {
              new MockResult(0, resultDsl.newResult(SCRIPT_WORK_ITEMS.fields()))
            };
          }
          selectSql.set(sql);
          selectBindings.set(context.bindings());
          ScriptWorkItemsRecord row = workItemRecord(11L, 0, 0L);
          row.setBindingId("");
          Result<ScriptWorkItemsRecord> result = resultDsl.newResult(SCRIPT_WORK_ITEMS);
          result.add(row);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptWorkItemRepository repository =
        new ScriptWorkItemRepository(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptWorkItem item = new ScriptWorkItem();
    item.setTenantId("tenant-1");
    item.setGameInstanceId("game-1");
    item.setRegionId("region-1");
    item.setRegionEpoch(1L);
    item.setEntityId("entity-1");
    item.setPlayableStateScope("scope-1");
    item.setWorldSlug("world-1");
    item.setRealmSlug("realm-1");
    item.setPointerVersion("pointer-1");
    item.setScriptId("script-1");
    item.setPluginId("plugin-1");
    item.setPluginVersionId("version-1");
    item.setBindingId(null);
    item.setEventType("event-1");
    item.setEventSchemaVersion("v1");
    item.setScriptPatchVersion("patch-1");
    item.setScriptEventId("event-id-1");

    ScriptWorkItem found = repository.insertIfAbsentByTriggerIdentity(item).workItem();

    assertThat(found.getId()).isEqualTo(11L);
    assertThat(insertBindings).hasValueSatisfying(values -> assertThat(values).contains(""));
    assertThat(selectSql)
        .hasValueSatisfying(sql -> assertThat(sql).contains("binding_id", "where"));
    assertThat(selectBindings).hasValueSatisfying(values -> assertThat(values).contains(""));
  }

  @Test
  void scriptOnlyPluginLookupNormalizesNullIdentityToCanonicalEmptyValues() {
    ScriptWorkItemsRecord row = workItemRecord(11L, 0, 0L);
    row.setPluginId("");
    row.setPluginVersionId("");
    row.setStatus("PENDING_EVALUATION");
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<Object[]> bindings = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          bindings.set(context.bindings());
          Result<ScriptWorkItemsRecord> result = resultDsl.newResult(SCRIPT_WORK_ITEMS);
          result.add(row);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptWorkItemRepository repository =
        new ScriptWorkItemRepository(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThat(
            repository
                .findByTenantIdAndPluginIdAndPluginVersionIdAndStatusInOrderByCreatedAtAscIdAsc(
                    "tenant-1", null, null, List.of("PENDING_EVALUATION")))
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.getPluginId()).isEmpty();
              assertThat(item.getPluginVersionId()).isEmpty();
            });
    assertThat(bindings.get()).contains("tenant-1", "", "");
  }

  @Test
  void deletingWorkItemsDisposesChildEvidenceBeforeParentRows() {
    List<String> sqlStatements = new ArrayList<>();
    MockDataProvider provider =
        context -> {
          sqlStatements.add(context.sql().toLowerCase(Locale.ROOT));
          return new MockResult[] {new MockResult(1, null)};
        };
    ScriptWorkItemRepository repository =
        new ScriptWorkItemRepository(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));
    ScriptWorkItem item = new ScriptWorkItem();
    item.setId(99L);

    repository.deleteAll(List.of(item));

    assertThat(sqlStatements).hasSize(3);
    assertThat(sqlStatements.get(0))
        .startsWith("update")
        .contains("script_event_audit")
        .contains("work_item_id");
    assertThat(sqlStatements.get(1)).contains("script_handoff_events");
    assertThat(sqlStatements.get(2)).contains("script_work_items");
  }

  @Test
  void statusRetentionDeletesChildrenBeforeMatchingParents() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    List<String> sqlStatements = new ArrayList<>();
    MockDataProvider provider =
        context -> {
          String sql = context.sql().toLowerCase(Locale.ROOT);
          sqlStatements.add(sql);
          if (sql.startsWith("select")) {
            Record1<Long> returned = resultDsl.newRecord(SCRIPT_WORK_ITEMS.ID);
            returned.set(SCRIPT_WORK_ITEMS.ID, 99L);
            Result<Record1<Long>> result = resultDsl.newResult(SCRIPT_WORK_ITEMS.ID);
            result.add(returned);
            return new MockResult[] {new MockResult(1, result)};
          }
          return new MockResult[] {new MockResult(1, null)};
        };
    ScriptWorkItemRepository repository =
        new ScriptWorkItemRepository(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThat(repository.deleteByStatusAndUpdatedAtBefore("HANDED_OFF", Instant.EPOCH))
        .isEqualTo(1);

    assertThat(sqlStatements).hasSize(4);
    assertThat(sqlStatements.get(0)).startsWith("select").contains("for update");
    assertThat(sqlStatements.get(1))
        .startsWith("update")
        .contains("script_event_audit")
        .contains("work_item_id");
    assertThat(sqlStatements.get(2)).contains("script_handoff_events");
    assertThat(sqlStatements.get(3))
        .contains("script_work_items")
        .contains("status")
        .contains("updated_at");
  }

  @Test
  void excessStatusRetentionLocksOldestRowsAndRechecksStatus() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    List<String> sqlStatements = new ArrayList<>();
    MockDataProvider provider =
        context -> {
          String sql = context.sql().toLowerCase(Locale.ROOT);
          sqlStatements.add(sql);
          if (sql.startsWith("select")) {
            Record1<Long> returned = resultDsl.newRecord(SCRIPT_WORK_ITEMS.ID);
            returned.set(SCRIPT_WORK_ITEMS.ID, 99L);
            Result<Record1<Long>> result = resultDsl.newResult(SCRIPT_WORK_ITEMS.ID);
            result.add(returned);
            return new MockResult[] {new MockResult(1, result)};
          }
          return new MockResult[] {new MockResult(1, null)};
        };
    ScriptWorkItemRepository repository =
        new ScriptWorkItemRepository(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThat(repository.deleteOldestByStatus("DEAD_LETTERED", 1)).isEqualTo(1);

    assertThat(sqlStatements).hasSize(4);
    assertThat(sqlStatements.get(0))
        .startsWith("select")
        .contains("status")
        .contains("order by")
        .contains("fetch next")
        .contains("for update");
    assertThat(sqlStatements.get(1))
        .startsWith("update")
        .contains("script_event_audit")
        .contains("work_item_id");
    assertThat(sqlStatements.get(2)).contains("script_handoff_events");
    assertThat(sqlStatements.get(3)).contains("script_work_items").contains("status");
  }

  @Test
  void deadLetterListingAppliesOptionalFiltersBeforeLimit() {
    AtomicReference<String> sql = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sql.set(context.sql().toLowerCase(Locale.ROOT));
          return new MockResult[] {
            new MockResult(0, resultDsl.newResult(SCRIPT_WORK_ITEMS.fields()))
          };
        };
    ScriptWorkItemRepository repository =
        new ScriptWorkItemRepository(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    repository.findDeadLettersByTenantIdAndFiltersOrderByUpdatedAtDescIdDesc(
        "tenant-1", "game-1", "patch-1", "DEAD_LETTERED", PageRequest.of(0, 25));

    assertThat(sql)
        .hasValueSatisfying(
            statement -> {
              assertThat(statement)
                  .contains(
                      "where",
                      "tenant_id",
                      "game_instance_id",
                      "script_patch_version",
                      "status",
                      "fetch next");
              int whereIndex = statement.indexOf(" where ");
              int fetchIndex = statement.indexOf("fetch next");
              assertThat(statement.indexOf("game_instance_id", whereIndex))
                  .isGreaterThan(whereIndex)
                  .isLessThan(fetchIndex);
              assertThat(statement.indexOf("script_patch_version", whereIndex))
                  .isGreaterThan(whereIndex)
                  .isLessThan(fetchIndex);
              assertThat(statement.indexOf("status", whereIndex))
                  .isGreaterThan(whereIndex)
                  .isLessThan(fetchIndex);
            });
  }

  private static ScriptWorkItemsRecord workItemRecord(long id, int rowVersion, long pinEpoch) {
    ScriptWorkItemsRecord record = new ScriptWorkItemsRecord();
    record.setId(id);
    record.setTenantId("tenant-1");
    record.setGameInstanceId("game-1");
    record.setScriptPinEpoch(pinEpoch);
    record.setCreatedAt(LocalDateTime.parse("2026-08-01T00:00:00"));
    record.setUpdatedAt(LocalDateTime.parse("2026-08-01T00:00:01"));
    record.setRowVersion(rowVersion);
    return record;
  }

  private static String conflictClause(String sql) {
    String normalized = sql.toLowerCase(Locale.ROOT);
    int conflictStart = normalized.indexOf("on conflict");
    assertThat(conflictStart).isGreaterThanOrEqualTo(0);
    int actionStart = normalized.indexOf(" do update", conflictStart);
    assertThat(actionStart).isGreaterThan(conflictStart);
    return normalized.substring(conflictStart, actionStart);
  }
}
