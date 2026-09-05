package net.firedevops.firemud.automationscripting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.jooq.tables.ScriptWorkItems;
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
import org.springframework.data.domain.PageRequest;

class ScriptWorkItemRepositoryTest {
  @Test
  void distinctInstancePatchPairsAllowNullOptionalPatchVersion() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql().toLowerCase(Locale.ROOT));
          Field<?>[] fields = {
            ScriptWorkItems.SCRIPT_WORK_ITEMS.GAME_INSTANCE_ID,
            ScriptWorkItems.SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION
          };
          Record row = resultDsl.newRecord(fields);
          row.set(ScriptWorkItems.SCRIPT_WORK_ITEMS.GAME_INSTANCE_ID, "game-1");
          row.set(ScriptWorkItems.SCRIPT_WORK_ITEMS.SCRIPT_PATCH_VERSION, "patch-1");
          Result<Record> result = resultDsl.newResult(fields);
          result.add(row);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptWorkItemRepository repository =
        new ScriptWorkItemRepository(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThat(repository.findDistinctInstancePatchPairs("tenant-1", "game-1", null))
        .singleElement()
        .satisfies(
            pair -> {
              assertThat(pair.getGameInstanceId()).isEqualTo("game-1");
              assertThat(pair.getScriptPatchVersion()).isEqualTo("patch-1");
            });
    int whereIndex = sqlRef.get().indexOf("where");
    assertThat(whereIndex).isGreaterThanOrEqualTo(0);
    String whereClause = sqlRef.get().substring(whereIndex);
    assertThat(whereClause)
        .containsPattern("game_instance_id\\\"\\s*=\\s*\\?")
        .doesNotContain("script_patch_version =");
  }

  @Test
  void updatesScriptPinEpochAlongsideOtherExecutionFences() {
    AtomicReference<String> updateSql = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    ScriptWorkItemsRecord returnedRow = resultDsl.newRecord(ScriptWorkItems.SCRIPT_WORK_ITEMS);
    returnedRow.setId(17L);
    returnedRow.setTenantId("tenant-1");
    returnedRow.setGameInstanceId("game-1");
    returnedRow.setRegionId("region-1");
    returnedRow.setRegionEpoch(3L);
    returnedRow.setEntityId("entity-1");
    returnedRow.setScriptId("script-1");
    returnedRow.setEventType("onCommand");
    returnedRow.setEventSchemaVersion("v1");
    returnedRow.setScriptPatchVersion("patch-1");
    returnedRow.setScriptPinEpoch(9L);
    returnedRow.setScriptEventId("event-1");
    returnedRow.set(
        ScriptWorkItems.SCRIPT_WORK_ITEMS.AUTHORITY_UNAVAILABLE_SINCE,
        java.time.LocalDateTime.ofInstant(Instant.EPOCH, java.time.ZoneOffset.UTC));
    returnedRow.set(ScriptWorkItems.SCRIPT_WORK_ITEMS.AUTHORITY_UNAVAILABLE_COUNT, 4);
    returnedRow.set(
        ScriptWorkItems.SCRIPT_WORK_ITEMS.NEXT_ELIGIBLE_AT,
        java.time.LocalDateTime.ofInstant(Instant.EPOCH.plusSeconds(30), java.time.ZoneOffset.UTC));
    returnedRow.setCreatedAt(
        java.time.LocalDateTime.ofInstant(Instant.EPOCH, java.time.ZoneOffset.UTC));
    returnedRow.setUpdatedAt(
        java.time.LocalDateTime.ofInstant(Instant.EPOCH, java.time.ZoneOffset.UTC));
    returnedRow.setRowVersion(1);
    MockDataProvider provider =
        context -> {
          String sql = context.sql().toLowerCase(Locale.ROOT);
          if (sql.startsWith("update")) {
            updateSql.set(sql);
            return new MockResult[] {new MockResult(1)};
          }
          Result<ScriptWorkItemsRecord> result =
              resultDsl.newResult(ScriptWorkItems.SCRIPT_WORK_ITEMS);
          result.add(returnedRow);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptWorkItemRepository repository =
        new ScriptWorkItemRepository(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));
    ScriptWorkItem item = new ScriptWorkItem();
    item.setId(17L);
    item.setTenantId("tenant-1");
    item.setGameInstanceId("game-1");
    item.setRegionId("region-1");
    item.setRegionEpoch(3L);
    item.setEntityId("entity-1");
    item.setScriptId("script-1");
    item.setEventType("onCommand");
    item.setEventSchemaVersion("v1");
    item.setScriptPatchVersion("patch-1");
    item.setScriptPinEpoch(9L);
    item.setScriptEventId("event-1");
    item.setAuthorityUnavailableSince(Instant.EPOCH);
    item.setAuthorityUnavailableCount(4);
    item.setNextEligibleAt(Instant.EPOCH.plusSeconds(30));
    item.setCreatedAt(Instant.EPOCH);
    item.setUpdatedAt(Instant.EPOCH);

    ScriptWorkItem saved = repository.save(item);

    assertThat(saved.getId()).isEqualTo(17L);
    assertThat(saved.getScriptPinEpoch()).isEqualTo(9L);
    assertThat(saved.getAuthorityUnavailableSince()).isEqualTo(Instant.EPOCH);
    assertThat(saved.getAuthorityUnavailableCount()).isEqualTo(4);
    assertThat(saved.getNextEligibleAt()).isEqualTo(Instant.EPOCH.plusSeconds(30));
    assertThat(updateSql)
        .hasValueSatisfying(
            sql ->
                assertThat(updateSetClause(sql))
                    .contains(
                        "script_pin_epoch",
                        "authority_unavailable_since",
                        "authority_unavailable_count",
                        "next_eligible_at"));
  }

  @Test
  void legacyTriggerExistenceLeavesPluginIdentityUnconstrained() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql().toLowerCase(Locale.ROOT));
          return new MockResult[] {new MockResult(1)};
        };
    ScriptWorkItemRepository repository =
        new ScriptWorkItemRepository(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThat(
            repository
                .existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                    "tenant-1",
                    "game-1",
                    "region-1",
                    3L,
                    "entity-1",
                    "SHARED",
                    "world-1",
                    "realm-1",
                    "1",
                    "script-1",
                    "onTimerExpire",
                    "v1",
                    "patch-1",
                    "event-1",
                    false))
        .isTrue();
    assertThat(sqlRef.get()).doesNotContain("plugin_id", "plugin_version_id", "binding_id");
  }

  @Test
  void pluginAwareTriggerExistenceConstrainsFullPluginIdentity() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql().toLowerCase(Locale.ROOT));
          return new MockResult[] {new MockResult(1)};
        };
    ScriptWorkItemRepository repository =
        new ScriptWorkItemRepository(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThat(
            repository
                .existsByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndEntityIdAndPlayableStateScopeAndWorldSlugAndRealmSlugAndPointerVersionAndScriptIdAndPluginIdAndPluginVersionIdAndBindingIdAndEventTypeAndEventSchemaVersionAndScriptPatchVersionAndScriptEventIdAndDryRun(
                    "tenant-1",
                    "game-1",
                    "region-1",
                    3L,
                    "entity-1",
                    "SHARED",
                    "world-1",
                    "realm-1",
                    "1",
                    "script-1",
                    "plugin-1",
                    "plugin-v1",
                    "binding-1",
                    "onTimerExpire",
                    "v1",
                    "patch-1",
                    "event-1",
                    false))
        .isTrue();
    assertThat(sqlRef.get()).contains("plugin_id", "plugin_version_id", "binding_id");
  }

  @Test
  void cancellationQueriesAreBoundAndWaitForLockedRows() {
    List<String> sqls = new ArrayList<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sqls.add(context.sql().toLowerCase(Locale.ROOT));
          return new MockResult[] {
            new MockResult(0, resultDsl.newResult(ScriptWorkItems.SCRIPT_WORK_ITEMS))
          };
        };
    ScriptWorkItemRepository repository =
        new ScriptWorkItemRepository(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    repository.findByTenantIdAndScriptPatchVersionAndStatusInForUpdateOrderByCreatedAtAscIdAsc(
        "tenant-1", "patch-1", "game-1", "region-1", List.of("PENDING_EVALUATION"));
    repository
        .findByTenantIdAndPluginIdAndPluginVersionIdAndStatusInForUpdateOrderByCreatedAtAscIdAsc(
            "tenant-1",
            "plugin-1",
            "plugin-v1",
            "game-1",
            "region-1",
            List.of("PENDING_EVALUATION"));

    assertThat(sqls).hasSize(2).allSatisfy(sql -> assertBoundAndWaitsForLocks(sql));
  }

  @Test
  void pagedClaimsUseSkipLockedWithTheirRequestedBound() {
    List<String> sqls = new ArrayList<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sqls.add(context.sql().toLowerCase(Locale.ROOT));
          return new MockResult[] {
            new MockResult(0, resultDsl.newResult(ScriptWorkItems.SCRIPT_WORK_ITEMS))
          };
        };
    ScriptWorkItemRepository repository =
        new ScriptWorkItemRepository(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    repository.findByStatusForUpdateOrderByCreatedAtAscIdAsc(
        "PENDING_EVALUATION", PageRequest.of(3, 7));
    repository.findByIdInAndStatusForUpdateOrderByCreatedAtAscIdAsc(
        List.of(1L, 2L), "PENDING_EVALUATION", PageRequest.of(3, 7));

    assertThat(sqls)
        .hasSize(2)
        .allSatisfy(sql -> assertThat(sql).contains("for update skip locked"));
    assertThat(sqls).allSatisfy(sql -> assertThat(sql).contains("fetch next ? rows only"));
    assertThat(sqls).allSatisfy(sql -> assertThat(sql).doesNotContain("offset"));
    assertThat(sqls)
        .allSatisfy(sql -> assertThat(sql).contains("next_eligible_at", "current_timestamp"));
  }

  @Test
  void deadLetterDeletionIsBlockedByRetainedReplayResults() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    AtomicReference<Object[]> bindingsRef = new AtomicReference<>();
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql().toLowerCase(Locale.ROOT));
          bindingsRef.set(context.bindings());
          return new MockResult[] {new MockResult(0)};
        };
    ScriptWorkItemRepository repository =
        new ScriptWorkItemRepository(DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    assertThat(repository.deleteDeadLetteredIfNoRetainedEvidence("tenant-1", 17L)).isFalse();
    assertThat(sqlRef)
        .hasValueSatisfying(
            sql -> {
              assertThat(sql).contains("not exists", "script_dead_letter_replay_results");
              int whereStart = sql.indexOf(" where ");
              int notExistsStart = sql.indexOf("not exists");
              assertThat(whereStart).as(sql).isGreaterThanOrEqualTo(0);
              assertThat(notExistsStart).as(sql).isGreaterThan(whereStart);
              String outerWhere = sql.substring(whereStart, notExistsStart);
              assertThat(outerWhere).contains("script_work_items", "tenant_id");
            });
    assertThat(bindingsRef)
        .hasValueSatisfying(
            bindings -> assertThat(bindings).contains("tenant-1", 17L, "DEAD_LETTERED"));
  }

  private static void assertBoundAndWaitsForLocks(String sql) {
    assertThat(sql).contains("for update").doesNotContain("skip locked");
    assertThat(sql).contains("fetch next ? rows only");
  }

  private static String updateSetClause(String sql) {
    String normalized = sql.toLowerCase(Locale.ROOT);
    int setStart = normalized.indexOf(" set ");
    int whereStart = normalized.indexOf(" where ", setStart);
    assertThat(setStart).as(sql).isGreaterThanOrEqualTo(0);
    assertThat(whereStart).as(sql).isGreaterThan(setStart);
    return normalized.substring(setStart, whereStart);
  }
}
