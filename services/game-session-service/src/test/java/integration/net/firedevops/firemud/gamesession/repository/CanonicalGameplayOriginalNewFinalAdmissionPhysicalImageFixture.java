package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Exact physical bytes without Redis DUMP's unspecified hash/set iteration order. */
final class CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture {
  private CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture() {}

  private static final String READ_IMAGE =
      """
      local kind = redis.call('TYPE', KEYS[1]).ok
      local expiry = redis.call('PEXPIRETIME', KEYS[1])
      if kind == 'string' then
        return {kind, expiry, redis.call('GET', KEYS[1])}
      elseif kind == 'hash' then
        return {kind, expiry, redis.call('HGETALL', KEYS[1])}
      elseif kind == 'set' then
        return {kind, expiry, redis.call('SMEMBERS', KEYS[1])}
      end
      return {kind, expiry}
      """;

  static byte[] image(RedisCommands<byte[], byte[]> commands, byte[] key) {
    List<Object> snapshot = commands.eval(READ_IMAGE, ScriptOutputType.MULTI, new byte[][] {key});
    return encodeSnapshot(snapshot);
  }

  static byte[] encodeSnapshot(List<Object> snapshot) {
    var output = new ByteArrayOutputStream();
    byte[] rawType = (byte[]) snapshot.get(0);
    String type = new String(rawType, StandardCharsets.US_ASCII);
    append(output, rawType);
    append(output, Long.toString((Long) snapshot.get(1)).getBytes(StandardCharsets.US_ASCII));
    switch (type) {
      case "none" -> {}
      case "string" -> append(output, (byte[]) snapshot.get(2));
      case "hash" -> {
        var raw = (List<?>) snapshot.get(2);
        var fields = new ArrayList<Map.Entry<byte[], byte[]>>();
        for (int i = 0; i < raw.size(); i += 2) {
          fields.add(Map.entry((byte[]) raw.get(i), (byte[]) raw.get(i + 1)));
        }
        fields.sort((left, right) -> Arrays.compareUnsigned(left.getKey(), right.getKey()));
        for (Map.Entry<byte[], byte[]> field : fields) {
          append(output, field.getKey());
          append(output, field.getValue());
        }
      }
      case "set" -> {
        var members = new ArrayList<byte[]>();
        for (Object member : (List<?>) snapshot.get(2)) members.add((byte[]) member);
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
