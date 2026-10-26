package net.firedevops.firemud.gamesession.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Passive manual-catalog contribution for one original-operation physical retirement. */
public final class CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution
    implements RedisScriptContribution {
  public static final String SCRIPT_ID = "game-session.issuer-binding-local-retirement.v1";
  public static final String RESOURCE_PATH =
      "redis/lua/game-session-issuer-binding-local-retirement.lua";
  private static final String DESCRIPTOR_PATH =
      "/redis/scripts/game-session-issuer-binding-local-retirement.json";
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  @Override
  public String ownerId() {
    return "game-session-service";
  }

  @Override
  public Collection<RedisScriptDescriptor> descriptors() {
    try (InputStream input = getClass().getResourceAsStream(DESCRIPTOR_PATH)) {
      if (input == null) throw new IOException("Descriptor resource is missing");
      byte[] bytes = input.readNBytes(16 * 1024 + 1);
      if (bytes.length == 0 || bytes.length > 16 * 1024) {
        throw new IOException("Descriptor resource is empty or oversized");
      }
      RedisScriptDescriptor descriptor = JSON.readValue(bytes, RedisScriptDescriptor.class);
      if (!ownerId().equals(descriptor.owner()) || !SCRIPT_ID.equals(descriptor.scriptId())) {
        throw new IllegalArgumentException("Descriptor owner or script identity is invalid");
      }
      return List.of(descriptor);
    } catch (IOException | RuntimeException failure) {
      throw new IllegalStateException(
          "Issuer binding-local retirement descriptor is unavailable", failure);
    }
  }
}
