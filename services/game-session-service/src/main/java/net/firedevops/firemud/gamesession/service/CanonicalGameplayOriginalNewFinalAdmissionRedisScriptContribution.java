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

/** Passive descriptor contribution for the manual original-NEW final session CAS. */
public final class CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution
    implements RedisScriptContribution {
  public static final String SCRIPT_ID = "game-session.original-new-final-admission.v1";
  public static final String RESOURCE_PATH =
      "redis/lua/game-session-original-new-final-admission.lua";
  private static final String DESCRIPTOR_PATH =
      "/redis/scripts/game-session-original-new-final-admission.json";
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
      try (InputStream receiptInput =
          getClass()
              .getResourceAsStream("/redis/scripts/game-session-region-transfer-receipt.json")) {
        if (receiptInput == null) throw unavailable();
        byte[] receiptBytes = receiptInput.readNBytes(16 * 1024 + 1);
        if (receiptBytes.length == 0 || receiptBytes.length > 16 * 1024) throw unavailable();
        RedisScriptDescriptor receipt = JSON.readValue(receiptBytes, RedisScriptDescriptor.class);
        if (!ownerId().equals(receipt.owner())
            || !"game-session.region-transfer-receipt.v1".equals(receipt.scriptId()))
          throw unavailable();
        return List.of(descriptor, receipt);
      }
    } catch (IOException | RuntimeException failure) {
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "Original NEW final admission Redis descriptor is unavailable");
  }
}
