package net.firedevops.firemud.loggingadmin.service.impl;

import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class SessionActorReaders {
  private SessionActorReaders() {}

  static String actorPrincipalOrInternalService() {
    String accountUuid = currentAccountUuidOrNull();
    return accountUuid == null ? "internal-service" : accountUuid;
  }

  static String currentAccountUuidOrNull() {
    try {
      String accountId = SessionContext.getAccountId();
      return accountId == null || accountId.isBlank()
          ? null
          : JwtClaims.requireCanonicalAccountUuid(accountId, "accountId");
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "current account claim was invalid");
    }
  }
}
