package net.firedevops.firemud.gamesession.service;

import org.springframework.stereotype.Component;

/** Resolves the explicit friend/social visibility policy from canonical gameplay presence facts. */
@Component
public class AccountPresenceVisibilityPolicyResolver {
  public AccountPresenceVisibilityPolicy resolve(GameplayPresenceRole role) {
    if (role == GameplayPresenceRole.GOD) {
      return AccountPresenceVisibilityPolicy.HIDDEN_STAFF;
    }
    return AccountPresenceVisibilityPolicy.FRIENDS_ONLY;
  }
}
