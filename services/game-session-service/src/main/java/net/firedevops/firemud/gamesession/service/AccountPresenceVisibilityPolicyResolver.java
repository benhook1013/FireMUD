package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.client.AccountClient;
import org.springframework.stereotype.Component;

/** Resolves the explicit friend/social visibility policy from account profile plus role clamp. */
@Component
public class AccountPresenceVisibilityPolicyResolver {
  private final AccountClient accountClient;

  public AccountPresenceVisibilityPolicyResolver(AccountClient accountClient) {
    this.accountClient = accountClient;
  }

  public AccountPresenceVisibilityPolicy resolve(
      long tenantId, long accountId, GameplayPresenceRole role) {
    if (role == GameplayPresenceRole.GOD) {
      return AccountPresenceVisibilityPolicy.HIDDEN_STAFF;
    }
    return resolve(tenantId, accountId);
  }

  public AccountPresenceVisibilityPolicy resolve(long tenantId, long accountId) {
    return accountClient.getPresenceVisibilityPolicy(tenantId, accountId);
  }
}
