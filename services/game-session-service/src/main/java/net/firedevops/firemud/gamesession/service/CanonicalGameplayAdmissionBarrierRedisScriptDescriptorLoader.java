package net.firedevops.firemud.gamesession.service;

import java.io.IOException;
import java.io.InputStream;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bounded strict descriptor reader for the manual Account INITIAL and issuer admission barriers.
 */
final class CanonicalGameplayAdmissionBarrierRedisScriptDescriptorLoader {
  private static final int MAX_DESCRIPTOR_BYTES = 16 * 1024;
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private CanonicalGameplayAdmissionBarrierRedisScriptDescriptorLoader() {}

  static RedisScriptDescriptor read(String path, String scriptId, String unavailableMessage) {
    try (InputStream input =
        CanonicalGameplayAdmissionBarrierRedisScriptDescriptorLoader.class.getResourceAsStream(
            path)) {
      if (input == null) throw new IllegalStateException(unavailableMessage);
      byte[] bytes = input.readNBytes(MAX_DESCRIPTOR_BYTES + 1);
      if (bytes.length == 0 || bytes.length > MAX_DESCRIPTOR_BYTES) {
        throw new IllegalStateException(unavailableMessage);
      }
      RedisScriptDescriptor descriptor = JSON.readValue(bytes, RedisScriptDescriptor.class);
      if (!"game-session-service".equals(descriptor.owner())
          || !scriptId.equals(descriptor.scriptId())) {
        throw new IllegalStateException(unavailableMessage);
      }
      return descriptor;
    } catch (IOException | RuntimeException failure) {
      throw new IllegalStateException(unavailableMessage, failure);
    }
  }
}
