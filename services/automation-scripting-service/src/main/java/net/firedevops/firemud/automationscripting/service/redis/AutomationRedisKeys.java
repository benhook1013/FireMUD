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

  public static String automationTickQueue(
      String tenantId, String gameInstanceId, String scriptId) {
    return automationTickKey(tenantId, gameInstanceId, scriptId, "queue");
  }

  public static String automationTickLock(String tenantId, String gameInstanceId, String scriptId) {
    return automationTickKey(tenantId, gameInstanceId, scriptId, "lock");
  }

  public static String automationTickPending(
      String tenantId, String gameInstanceId, String scriptId) {
    return automationTickKey(tenantId, gameInstanceId, scriptId, "pending");
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

  private static String automationTickKey(
      String tenantId, String gameInstanceId, String scriptId, String suffix) {
    return "automation:tick:{"
        + tenantInstanceTag(tenantId, gameInstanceId)
        + ":script:"
        + requirePart("scriptId", scriptId)
        + "}:"
        + suffix;
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
