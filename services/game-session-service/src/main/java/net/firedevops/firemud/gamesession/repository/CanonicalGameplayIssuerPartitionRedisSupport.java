package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared strict mechanics for the manual issuer partition lifecycle owners. */
final class CanonicalGameplayIssuerPartitionRedisSupport {
  static final String KEY_PREFIX = "session:game:index:issuer:";
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final int MAX_SCRIPT_BYTES = 128 * 1024;
  private static final long COMMAND_TIMEOUT_MILLIS = 5_000;

  private final BiFunction<String, Throwable, IllegalStateException> unavailable;

  CanonicalGameplayIssuerPartitionRedisSupport(
      BiFunction<String, Throwable, IllegalStateException> unavailable) {
    this.unavailable = Objects.requireNonNull(unavailable, "Owner error factory is required");
  }

  boolean matchesCurrentState(
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current,
      CanonicalGameplayIssuerInstalledAnchor anchor) {
    return current != null
        && sameCheckpoint(current.accountCheckpoint(), anchor)
        && Arrays.equals(
            CanonicalGameplayIssuerInstalledAnchor.encodeIncarnation(current.incarnation()),
            anchor.incarnationBytes());
  }

  private static boolean sameCheckpoint(
      CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint current,
      CanonicalGameplayIssuerInstalledAnchor anchor) {
    return current != null
        && current.issuerId().equals(anchor.issuerId())
        && current.issuerAuthGeneration().equals(anchor.issuerAuthGeneration())
        && Arrays.equals(current.exactCheckpointBytes(), anchor.sourceCheckpointBytes())
        && current.checkpointDigest().equals(anchor.sourceCheckpointDigest())
        && Arrays.equals(current.latestIssuerEventBytes(), anchor.sourceEventBytes())
        && Objects.equals(current.latestIssuerEventDigest(), anchor.sourceEventDigest());
  }

  boolean matchesDescriptor(
      RedisScriptDescriptor descriptor,
      String scriptId,
      String resourcePath,
      List<RedisScriptDescriptor.KeySpec> keys,
      List<RedisScriptDescriptor.ArgumentSpec> arguments,
      List<RedisScriptDescriptor.OutcomeSpec> outcomes) {
    return scriptId.equals(descriptor.scriptId())
        && resourcePath.equals(descriptor.resourcePath())
        && "game-session-service".equals(descriptor.owner())
        && PRINCIPAL.equals(descriptor.principal())
        && descriptor.role() == RedisScriptDescriptor.RedisRole.COORDINATION
        && keys.equals(descriptor.keys())
        && arguments.equals(descriptor.arguments())
        && descriptor.category() == RedisScriptDescriptor.ScriptCategory.SESSION_ISSUER_INDEX
        && outcomes.equals(descriptor.outcomes())
        && descriptor.resetSensitivity() == RedisScriptDescriptor.ResetSensitivity.CLUSTER
        && descriptor.lossClass() == RedisScriptDescriptor.LossClass.EMPTY_REDIS_COLD_START_OR_RESET
        && descriptor.compatibilityLevel()
            == RedisScriptDescriptor.CompatibilityLevel.REQUIRES_CLUSTER_RESET
        && descriptor
            .supportedCoexistence()
            .equals(List.of(new RedisScriptDescriptor.SupportedCoexistence("v1", "v2")))
        && descriptor.legacyPayloadShape() == null;
  }

  byte[] scriptSource(RedisScriptDescriptor descriptor) {
    try (InputStream input =
        CanonicalGameplayIssuerPartitionRedisSupport.class
            .getClassLoader()
            .getResourceAsStream(descriptor.resourcePath())) {
      if (input == null) throw failure("Issuer Lua resource is missing", null);
      byte[] bytes = input.readNBytes(MAX_SCRIPT_BYTES + 1);
      if (bytes.length == 0
          || bytes.length > MAX_SCRIPT_BYTES
          || !descriptor.sha256().equals(digest("SHA-256", bytes))) {
        throw failure("Issuer Lua differs from its exact descriptor digest", null);
      }
      return bytes;
    } catch (IOException failure) {
      throw failure("Issuer Lua resource could not be read", failure);
    }
  }

