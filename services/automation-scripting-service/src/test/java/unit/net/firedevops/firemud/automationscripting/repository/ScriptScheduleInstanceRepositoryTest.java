package net.firedevops.firemud.automationscripting.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.firedevops.firemud.automationscripting.entity.ScriptScheduleInstance;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
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
