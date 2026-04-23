package net.firedevops.firemud.automationscripting.service.redis;

/** Canonical Redis key builder for Automation & Scripting-owned prefixes. */
public final class AutomationRedisKeys {
  private AutomationRedisKeys() {}

  public static String automationQueue(String tenantId, String gameInstanceId, String entityId) {
    return "automation:queue:{"
        + tenantInstanceTag(tenantId, gameInstanceId)
        + "}:"
        + requirePart("entityId", entityId);
  }

  public static String automationQuota(String tenantId, String scriptId) {
    return "automation:quota:"
        + requirePart("tenantId", tenantId)
        + ":"
        + requirePart("scriptId", scriptId);
  }

  public static String automationTenantBudget(String tenantId, String priorityTier) {
    return "automation:tenant-budget:"
        + requirePart("tenantId", tenantId)
        + ":tier:"
        + requirePart("priorityTier", priorityTier);
  }

  public static String automationDryRunTenantQuota(String tenantId, String scriptId) {
    return "automation:test:quota:"
        + requirePart("tenantId", tenantId)
        + ":script:"
        + requirePart("scriptId", scriptId)
        + ":tenant";
  }

  public static String automationDryRunPrincipalQuota(
      String tenantId, String scriptId, String principalKey) {
    return "automation:test:quota:"
        + requirePart("tenantId", tenantId)
        + ":script:"
        + requirePart("scriptId", scriptId)
        + ":principal:"
        + requirePart("principalKey", principalKey);
  }

  public static String automationDryRunCapacityCounter(String tenantId) {
    return "automation:test:capacity:" + requirePart("tenantId", tenantId) + ":tenant";
  }

  public static String automationDryRunCapacityLease(String tenantId, String workItemId) {
    return "automation:test:capacity:"
        + requirePart("tenantId", tenantId)
        + ":lease:"
        + requirePart("workItemId", workItemId);
  }

  public static String automationDryRunClusterCapacityCounter() {
    return "automation:test:capacity:cluster";
  }

  public static String automationDryRunClusterCapacityLease(String tenantId, String workItemId) {
    return "automation:test:capacity:cluster:lease:"
        + requirePart("tenantId", tenantId)
        + ":"
        + requirePart("workItemId", workItemId);
  }

  private static String tenantInstanceTag(String tenantId, String gameInstanceId) {
    return "tenant:"
        + requirePart("tenantId", tenantId)
        + ":instance:"
        + requirePart("gameInstanceId", gameInstanceId);
  }

  private static String requirePart(String name, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required for automation Redis keys");
    }
    return value;
  }
}
