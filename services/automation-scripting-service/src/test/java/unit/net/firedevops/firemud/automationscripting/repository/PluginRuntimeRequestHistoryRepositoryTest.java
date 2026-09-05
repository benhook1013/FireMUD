package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.PluginRuntimeRequestHistory.PLUGIN_RUNTIME_REQUEST_HISTORY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeRequestHistory;
import net.firedevops.firemud.automationscripting.jooq.tables.records.PluginRuntimeRequestHistoryRecord;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class PluginRuntimeRequestHistoryRepositoryTest {
  @Test
  void insertOrGetReturnsDurableWinnerThroughNoOpConflictUpdate() {
    AtomicReference<String> sqlRef = new AtomicReference<>();
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          sqlRef.set(context.sql());
          var result = resultDsl.newResult(PLUGIN_RUNTIME_REQUEST_HISTORY);
          result.add(historyRecord(Instant.EPOCH));
          return new MockResult[] {new MockResult(1, result)};
        };
    PluginRuntimeRequestHistoryRepository repository =
        new PluginRuntimeRequestHistoryRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    PluginRuntimeRequestHistory saved = repository.insertOrGet(history(Instant.EPOCH));

    assertThat(saved.getId()).isEqualTo(73L);
    assertThat(saved.getControlPlaneRequestId()).isEqualTo("request-1");
    assertThat(saved.getRequestFingerprint()).isEqualTo("durable-winner-fingerprint");
    assertThat(saved.getActivePluginVersionId()).isEqualTo("durable-winner-version");
    assertThat(saved.getPluginActivationEpoch()).isEqualTo(9L);
    assertThat(sqlRef.get().toLowerCase(Locale.ROOT))
        .contains("on conflict", "do update", "returning")
        .doesNotContain("do nothing");
  }

  @Test
  void insertOrGetPreservesNullCreatedAt() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          var result = resultDsl.newResult(PLUGIN_RUNTIME_REQUEST_HISTORY);
          result.add(historyRecord(null));
          return new MockResult[] {new MockResult(1, result)};
        };
    PluginRuntimeRequestHistoryRepository repository =
        new PluginRuntimeRequestHistoryRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));

    PluginRuntimeRequestHistory saved = repository.insertOrGet(history(null));

    assertThat(saved.getCreatedAt()).isNull();
  }

  private static PluginRuntimeRequestHistory history(Instant createdAt) {
    PluginRuntimeRequestHistory history = new PluginRuntimeRequestHistory();
    history.setTenantId("tenant-1");
    history.setGameInstanceId("game-1");
    history.setPluginId("plugin-1");
    history.setOperation("DISABLE");
    history.setControlPlaneRequestId("request-1");
    history.setRequestFingerprint("fingerprint-1");
    history.setPreviousPluginVersionId("plugin-v1");
    history.setActivePluginVersionId("plugin-v1");
    history.setPluginActivationEpoch(1L);
    history.setLifecycleRevision(2L);
    history.setPluginState("DISABLED");
    history.setCreatedAt(createdAt);
    return history;
  }

  private static PluginRuntimeRequestHistoryRecord historyRecord(Instant createdAt) {
    PluginRuntimeRequestHistoryRecord record = new PluginRuntimeRequestHistoryRecord();
    record.setId(73L);
    record.setTenantId("tenant-1");
    record.setGameInstanceId("game-1");
    record.setPluginId("plugin-1");
    record.setOperation("DISABLE");
    record.setControlPlaneRequestId("request-1");
    record.setRequestFingerprint("durable-winner-fingerprint");
    record.setPreviousPluginVersionId("plugin-v1");
    record.setActivePluginVersionId("durable-winner-version");
    record.setPluginActivationEpoch(9L);
    record.setLifecycleRevision(11L);
    record.setPluginState("DISABLED");
    record.setCreatedAt(createdAt == null ? null : createdAt.atOffset(java.time.ZoneOffset.UTC));
    return record;
  }
}
