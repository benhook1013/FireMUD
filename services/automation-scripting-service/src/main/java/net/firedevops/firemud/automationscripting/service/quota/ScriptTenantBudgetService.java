package net.firedevops.firemud.automationscripting.service.quota;

/** Service enforcing live tenant-level automation execution budgets. */
public interface ScriptTenantBudgetService {
  /**
   * Attempt to reserve live execution budget for a tenant/tier.
   *
   * @param tenantId tenant identifier
   * @param priorityTier low-cardinality priority tier such as high, normal, or background
   * @return {@code true} when execution may proceed, {@code false} when tenant budget is exhausted
   */
  boolean tryReserve(String tenantId, String priorityTier);
}
