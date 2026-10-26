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

/** Passive descriptor contribution for the manually composed region lease scripts. */
public final class CanonicalGameplayRegionLeaseRedisScriptContribution
    implements RedisScriptContribution {
  public static final String ACQUIRE_ID = "game-session.region-lease-acquire.v1";
  public static final String RENEW_ID = "game-session.region-lease-renew.v1";
  public static final String READ_ID = "game-session.region-lease-read.v1";
  public static final String ACQUIRE_PATH = "redis/lua/game-session-region-lease-acquire.lua";
  public static final String RENEW_PATH = "redis/lua/game-session-region-lease-renew.lua";
  public static final String READ_PATH = "redis/lua/game-session-region-lease-read.lua";
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
    return List.of(
        read("/redis/scripts/game-session-region-lease-acquire.json", ACQUIRE_ID),
        read("/redis/scripts/game-session-region-lease-renew.json", RENEW_ID),
        read("/redis/scripts/game-session-region-lease-read.json", READ_ID));
  }

  private RedisScriptDescriptor read(String path, String id) {
    try (InputStream input = getClass().getResourceAsStream(path)) {
      if (input == null) throw unavailable();
      byte[] bytes = input.readNBytes(16 * 1024 + 1);
      if (bytes.length == 0 || bytes.length > 16 * 1024) throw unavailable();
      RedisScriptDescriptor descriptor = JSON.readValue(bytes, RedisScriptDescriptor.class);
      if (!ownerId().equals(descriptor.owner()) || !id.equals(descriptor.scriptId())) {
        throw unavailable();
      }
      return descriptor;
    } catch (IOException | RuntimeException failure) {
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Canonical region lease descriptor is unavailable");
  }
}
