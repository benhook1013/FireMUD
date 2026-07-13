package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.client.AccountClient;
import org.springframework.stereotype.Component;

/** Resolves the explicit friend/social visibility policy from account profile. */
@Component
public class AccountPresenceVisibilityPolicyResolver {
  private final AccountClient accountClient;

  public AccountPresenceVisibilityPolicyResolver(AccountClient accountClient) {
    this.accountClient = accountClient;
  }

  public AccountPresenceVisibilityPolicy resolve(long tenantId, long accountId) {
    return accountClient.getPresenceVisibilityPolicy(tenantId, accountId);
  }
}
