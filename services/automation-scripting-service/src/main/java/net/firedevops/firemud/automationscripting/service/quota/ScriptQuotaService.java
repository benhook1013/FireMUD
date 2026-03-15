package net.firedevops.firemud.automationscripting.service.quota;

/** Service enforcing per-script execution quotas to prevent runaway automation. */
public interface ScriptQuotaService {
  /**
   * Attempt to acquire quota for the given script.
   *
   * @param tenantId tenant identifier
   * @param scriptId script identifier
   * @return {@code true} if execution may proceed, {@code false} if the quota is exceeded
   */
  boolean tryAcquire(Long tenantId, Long scriptId);
}