  long writeOneKey(RedisClient client, byte[] script, byte[] key, byte[][] arguments) {
    try (StatefulRedisConnection<byte[], byte[]> connection =
        client.connect(ByteArrayCodec.INSTANCE)) {
      RedisAsyncCommands<byte[], byte[]> commands = authenticatedCommands(connection);
      String sha = await(commands.scriptLoad(script));
      if (!digest("SHA-1", script).equals(sha))
        throw failure("Redis loaded a different script", null);
      Long reply =
          await(commands.evalsha(sha, ScriptOutputType.INTEGER, new byte[][] {key}, arguments));
      if (reply == null) throw failure("Redis returned no issuer mutation result", null);
      return reply;
    }
  }

  /** Returns Base64 field keys so distinct byte fields cannot be collapsed by text decoding. */
  Map<String, byte[]> readBoundedHash(RedisAsyncCommands<byte[], byte[]> commands, byte[] key) {
    if (!"hash".equals(await(commands.type(key)))
        || !Long.valueOf(-1L).equals(await(commands.pttl(key)))) {
      throw failure("Issuer partition type or TTL is not canonical", null);
    }
    Long count = await(commands.hlen(key));
    if (count == null
        || count < 1
        || count > CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_LOCAL_HASH_FIELDS) {
      throw failure("Issuer partition field count is outside its strict bound", null);
    }
    Map<byte[], byte[]> raw = await(commands.hgetall(key));
    if (raw == null || raw.size() != count)
      throw failure("Issuer partition readback is incomplete", null);
    Map<String, byte[]> actual = new HashMap<>();
    long bytes = 0;
    for (Map.Entry<byte[], byte[]> field : raw.entrySet()) {
      byte[] name = Objects.requireNonNull(field.getKey(), "Redis field");
      byte[] value = Objects.requireNonNull(field.getValue(), "Redis value");
      bytes = Math.addExact(bytes, name.length + (long) value.length);
      if (bytes > CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_TOTAL_HASH_BYTES) {
        throw failure("Issuer partition readback exceeds its bounded bytes", null);
      }
      if (actual.putIfAbsent(b64(name), value.clone()) != null) {
        throw failure("Issuer partition readback repeats a physical field", null);
      }
    }
    return actual;
  }

  Map<String, byte[]> decodePhysicalMap(Map<String, byte[]> encoded) {
    Map<String, byte[]> result = new HashMap<>();
    encoded.forEach((field, value) -> result.put(decodeField(field), value.clone()));
    return result;
  }

  String decodeField(String encoded) {
    return decodeUtf8(Base64.getDecoder().decode(encoded));
  }

  private String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw failure("Issuer partition contains a non-UTF-8 field", malformed);
    }
  }

  RedisAsyncCommands<byte[], byte[]> authenticatedCommands(
      StatefulRedisConnection<byte[], byte[]> connection) {
    if (connection == null || !connection.isOpen())
      throw failure("Redis connection is closed", null);
    RedisAsyncCommands<byte[], byte[]> commands = connection.async();
    requirePrincipal(await(commands.aclWhoami()));
    return commands;
  }

  private void requirePrincipal(String principal) {
    if (!PRINCIPAL.equals(principal))
      throw failure("Redis ACL principal is not the exact owner", null);
  }

  private <T> T await(RedisFuture<T> future) {
    try {
      return future.get(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw failure("Redis command was interrupted", interrupted);
    } catch (ExecutionException | TimeoutException | RuntimeException failure) {
      future.cancel(true);
      throw failure("Redis command did not return exact proof", failure);
    }
  }

  void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw failure("Issuer Redis operation cannot run inside an ambient SQL transaction", null);
  }

  private String digest(String algorithm, byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(value));
    } catch (Exception failure) {
      throw failure("Could not verify issuer Lua digest", failure);
    }
  }

  static String b64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private IllegalStateException failure(String message, Throwable cause) {
    return unavailable.apply(message, cause);
  }
}
