package net.firedevops.firemud.gamesession.command.text;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Passive UI-only contract registration, not a client, receiving-chain or admission producer. */
public final class DirectTextWorldMenuRedisScriptContribution implements RedisScriptContribution {
  public static final String SCRIPT_ID = "game-session.direct-text-world-menu-cas.v1";
  public static final String RESOURCE_PATH =
      "redis/lua/game-session-direct-text-world-menu-cas.lua";
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
    try (InputStream input =
        getClass()
            .getResourceAsStream("/redis/scripts/game-session-direct-text-world-menu-cas.json")) {
      if (input == null) throw unavailable();
      byte[] bytes = input.readNBytes(16 * 1024 + 1);
      if (bytes.length == 0 || bytes.length > 16 * 1024) throw unavailable();
      var descriptor = JSON.readValue(bytes, RedisScriptDescriptor.class);
      if (!ownerId().equals(descriptor.owner()) || !SCRIPT_ID.equals(descriptor.scriptId()))
        throw unavailable();
      return List.of(descriptor);
    } catch (IOException | RuntimeException failure) {
      throw new IllegalStateException("Public world menu Redis descriptor is unavailable", failure);
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Public world menu Redis descriptor is unavailable");
  }
}
