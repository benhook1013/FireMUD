package net.firedevops.firemud.loggingadmin.service.impl;

import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class SessionActorReaders {
  private SessionActorReaders() {}

  static String actorPrincipalOrInternalService() {
    Long accountId = currentAccountIdOrNull();
    return accountId == null ? "internal-service" : Long.toString(accountId);
  }

  static Long currentAccountIdOrNull() {
    try {
      return SessionContext.currentAccountIdOrNull();
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "current account claim was invalid");
    }
  }
}
