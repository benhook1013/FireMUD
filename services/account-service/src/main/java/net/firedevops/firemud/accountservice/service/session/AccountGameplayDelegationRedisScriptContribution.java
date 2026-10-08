package net.firedevops.firemud.accountservice.service.session;

import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.accountservice.service.AccountRedisScriptDescriptorLoader;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;

/**
 * Account-owned descriptors for issuance registry transitions and current authority projections.
 */
public final class AccountGameplayDelegationRedisScriptContribution
    implements RedisScriptContribution {
  private static final String[] DESCRIPTOR_RESOURCES = {
    "/redis/scripts/account-game-session-delegation-pending-register.json",
    "/redis/scripts/account-game-session-delegation-activate-committed.json",
    "/redis/scripts/account-game-session-delegation-authority-project-issuer.json",
    "/redis/scripts/account-selected-tenant-generation-projection.json",
    "/redis/scripts/account-selected-membership-generation-projection.json"
  };

  @Override
  public String ownerId() {
    return "account-service";
  }

  @Override
  public Collection<RedisScriptDescriptor> descriptors() {
    try {
      java.util.ArrayList<RedisScriptDescriptor> descriptors =
          new java.util.ArrayList<>(DESCRIPTOR_RESOURCES.length);
      for (String resource : DESCRIPTOR_RESOURCES) {
        RedisScriptDescriptor descriptor =
            AccountRedisScriptDescriptorLoader.load(
                AccountGameplayDelegationRedisScriptContribution.class, resource);
        if (!"account-service".equals(descriptor.owner())) {
          throw new IllegalStateException("Account Redis descriptor owner is invalid");
        }
        descriptors.add(descriptor);
      }
      if (descriptors.size() != 5
          || !descriptors.stream()
              .map(RedisScriptDescriptor::scriptId)
              .toList()
              .equals(
                  List.of(
                      AccountGameplayDelegationRedisClient.SCRIPT_ID,
                      AccountGameplayDelegationRedisClient.ACTIVE_TRANSITION_SCRIPT_ID,
                      AccountGameplayDelegationRedisClient.ISSUER_PROJECTION_SCRIPT_ID,
                      AccountGameplayDelegationRedisClient.TENANT_PROJECTION_SCRIPT_ID,
                      AccountGameplayDelegationRedisClient.MEMBERSHIP_PROJECTION_SCRIPT_ID))) {
        throw new IllegalStateException("Account Redis descriptor identities are invalid");
      }
      return List.copyOf(descriptors);
    } catch (RuntimeException ex) {
      if (ex instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("Account Redis descriptor is unavailable");
    }
  }
}
