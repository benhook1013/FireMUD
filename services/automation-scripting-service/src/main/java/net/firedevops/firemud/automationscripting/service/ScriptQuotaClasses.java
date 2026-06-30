package net.firedevops.firemud.automationscripting.service;

public final class ScriptQuotaClasses {
  public static final String STANDARD_RUNTIME = "STANDARD_RUNTIME";
  public static final String PUBLISH_READINESS = "PUBLISH_READINESS";

  private ScriptQuotaClasses() {}

  public static String normalize(String quotaClass) {
    return quotaClass == null || quotaClass.isBlank() ? STANDARD_RUNTIME : quotaClass;
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
