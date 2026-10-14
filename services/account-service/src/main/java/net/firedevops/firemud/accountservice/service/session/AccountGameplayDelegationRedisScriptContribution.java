package net.firedevops.firemud.accountservice.service.session;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Passive Account-owned catalog contribution for private issuance and projection scripts. */
public final class AccountGameplayDelegationRedisScriptContribution
    implements RedisScriptContribution {
  private static final int MAX_DESCRIPTOR_BYTES = 16 * 1024;
  private static final String[] DESCRIPTOR_RESOURCES = {
    "/redis/scripts/account-game-session-delegation-pending-register.json",
    "/redis/scripts/account-game-session-delegation-activate-committed.json",
    "/redis/scripts/account-game-session-delegation-authority-project-issuer.json",
    "/redis/scripts/account-selected-tenant-generation-projection.json",
    "/redis/scripts/account-selected-membership-generation-projection.json",
    "/redis/scripts/account-initial-gameplay-login-dispose.json"
  };
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  @Override
  public String ownerId() {
    return "account-service";
  }

  @Override
  public Collection<RedisScriptDescriptor> descriptors() {
    try {
      List<RedisScriptDescriptor> descriptors = new ArrayList<>(DESCRIPTOR_RESOURCES.length);
      for (String resource : DESCRIPTOR_RESOURCES) {
        RedisScriptDescriptor descriptor;
        try (InputStream input =
            AccountGameplayDelegationRedisScriptContribution.class.getResourceAsStream(resource)) {
          if (input == null) {
            throw new IllegalStateException("Account Redis descriptor is unavailable");
          }
          byte[] bytes = input.readNBytes(MAX_DESCRIPTOR_BYTES + 1);
          if (bytes.length == 0 || bytes.length > MAX_DESCRIPTOR_BYTES) {
            throw new IllegalStateException("Account Redis descriptor exceeds its finite bound");
          }
          descriptor = JSON.readValue(bytes, RedisScriptDescriptor.class);
        }
        if (!"account-service".equals(descriptor.owner())) {
          throw new IllegalStateException("Account Redis descriptor owner is invalid");
        }
        descriptors.add(descriptor);
      }
      if (descriptors.size() != 6
          || !descriptors.stream()
              .map(RedisScriptDescriptor::scriptId)
              .toList()
              .equals(
                  List.of(
                      AccountGameplayDelegationRedisClient.SCRIPT_ID,
                      AccountGameplayDelegationRedisClient.ACTIVE_TRANSITION_SCRIPT_ID,
                      AccountGameplayDelegationRedisClient.ISSUER_PROJECTION_SCRIPT_ID,
                      AccountGameplayDelegationRedisClient.TENANT_PROJECTION_SCRIPT_ID,
                      AccountGameplayDelegationRedisClient.MEMBERSHIP_PROJECTION_SCRIPT_ID,
                      AccountGameplayDelegationRedisClient.INITIAL_LOGIN_DISPOSAL_SCRIPT_ID))) {
        throw new IllegalStateException("Account Redis descriptor identities are invalid");
      }
      return List.copyOf(descriptors);
    } catch (IOException | RuntimeException ex) {
      if (ex instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("Account Redis descriptor is unavailable");
    }
  }
}
