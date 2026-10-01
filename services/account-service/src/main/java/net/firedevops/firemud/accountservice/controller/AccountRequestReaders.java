package net.firedevops.firemud.accountservice.controller;

import java.util.UUID;
import net.firedevops.firemud.common.security.RequestIdValidation;

final class AccountRequestReaders {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private AccountRequestReaders() {}

  static UUID requireAccountUuid(String accountId) {
    if (accountId == null || accountId.isBlank()) {
      throw new IllegalArgumentException("accountId must be a canonical non-nil UUID");
    }
    try {
      UUID parsed = UUID.fromString(accountId);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(accountId)) {
        throw new IllegalArgumentException("accountId must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("accountId must be a canonical non-nil UUID", exception);
    }
  }

  static long requireAccountId(String accountId) {
    return RequestIdValidation.requirePositiveLong(accountId, "accountId");
  }

  static long requireAccountId(Long accountId) {
    return RequestIdValidation.requirePositiveLong(accountId, "accountId");
  }

  static long requireTenantId(String tenantId) {
    return RequestIdValidation.requirePositiveLong(tenantId, "tenantId");
  }

  static long requireTenantId(Long tenantId) {
    return RequestIdValidation.requirePositiveLong(tenantId, "tenantId");
  }
}
