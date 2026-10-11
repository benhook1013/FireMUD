package net.firedevops.firemud.accountservice.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Passive Account-owned catalog contribution for its generation-projection CAS script. */
public final class AccountGenerationProjectionRedisScriptContribution
    implements RedisScriptContribution {
  private static final int MAX_DESCRIPTOR_BYTES = 16 * 1024;
  private static final String DESCRIPTOR_RESOURCE =
      "/redis/scripts/account-generation-projection-cas.json";
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
    try (InputStream input =
        AccountGenerationProjectionRedisScriptContribution.class.getResourceAsStream(
            DESCRIPTOR_RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException("Account generation projection descriptor is unavailable");
      }
      byte[] bytes = input.readNBytes(MAX_DESCRIPTOR_BYTES + 1);
      if (bytes.length == 0 || bytes.length > MAX_DESCRIPTOR_BYTES) {
        throw new IllegalStateException(
            "Account generation projection descriptor exceeds its finite bound");
      }
      RedisScriptDescriptor descriptor = JSON.readValue(bytes, RedisScriptDescriptor.class);
      if (!"account-service".equals(descriptor.owner())
          || !AccountGenerationProjectionRedisContract.SCRIPT_ID.equals(descriptor.scriptId())) {
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
