package net.firedevops.firemud.accountservice.controller;

import java.util.UUID;
import net.firedevops.firemud.accountservice.AccountUuidText;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.security.RequestIdValidation;
import org.springframework.dao.DataAccessException;

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

  static UUID requireAccountUuid(String accountId) {
    UUID parsed = AccountUuidText.parseOrNull(accountId);
    if (parsed == null) {
      throw new IllegalArgumentException("accountId must be a canonical non-nil Account UUID");
    }
    return parsed;
  }

  static long resolveAccountStorageId(AccountService accountService, UUID accountUuid) {
    try {
      Long storageId = accountService.resolveAccountStorageId(accountUuid);
      if (storageId == null || storageId <= 0L) {
        throw new IllegalStateException("Account UUID resolved to an invalid storage owner");
      }
      return storageId;
    } catch (IllegalArgumentException ex) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.FORBIDDEN, "Account access required");
    } catch (IllegalStateException | DataAccessException ex) {
      throw new AuthenticationException("AUTH_UNAVAILABLE", "Account authority is unavailable", ex);
    }
  }
}
