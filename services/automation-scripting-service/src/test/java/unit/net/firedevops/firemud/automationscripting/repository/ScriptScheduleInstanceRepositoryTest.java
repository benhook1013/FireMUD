package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptScheduleInstances.SCRIPT_SCHEDULE_INSTANCES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleInstance;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptScheduleInstancesRecord;
import org.jooq.DSLContext;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class ScriptScheduleInstanceRepositoryTest {
  @Test
  void rejectsIncoherentFenceBeforeInsertOrUpdate() {
    ScriptScheduleInstanceRepository repository =
        new ScriptScheduleInstanceRepository(DSL.using(SQLDialect.POSTGRES));

    assertFenceRejected(repository, null, 1L, 0L, "both be zero or both be positive");
    assertFenceRejected(repository, 7L, 1L, 0L, "both be zero or both be positive");
  }

  @Test
  void rejectsNegativeFenceBeforeInsertOrUpdate() {
    ScriptScheduleInstanceRepository repository =
        new ScriptScheduleInstanceRepository(DSL.using(SQLDialect.POSTGRES));

    assertFenceRejected(repository, null, -1L, -1L, "must be non-negative");
    assertFenceRejected(repository, 7L, -1L, -1L, "must be non-negative");
  }

  @Test
  void saveWritesNewBaseVersionWhenHistoricalBaseWasNull() {
    assertBaseVersionUpdate(null, 42L);
  }

  @Test
  void saveWritesNewBaseVersionAcrossPositiveBaseTransition() {
    assertBaseVersionUpdate(7L, 42L);
  }

  private static void assertBaseVersionUpdate(Long persistedBaseVersionId, Long newBaseVersionId) {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicReference<String> updateSql = new AtomicReference<>();
    AtomicReference<Object[]> updateBindings = new AtomicReference<>();
    AtomicReference<Long> baseVersionAtUpdate = new AtomicReference<>();
    ScriptScheduleInstance entity = scheduleInstance(newBaseVersionId);
    int originalRowVersion = entity.getRowVersion();
    ScriptScheduleInstancesRecord row = new ScriptScheduleInstancesRecord();
    row.setId(entity.getId());
    row.setScriptPatchBaseVersionId(persistedBaseVersionId);
    row.setCadenceValue(1L);
    row.setRowVersion(originalRowVersion);
    MockDataProvider provider =
        context -> {
          String sql = context.sql().trim().toLowerCase(Locale.ROOT);
          if (sql.startsWith("update")) {
            updateSql.set(sql);
            updateBindings.set(context.bindings());
            baseVersionAtUpdate.set(row.getScriptPatchBaseVersionId());
            row.setScriptPatchBaseVersionId(newBaseVersionId);
            row.setRowVersion(originalRowVersion + 1);
            return new MockResult[] {new MockResult(1)};
          }
          Result<ScriptScheduleInstancesRecord> result =
              resultDsl.newResult(SCRIPT_SCHEDULE_INSTANCES);
          result.add(row);
          return new MockResult[] {new MockResult(1, result)};
        };
    ScriptScheduleInstanceRepository repository =
        new ScriptScheduleInstanceRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    ScriptScheduleInstance saved = repository.save(entity);

    assertThat(saved.getScriptPatchBaseVersionId()).isEqualTo(newBaseVersionId);
    assertThat(saved.getRowVersion()).isEqualTo(originalRowVersion + 1);
    assertThat(entity.getRowVersion()).isEqualTo(originalRowVersion + 1);
    assertThat(baseVersionAtUpdate.get()).isEqualTo(persistedBaseVersionId);
    String sql = updateSql.get();
    assertThat(sql).contains("script_patch_base_version_id");
    assertThat(updateBindings.get()).contains(newBaseVersionId);
    String whereClause = sql.substring(sql.indexOf(" where "));
    assertThat(whereClause).contains("id", "row_version");
    assertThat(whereClause).doesNotContain("script_patch_base_version_id");
  }

  private static ScriptScheduleInstance scheduleInstance(Long scriptPatchBaseVersionId) {
    ScriptScheduleInstance entity = new ScriptScheduleInstance();
    entity.setId(7L);
    entity.setTenantId("tenant-1");
    entity.setGameInstanceId("game-1");
    entity.setScriptPatchVersion("patch-2");
    entity.setScriptPatchBaseVersionId(scriptPatchBaseVersionId);
    entity.setScriptPinEpoch(2L);
    entity.setScriptId("script-1");
    entity.setRowVersion(4);
    return entity;
  }

  private static void assertFenceRejected(
      ScriptScheduleInstanceRepository repository,
      Long id,
      long activationEpoch,
      long lifecycleRevision,
      String message) {
    ScriptScheduleInstance entity = new ScriptScheduleInstance();
    entity.setId(id);
    entity.setPluginActivationEpoch(activationEpoch);
    entity.setLifecycleRevision(lifecycleRevision);

    assertThatThrownBy(() -> repository.save(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(message);
  }
}
