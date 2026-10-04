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
    // Social still exposes legacy numeric account selectors. They cannot prove self access under
    // the canonical Account UUID identity contract, so only the existing tenant-role gate applies
    // until Social's account UUID migration is complete.
    return accountId > 0L && SessionContext.hasTenantAccess(tenantId);
  }

  public void requireTenantAccess(long tenantId) {
    SessionContext.requireTenantAccess(tenantId);
  }
}
