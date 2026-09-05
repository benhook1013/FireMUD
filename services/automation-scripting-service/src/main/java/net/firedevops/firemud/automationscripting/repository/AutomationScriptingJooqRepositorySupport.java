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
}
