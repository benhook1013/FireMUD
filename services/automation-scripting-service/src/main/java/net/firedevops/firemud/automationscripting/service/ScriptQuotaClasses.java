package net.firedevops.firemud.automationscripting.service;

public final class ScriptQuotaClasses {
  public static final String STANDARD_RUNTIME = "STANDARD_RUNTIME";
  public static final String PUBLISH_READINESS = "PUBLISH_READINESS";

  private ScriptQuotaClasses() {}

  public static String normalize(String quotaClass) {
    if (quotaClass == null) {
      return STANDARD_RUNTIME;
    }
    String normalized = quotaClass.trim();
    if (normalized.isEmpty()) {
      return STANDARD_RUNTIME;
    }
    return switch (normalized) {
      case STANDARD_RUNTIME -> STANDARD_RUNTIME;
      case PUBLISH_READINESS -> PUBLISH_READINESS;
      default -> STANDARD_RUNTIME;
    };
  }

  public static boolean consumesLiveScriptQuota(String quotaClass) {
    return STANDARD_RUNTIME.equals(normalize(quotaClass));
  }

  public static boolean consumesLiveTenantBudget(String quotaClass) {
    return STANDARD_RUNTIME.equals(normalize(quotaClass));
  }

  public static boolean usesPublishReadinessCapacity(String quotaClass) {
    return PUBLISH_READINESS.equals(normalize(quotaClass));
  }
}
