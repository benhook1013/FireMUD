package net.firedevops.firemud.socialgroups.security;

import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.stereotype.Component;

@Component
public class SocialAccessGuard {
  public void requireAccountAccess(long tenantId, String accountId) {
    SessionContext.requireAccountAccess(
        tenantId, JwtClaims.requireAccountId(accountId, "accountId"));
  }

  public boolean hasAccountAccess(long tenantId, String accountId) {
    try {
      return SessionContext.hasAccountAccess(
          tenantId, JwtClaims.requireAccountId(accountId, "accountId"));
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  public void requireTenantAccess(long tenantId) {
    SessionContext.requireTenantAccess(tenantId);
  }
}
