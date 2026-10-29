package net.firedevops.firemud.gamesession.service;

import java.io.IOException;
import java.io.InputStream;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Strict bounded resource loading for passive Game Session Redis script contributions. */
final class GameSessionRedisScriptDescriptors {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private GameSessionRedisScriptDescriptors() {}

  static RedisScriptDescriptor load(
      Class<?> resourceOwner, String path, String ownerId, String scriptId, String failureMessage) {
    try (InputStream input = resourceOwner.getResourceAsStream(path)) {
      if (input == null) throw new IOException("Descriptor resource is missing");
      byte[] bytes = input.readNBytes(16 * 1024 + 1);
      if (bytes.length == 0 || bytes.length > 16 * 1024) {
        throw new IOException("Descriptor resource is empty or oversized");
      }
      RedisScriptDescriptor descriptor = JSON.readValue(bytes, RedisScriptDescriptor.class);
      if (!ownerId.equals(descriptor.owner()) || !scriptId.equals(descriptor.scriptId())) {
        throw new IllegalArgumentException("Descriptor owner or script identity is invalid");
      }
      return descriptor;
    } catch (IOException | RuntimeException failure) {
      throw new IllegalStateException(failureMessage, failure);
    }
  }
}
