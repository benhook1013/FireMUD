package net.firedevops.firemud.accountservice.dto;

import java.util.Objects;
import java.util.UUID;

/** Explicit representation of the tenant identity carried by an Account audit envelope. */
public record AccountAuditTenantIdentity(int version, Long tenantId, UUID tenantUuid) {
  public static final int VERSION_1 = 1;
  public static final int VERSION_2 = 2;

  public AccountAuditTenantIdentity {
    if (version == VERSION_1) {
      if (tenantUuid != null || (tenantId != null && tenantId <= 0L)) {
        throw new IllegalArgumentException(
            "Version-1 audit identity must use a positive numeric ID");
      }
    } else if (version == VERSION_2) {
      if (tenantId != null || tenantUuid == null || isNil(tenantUuid)) {
        throw new IllegalArgumentException(
            "Version-2 audit identity must use a non-nil tenant UUID");
      }
    } else {
      throw new IllegalArgumentException("Unsupported Account audit tenant identity version");
    }
  }

  public static AccountAuditTenantIdentity platformV1() {
    return new AccountAuditTenantIdentity(VERSION_1, null, null);
  }

  public static AccountAuditTenantIdentity retainedTenantV1(long tenantId) {
    return new AccountAuditTenantIdentity(VERSION_1, tenantId, null);
  }

  /** Strict wire/storage constructor; construction does not prove tenant ownership or authority. */
  public static AccountAuditTenantIdentity canonicalTenantV2(String tenantUuid) {
    if (tenantUuid == null) {
      throw new IllegalArgumentException("Canonical tenant UUID is required");
    }
    final UUID parsed;
    try {
      parsed = UUID.fromString(tenantUuid);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Canonical tenant UUID is malformed", exception);
    }
    if (!tenantUuid.equals(parsed.toString())) {
      throw new IllegalArgumentException("Canonical tenant UUID must use lowercase canonical form");
    }
    return new AccountAuditTenantIdentity(VERSION_2, null, parsed);
  }

  public void requireScope(String scope) {
    Objects.requireNonNull(scope, "scope");
    boolean platformV1 =
        "platform".equals(scope) && version == VERSION_1 && tenantId == null && tenantUuid == null;
    boolean tenantV1 =
        "tenant".equals(scope)
            && version == VERSION_1
            && tenantId != null
            && tenantId > 0L
            && tenantUuid == null;
    boolean tenantV2 =
        "tenant".equals(scope)
            && version == VERSION_2
            && tenantId == null
            && tenantUuid != null
            && !isNil(tenantUuid);
    if (!platformV1 && !tenantV1 && !tenantV2) {
      throw new IllegalArgumentException(
          "Account audit scope and typed tenant identity must match");
    }
  }

  private static boolean isNil(UUID value) {
    return value.getMostSignificantBits() == 0L && value.getLeastSignificantBits() == 0L;
  }
}
