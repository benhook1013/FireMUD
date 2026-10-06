package net.firedevops.firemud.accountservice.service;

/** Typed fail-closed outcomes for Account's protected non-paid demo entitlement slice. */
public final class DemoTenantEntitlementException extends IllegalStateException {
  private final Code code;

  public DemoTenantEntitlementException(Code code, String message) {
    super(message);
    this.code = code;
  }

  public DemoTenantEntitlementException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public Code code() {
    return code;
  }

  public enum Code {
    FIXTURE_DISABLED,
    ENVIRONMENT_DENIED,
    SOURCE_EVIDENCE_INVALID,
    IDEMPOTENCY_CONFLICT,
    ENTITLEMENT_VERSION_CONFLICT,
    TENANT_AUTHORITY_STALE,
    ENTITLEMENT_UNAVAILABLE,
    STALE_ADMISSION_EVIDENCE
  }
}
