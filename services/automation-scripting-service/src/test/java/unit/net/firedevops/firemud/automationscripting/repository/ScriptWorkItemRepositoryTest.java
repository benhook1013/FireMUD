package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptWorkItems.SCRIPT_WORK_ITEMS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

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
          Field<String> requestIdField =
              DSL.field("script_pin_control_plane_request_id", String.class);
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
    item.setScriptPinEpoch(7L);
    item.setScriptPinControlPlaneRequestId("pin-request-1");

    ScriptWorkItem saved = repository.insertIfAbsentByTriggerIdentity(item).workItem();

    assertThat(insertSql)
        .hasValueSatisfying(
            sql ->
                assertThat(conflictClause(sql))
                    .contains("script_pin_epoch", "script_pin_control_plane_request_id"));
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
          Field<String> requestIdField =
              DSL.field("script_pin_control_plane_request_id", String.class);
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
