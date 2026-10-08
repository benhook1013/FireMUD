package net.firedevops.firemud.accountservice.service;

import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;

/** Account-owned descriptor contribution for the current-generation projection CAS script. */
public final class AccountGenerationProjectionRedisScriptContribution
    implements RedisScriptContribution {
  private static final String DESCRIPTOR_RESOURCE =
      "/redis/scripts/account-generation-projection-cas.json";

  @Override
  public String ownerId() {
    return "account-service";
  }

  @Override
  public Collection<RedisScriptDescriptor> descriptors() {
    try {
      RedisScriptDescriptor descriptor =
          AccountRedisScriptDescriptorLoader.load(
              AccountGenerationProjectionRedisScriptContribution.class, DESCRIPTOR_RESOURCE);
      if (!"account-service".equals(descriptor.owner())
          || !"account.account-generation-projection.v1".equals(descriptor.scriptId())) {
        throw new IllegalStateException(
            "Account generation projection descriptor identity is invalid");
      }
      return List.of(descriptor);
    } catch (RuntimeException exception) {
      if (exception instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("Account generation projection descriptor is unavailable");
    }
  }
}
