package net.firedevops.firemud.accountservice.controller;

import net.firedevops.firemud.common.security.RequestIdValidation;

final class AccountRequestReaders {
  private AccountRequestReaders() {}

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
