package net.firedevops.firemud.socialgroups.security;

import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SocialAccessGuard {
  public void requireAccountAccess(long tenantId, String accountId) {
    if (!hasAccountAccess(tenantId, accountId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account access required");
    }
  }

  public boolean hasAccountAccess(long tenantId, String accountId) {
    if (tenantId <= 0) {
      return false;
    }
    return isCurrentAccount(accountId);
  }

  /** Allows tenant-authorized guild ownership or exact self-ownership for an ordinary caller. */
  public boolean hasGuildOwnerAccess(long tenantId, String accountId) {
    if (tenantId <= 0) {
      return false;
    }
    try {
      String canonicalAccountId =
          JwtClaims.requireCanonicalAccountUuid(accountId, "ownerAccountId");
      return SessionContext.hasTenantAccess(tenantId) || isCurrentAccount(canonicalAccountId);
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  public void requireTenantAccess(long tenantId) {
    SessionContext.requireTenantAccess(tenantId);
  }

  private boolean isCurrentAccount(String accountId) {
    try {
      return JwtClaims.requireCanonicalAccountUuid(accountId, "accountId")
          .equals(
              JwtClaims.requireCanonicalAccountUuid(SessionContext.getAccountId(), "accountId"));
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }
}
