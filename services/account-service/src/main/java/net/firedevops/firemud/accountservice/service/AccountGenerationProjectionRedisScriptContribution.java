package net.firedevops.firemud.accountservice.service;

import java.io.IOException;
import java.io.InputStream;
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
    try (InputStream input =
        AccountGenerationProjectionRedisScriptContribution.class.getResourceAsStream(
            DESCRIPTOR_RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException("Account generation projection descriptor is unavailable");
      }
      RedisScriptDescriptor descriptor =
          RedisScriptDescriptor.parse(
              input.readNBytes(RedisScriptDescriptor.MAX_DESCRIPTOR_BYTES + 1));
      if (!"account-service".equals(descriptor.owner())
          || !"account.account-generation-projection.v1".equals(descriptor.id())) {
        throw new IllegalStateException(
            "Account generation projection descriptor identity is invalid");
      }
      return List.of(descriptor);
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("Account generation projection descriptor is unavailable");
    }
  }
}
