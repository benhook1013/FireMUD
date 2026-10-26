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

/** Passive manual-catalog contribution for the purpose-specific issuer release operation. */
public final class CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution
    implements RedisScriptContribution {
  public static final String SCRIPT_ID = "game-session.issuer-binding-local-release.v1";
  public static final String RESOURCE_PATH = "redis/lua/game-session-issuer-binding-local-release.lua";
  private static final String DESCRIPTOR_PATH =
      "/redis/scripts/game-session-issuer-binding-local-release.json";
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
      if (input == null) throw unavailable();
      byte[] bytes = input.readNBytes(16 * 1024 + 1);
      if (bytes.length == 0 || bytes.length > 16 * 1024) throw unavailable();
      RedisScriptDescriptor descriptor = JSON.readValue(bytes, RedisScriptDescriptor.class);
      if (!ownerId().equals(descriptor.owner()) || !SCRIPT_ID.equals(descriptor.scriptId())) {
        throw unavailable();
      }
      return List.of(descriptor);
    } catch (IOException | RuntimeException failure) {
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Issuer binding-local release Redis descriptor is unavailable");
  }
}
