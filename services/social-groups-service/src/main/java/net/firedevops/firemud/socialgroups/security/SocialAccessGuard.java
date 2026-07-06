package net.firedevops.firemud.socialgroups.security;

import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SocialAccessGuard {
  public void requireAccountAccess(long tenantId, long accountId) {
    if (!hasAccountAccess(tenantId, accountId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account access required");
    }
  }

  public boolean hasAccountAccess(long tenantId, long accountId) {
    return SessionContext.hasTenantAccess(tenantId) || isCurrentAccount(accountId);
  }

  public void requireTenantAccess(long tenantId) {
    SessionContext.requireTenantAccess(tenantId);
  }

  private boolean isCurrentAccount(long accountId) {
    return SessionContext.isCurrentAccount(accountId);
  }
}
