package net.firedevops.firemud.accountservice.controller;

import java.util.UUID;
import net.firedevops.firemud.accountservice.AccountUuidText;
import net.firedevops.firemud.common.security.RequestIdValidation;

final class AccountRequestReaders {
  private AccountRequestReaders() {}

  static UUID requireAccountUuid(String accountId) {
    UUID parsed = AccountUuidText.parseOrNull(accountId);
    if (parsed == null) {
      throw new IllegalArgumentException("accountId must be a canonical non-nil UUID");
    }
    return parsed;
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
