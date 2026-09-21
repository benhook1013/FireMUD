package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.PluginRuntimeStates.PLUGIN_RUNTIME_STATES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.automationscripting.jooq.tables.records.PluginRuntimeStatesRecord;
import org.jooq.DSLContext;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class PluginRuntimeStateRepositoryTest {
  @Test
  void rejectsIncoherentFenceBeforeInsertOrUpdate() {
    PluginRuntimeStateRepository repository =
        new PluginRuntimeStateRepository(DSL.using(SQLDialect.POSTGRES));
    PluginRuntimeState entity = state(1L, 0L);

    assertThatThrownBy(() -> repository.save(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "plugin_activation_epoch and lifecycle_revision must both be zero or both be positive");
  }

  @Test
  void rejectsNegativeFenceBeforeInsertOrUpdate() {
    PluginRuntimeStateRepository repository =
        new PluginRuntimeStateRepository(DSL.using(SQLDialect.POSTGRES));
    PluginRuntimeState entity = state(-1L, -1L);

    assertThatThrownBy(() -> repository.save(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("plugin fence values must be non-negative");
  }

  @Test
  void rejectsActivePluginVersionWithoutPositiveFence() {
    PluginRuntimeStateRepository repository =
        new PluginRuntimeStateRepository(DSL.using(SQLDialect.POSTGRES));
    PluginRuntimeState entity = state(0L, 0L);
    entity.setActivePluginVersionId("version-active");

    assertThatThrownBy(() -> repository.save(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("active_plugin_version_id requires a positive plugin fence pair");
  }

  @Test
  void acceptsZeroZeroFenceAtRepositoryBoundary() {
    PluginRuntimeState saved = saveWithMockDatabase(state(0L, 0L));

    assertThat(saved.getPluginActivationEpoch()).isZero();
    assertThat(saved.getLifecycleRevision()).isZero();
  }

  @Test
  void acceptsPositivePositiveFenceAtRepositoryBoundary() {
    PluginRuntimeState saved = saveWithMockDatabase(state(3L, 8L));

    assertThat(saved.getPluginActivationEpoch()).isEqualTo(3L);
    assertThat(saved.getLifecycleRevision()).isEqualTo(8L);
  }

  private static PluginRuntimeState saveWithMockDatabase(PluginRuntimeState entity) {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    MockDataProvider provider =
        context -> {
          if (context.sql().trim().toLowerCase(java.util.Locale.ROOT).startsWith("update")) {
            return new MockResult[] {new MockResult(1)};
          }
          PluginRuntimeStatesRecord row = stateRecord(entity);
          Result<PluginRuntimeStatesRecord> result = resultDsl.newResult(PLUGIN_RUNTIME_STATES);
          result.add(row);
          return new MockResult[] {new MockResult(1, result)};
        };
    PluginRuntimeStateRepository repository =
        new PluginRuntimeStateRepository(
            DSL.using(new MockConnection(provider), SQLDialect.POSTGRES));
    return repository.save(entity);
  }

  private static PluginRuntimeState state(long activationEpoch, long lifecycleRevision) {
    PluginRuntimeState entity = new PluginRuntimeState();
    entity.setId(7L);
    entity.setTenantId("tenant-1");
    entity.setGameInstanceId("game-1");
    entity.setRuntimeRegionId("region-1");
    entity.setRuntimeRegionEpoch(1L);
    entity.setPluginId("plugin-1");
    entity.setActivePluginVersionId("");
    entity.setPluginActivationEpoch(activationEpoch);
    entity.setLifecycleRevision(lifecycleRevision);
    entity.setPluginState("DISABLED");
    entity.setStatusReason("test");
    entity.setControlPlaneRequestId("");
    entity.setControlPlaneRequestFingerprint("");
    entity.setActorPrincipal("");
    entity.setLastChangedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
    entity.setLastPolicyCheckedAt(java.time.Instant.EPOCH);
    entity.setRowVersion(0);
    return entity;
  }

  private static PluginRuntimeStatesRecord stateRecord(PluginRuntimeState entity) {
    PluginRuntimeStatesRecord row = new PluginRuntimeStatesRecord();
    row.setId(entity.getId());
    row.setTenantId(entity.getTenantId());
    row.setGameInstanceId(entity.getGameInstanceId());
    row.setRuntimeRegionId(entity.getRuntimeRegionId());
    row.setRuntimeRegionEpoch(entity.getRuntimeRegionEpoch());
    row.setPluginId(entity.getPluginId());
    row.setActivePluginVersionId(entity.getActivePluginVersionId());
    row.setPluginActivationEpoch(entity.getPluginActivationEpoch());
    row.setLifecycleRevision(entity.getLifecycleRevision());
    row.setPluginState(entity.getPluginState());
    row.setStatusReason(entity.getStatusReason());
    row.setControlPlaneRequestId(entity.getControlPlaneRequestId());
    row.setControlPlaneRequestFingerprint(entity.getControlPlaneRequestFingerprint());
    row.setActorPrincipal(entity.getActorPrincipal());
    row.setLastChangedAt(LocalDateTime.parse("2026-01-01T00:00:00"));
    row.setLastPolicyCheckedAt(LocalDateTime.parse("1970-01-01T00:00:00"));
    row.setRowVersion(1);
    return row;
  }
}
