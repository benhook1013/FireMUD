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

/** Passive descriptor contribution for the one-key CLOSED account barrier script. */
public final class CanonicalGameplayAccountClosedBarrierRedisScriptContribution
    implements RedisScriptContribution {
  public static final String SCRIPT_ID = "game-session.account-admission-barrier-close.v1";
  public static final String RESOURCE_PATH =
      "redis/lua/game-session-account-admission-barrier-close.lua";
  private static final String DESCRIPTOR_PATH =
      "/redis/scripts/game-session-account-admission-barrier-close.json";
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
      var descriptor = JSON.readValue(bytes, RedisScriptDescriptor.class);
      if (!ownerId().equals(descriptor.owner()) || !SCRIPT_ID.equals(descriptor.scriptId())) {
        throw unavailable();
      }
      return List.of(descriptor);
    } catch (IOException | RuntimeException failure) {
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Account admission barrier descriptor is unavailable");
  }
}
