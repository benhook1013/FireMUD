package net.firedevops.firemud.automationscripting.repository;

import java.util.List;
import org.springframework.dao.OptimisticLockingFailureException;

final class AutomationScriptingJooqRepositorySupport {
  static final List<String> TERMINAL_WORK_ITEM_STATUSES =
      List.of("HANDED_OFF", "CANCELED", "DEAD_LETTERED");

  private AutomationScriptingJooqRepositorySupport() {}

  static OptimisticLockingFailureException staleWrite(String tableName, Long id) {
    return new OptimisticLockingFailureException(
        "Stale write rejected for " + tableName + " id=" + id);
  }

  static String normalize(String value) {
    return value == null ? "" : value;
  }

  static void requireCoherentPluginFence(long activationEpoch, long lifecycleRevision) {
    if (activationEpoch < 0L || lifecycleRevision < 0L) {
      throw new IllegalArgumentException("plugin fence values must be non-negative");
    }
    if ((activationEpoch == 0L) != (lifecycleRevision == 0L)) {
      throw new IllegalArgumentException(
          "plugin_activation_epoch and lifecycle_revision must both be zero or both be positive");
    }
  }
}
