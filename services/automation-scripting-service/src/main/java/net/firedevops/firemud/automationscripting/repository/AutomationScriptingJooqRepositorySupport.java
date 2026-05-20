package net.firedevops.firemud.automationscripting.repository;

import org.springframework.dao.OptimisticLockingFailureException;

final class AutomationScriptingJooqRepositorySupport {
  private AutomationScriptingJooqRepositorySupport() {}

  static OptimisticLockingFailureException staleWrite(String tableName, Long id) {
    return new OptimisticLockingFailureException(
        "Stale write rejected for " + tableName + " id=" + id);
  }

  static String normalize(String value) {
    return value == null ? "" : value;
  }
}
