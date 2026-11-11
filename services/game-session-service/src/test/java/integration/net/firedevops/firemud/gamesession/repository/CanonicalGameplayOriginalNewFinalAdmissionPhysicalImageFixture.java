package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.api.sync.RedisCommands;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

/** Exact physical bytes without Redis DUMP's unspecified hash/set iteration order. */
final class CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture {
  private CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture() {}

  static byte[] image(RedisCommands<byte[], byte[]> commands, byte[] key) {
    var output = new ByteArrayOutputStream();
    String type = commands.type(key);
    append(output, type.getBytes(StandardCharsets.US_ASCII));
    long ttl = commands.pttl(key);
    // Persistent/missing keys stay exact. Expiring fault fixtures retain a positive-TTL marker;
    // their separate assertions check expiry without comparing a naturally decreasing duration.
    append(output, Long.toString(ttl > 0 ? 1 : ttl).getBytes(StandardCharsets.US_ASCII));
    switch (type) {
      case "none" -> {}
      case "string" -> append(output, commands.get(key));
      case "hash" -> {
        var fields = new ArrayList<>(commands.hgetall(key).entrySet());
        fields.sort((left, right) -> Arrays.compareUnsigned(left.getKey(), right.getKey()));
        for (Map.Entry<byte[], byte[]> field : fields) {
          append(output, field.getKey());
          append(output, field.getValue());
        }
      }
      case "set" -> {
        var members = new ArrayList<>(commands.smembers(key));
        members.sort(Arrays::compareUnsigned);
        for (byte[] member : members) append(output, member);
      }
      default ->
          throw new IllegalStateException("Unexpected final-admission fixture key type: " + type);
    }
    return output.toByteArray();
  }

  private static void append(ByteArrayOutputStream output, byte[] value) {
    output.writeBytes(Integer.toString(value.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(value);
  }
}
