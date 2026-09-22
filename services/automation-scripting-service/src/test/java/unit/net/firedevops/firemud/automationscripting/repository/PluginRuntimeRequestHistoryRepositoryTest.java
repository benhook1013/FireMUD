package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.PluginRuntimeRequestHistory.PLUGIN_RUNTIME_REQUEST_HISTORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeRequestHistory;
import net.firedevops.firemud.automationscripting.jooq.tables.records.PluginRuntimeRequestHistoryRecord;
import org.jooq.DSLContext;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class PluginRuntimeRequestHistoryRepositoryTest {
  @Test
  void rejectsIncoherentFenceBeforeInsert() {
    PluginRuntimeRequestHistoryRepository repository =
        new PluginRuntimeRequestHistoryRepository(DSL.using(SQLDialect.POSTGRES));
    PluginRuntimeRequestHistory entity = history(1L, 0L);

    assertThatThrownBy(() -> repository.insertOrGet(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "plugin_activation_epoch and lifecycle_revision must both be zero or both be positive");
  }

  @Test
  void rejectsNegativeFenceBeforeInsert() {
    PluginRuntimeRequestHistoryRepository repository =
        new PluginRuntimeRequestHistoryRepository(DSL.using(SQLDialect.POSTGRES));
    PluginRuntimeRequestHistory entity = history(-1L, -1L);

    assertThatThrownBy(() -> repository.insertOrGet(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("plugin fence values must be non-negative");
  }

  @Test
  void acceptsZeroZeroFenceAtRepositoryBoundary() {
    PluginRuntimeRequestHistory saved = insertWithMockDatabase(history(0L, 0L));

    assertThat(saved.getPluginActivationEpoch()).isZero();
    assertThat(saved.getLifecycleRevision()).isZero();
  }

  @Test
  void acceptsZeroZeroFenceForSuccessfulNoopReceipt() {
    PluginRuntimeRequestHistory noOp = history(0L, 0L);
    noOp.setActivePluginVersionId("version-active");

    PluginRuntimeRequestHistory saved = insertWithMockDatabase(noOp);

    assertThat(saved.getActivePluginVersionId()).isEqualTo("version-active");
    assertThat(saved.getPluginActivationEpoch()).isZero();
    assertThat(saved.getLifecycleRevision()).isZero();
  }

  @Test
  void acceptsPositivePositiveFenceAtRepositoryBoundary() {
    PluginRuntimeRequestHistory saved = insertWithMockDatabase(history(3L, 8L));

    assertThat(saved.getPluginActivationEpoch()).isEqualTo(3L);
    assertThat(saved.getLifecycleRevision()).isEqualTo(8L);
  }

  private static PluginRuntimeRequestHistory insertWithMockDatabase(
      PluginRuntimeRequestHistory entity) {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          PluginRuntimeRequestHistoryRecord row = historyRecord(entity);
          Result<PluginRuntimeRequestHistoryRecord> result =
              resultDsl.newResult(PLUGIN_RUNTIME_REQUEST_HISTORY);
          result.add(row);
          return new MockResult[] {new MockResult(1, result)};
        };
    PluginRuntimeRequestHistoryRepository repository =
        new PluginRuntimeRequestHistoryRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));
    return repository.insertOrGet(entity);
  }

  private static PluginRuntimeRequestHistory history(long activationEpoch, long lifecycleRevision) {
    PluginRuntimeRequestHistory entity = new PluginRuntimeRequestHistory();
    entity.setTenantId("tenant-1");
    entity.setGameInstanceId("game-1");
    entity.setPluginId("plugin-1");
    entity.setOperation("DISABLE");
    entity.setControlPlaneRequestId("request-1");
    entity.setRequestFingerprint("fingerprint-1");
    entity.setPreviousPluginVersionId("");
    entity.setActivePluginVersionId("");
    entity.setPluginActivationEpoch(activationEpoch);
    entity.setLifecycleRevision(lifecycleRevision);
    entity.setPluginState("DISABLED");
    entity.setRequestOutcome("SUCCEEDED");
    entity.setFailureCode("");
    entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    return entity;
  }

  private static PluginRuntimeRequestHistoryRecord historyRecord(
      PluginRuntimeRequestHistory entity) {
    PluginRuntimeRequestHistoryRecord row = new PluginRuntimeRequestHistoryRecord();
    row.setId(7L);
    row.setTenantId(entity.getTenantId());
    row.setGameInstanceId(entity.getGameInstanceId());
    row.setPluginId(entity.getPluginId());
    row.setOperation(entity.getOperation());
    row.setControlPlaneRequestId(entity.getControlPlaneRequestId());
    row.setRequestFingerprint(entity.getRequestFingerprint());
    row.setPreviousPluginVersionId(entity.getPreviousPluginVersionId());
    row.setActivePluginVersionId(entity.getActivePluginVersionId());
    row.setPluginActivationEpoch(entity.getPluginActivationEpoch());
    row.setLifecycleRevision(entity.getLifecycleRevision());
    row.setPluginState(entity.getPluginState());
    row.setRequestOutcome(entity.getRequestOutcome());
    row.setFailureCode(entity.getFailureCode());
    row.setCreatedAt(OffsetDateTime.ofInstant(entity.getCreatedAt(), ZoneOffset.UTC));
    return row;
  }
}
