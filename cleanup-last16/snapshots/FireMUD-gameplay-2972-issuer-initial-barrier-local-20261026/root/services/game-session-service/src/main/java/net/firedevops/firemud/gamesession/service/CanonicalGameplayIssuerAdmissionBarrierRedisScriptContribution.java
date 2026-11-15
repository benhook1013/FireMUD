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

/** Passive manual descriptor contribution for one-key issuer tenant barrier scripts. */
public final class CanonicalGameplayIssuerAdmissionBarrierRedisScriptContribution
    implements RedisScriptContribution {
  public static final String OPEN_SCRIPT_ID = "game-session.issuer-admission-barrier-open.v1";
  public static final String CLOSE_SCRIPT_ID = "game-session.issuer-admission-barrier-close.v1";
  public static final String OPEN_RESOURCE_PATH =
      "redis/lua/game-session-issuer-admission-barrier-open.lua";
  public static final String CLOSE_RESOURCE_PATH =
      "redis/lua/game-session-issuer-admission-barrier-close.lua";
  private static final String OPEN_DESCRIPTOR_PATH =
      "/redis/scripts/game-session-issuer-admission-barrier-open.json";
  private static final String CLOSE_DESCRIPTOR_PATH =
      "/redis/scripts/game-session-issuer-admission-barrier-close.json";
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
    return List.of(readDescriptor(OPEN_DESCRIPTOR_PATH, OPEN_SCRIPT_ID),
        readDescriptor(CLOSE_DESCRIPTOR_PATH, CLOSE_SCRIPT_ID));
  }

  private RedisScriptDescriptor readDescriptor(String path, String scriptId) {
    try (InputStream input = getClass().getResourceAsStream(path)) {
      if (input == null) throw unavailable();
      byte[] bytes = input.readNBytes(16 * 1024 + 1);
      if (bytes.length == 0 || bytes.length > 16 * 1024) throw unavailable();
      var descriptor = JSON.readValue(bytes, RedisScriptDescriptor.class);
      if (!ownerId().equals(descriptor.owner()) || !scriptId.equals(descriptor.scriptId())) {
        throw unavailable();
      }
      return descriptor;
    } catch (IOException | RuntimeException failure) {
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Issuer admission barrier descriptors are unavailable");
  }
}
