package net.firedevops.firemud.gamesession.command.text;

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService.SelectionSnapshot;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manually composed, unregistered retention of original PUBLIC WORLDS UI only. Observed transport
 * metadata is correlation, never authenticated ingress, resume, initial LOGIN or admission proof.
 * Missing/reset/expired state requires fresh WORLDS; there is no reconstruction or local fallback.
 */
public final class DirectTextWorldMenuRedisStore {
  public static final String OWNED_PREFIX = "session:game:direct-text-worlds:v1:";
  public static final String PRINCIPAL = "gamesession_coord_app";
  static final int MAX_OUTER_BYTES = 2 * 1024 * 1024;
  private static final int MAX_INNER_BYTES =
      DirectTextWorldSelectionSnapshotCodec.MAX_PAYLOAD_BYTES;
  private static final int MAX_BASE64_CHARS = 4 * ((MAX_INNER_BYTES + 2) / 3);
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  private static final String SCHEMA = "direct-text-world-menu/v1";
  private static final byte[] EMPTY = new byte[0];
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .build();

  private final DirectTextWorldSelectionService selection;
  private final Clock clock;
  private final Supplier<StatefulRedisConnection<byte[], byte[]>> connections;
  private final byte[] verifiedSource;
  private final String verifiedSha1;

  /**
   * The supplier must own protected Coordination endpoint/credential custody and return a dedicated
   * physical connection with transparent reconnect disabled. WHOAMI verifies its exact principal,
   * not endpoint custody or genuine receiving-chain authority. This class creates no client/bean.
   */
  public DirectTextWorldMenuRedisStore(
      DirectTextWorldSelectionService selection,
      Clock clock,
      Supplier<StatefulRedisConnection<byte[], byte[]>> protectedPinnedCoordinationConnections,
      RedisScriptCatalog installedCatalog) {
    this.selection = Objects.requireNonNull(selection, "selection");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.connections =
        Objects.requireNonNull(protectedPinnedCoordinationConnections, "protected connections");
    this.verifiedSource =
        requireSource(Objects.requireNonNull(installedCatalog, "installed catalog"));
    this.verifiedSha1 = digest("SHA-1", verifiedSource);
  }

  /** Explicit fresh WORLDS discovery; returns its new handle only after an acknowledged CAS. */
  public Optional<SelectionSnapshot> discoverAndReplace(ObservedDirectTextTransport transport) {
    requireTransport(transport);
    var discovered = selection.browse();
    if (discovered.isEmpty()) return Optional.empty();
    var snapshot = discovered.orElseThrow();
    var prepared = prepare(transport, snapshot);
    if (prepared.isEmpty()) return Optional.empty();
    var write = prepared.orElseThrow();
    try (var connection = connections.get()) {
      var commands = requireConnection(connection);
      byte[] key = key(transport);
      byte[] prior = readBounded(commands, key);
      if (prior != null && decode(transport, prior).isEmpty()) return Optional.empty();
      return mutate(commands, key, "REPLACE", prior, write.desired(), write.expiry(), write.now())
          ? discovered
          : Optional.empty();
    }
  }

  /** Reasserts only exact still-present original bytes; loss/reset never recreates an old menu. */
  public boolean reassert(ObservedDirectTextTransport transport, SelectionSnapshot snapshot) {
    requireTransport(transport);
    var prepared = prepare(transport, snapshot);
    if (prepared.isEmpty()) return false;
    var write = prepared.orElseThrow();
    try (var connection = connections.get()) {
      var commands = requireConnection(connection);
      byte[] key = key(transport);
      byte[] prior = readBounded(commands, key);
      if (prior == null || !Arrays.equals(prior, write.desired())) return false;
      // The same nonempty preimage must survive until Lua. An unchanged value is not rewritten,
      // so its original physical deadline/PTTL is retained without extension.
      return mutate(commands, key, "REPLACE", prior, write.desired(), write.expiry(), write.now());
    }
  }

  private Optional<MenuWrite> prepare(
      ObservedDirectTextTransport transport, SelectionSnapshot snapshot) {
    Optional<byte[]> encoded = selection.encodeSnapshot(snapshot);
    if (encoded.isEmpty()) return Optional.empty();
    byte[] inner = encoded.orElseThrow();
    // Source unavailability deliberately escapes separately from invalid UI state.
    if (selection.restoreSnapshot(inner).isEmpty()) return Optional.empty();
    long nowEpochMillis = requirePositiveSafeEpochMillis(clock.millis());
    long expiry = requirePositiveSafeEpochMillis(snapshot.expiresAt().toEpochMilli());
    if (expiry <= nowEpochMillis || expiry - nowEpochMillis > 300_000L) return Optional.empty();
    return Optional.of(new MenuWrite(encode(transport, inner), expiry, nowEpochMillis));
  }

  private record MenuWrite(byte[] desired, long expiry, long now) {}

  /**
   * Restores an original handle only after exact correlation and current complete source checks.
   */
  public Optional<SelectionSnapshot> find(ObservedDirectTextTransport transport) {
    requireTransport(transport);
    Optional<byte[]> inner;
    try (var connection = connections.get()) {
      inner = decode(transport, readBounded(requireConnection(connection), key(transport)));
    }
    return inner.flatMap(selection::restoreSnapshot);
  }

  /** Exact original UI cleanup only; stale sources do not prevent deleting this transport's UI. */
  public boolean clear(ObservedDirectTextTransport transport) {
    requireTransport(transport);
    long nowEpochMillis = requirePositiveSafeEpochMillis(clock.millis());
    try (var connection = connections.get()) {
      var commands = requireConnection(connection);
      byte[] key = key(transport);
      byte[] prior = readBounded(commands, key);
      if (prior != null && decode(transport, prior).isEmpty()) return false;
      return mutate(commands, key, "CLEAR", prior, EMPTY, 0L, nowEpochMillis);
    }
  }

  private boolean mutate(
      RedisCommands<byte[], byte[]> commands,
      byte[] key,
      String operation,
      byte[] prior,
      byte[] desired,
      long expiry,
      long nowEpochMillis) {
    byte[][] keys = {key};
    byte[][] arguments = {
      ascii(operation),
      prior == null ? EMPTY : prior,
      desired,
      ascii(Long.toString(expiry)),
      ascii(Long.toString(nowEpochMillis))
    };
    String result;
    try {
      result = commands.evalsha(verifiedSha1, ScriptOutputType.STATUS, keys, arguments);
    } catch (RedisNoScriptException missing) {
      // Only NOSCRIPT proves the first attempt did not execute. Unknown writes are never retried.
      if (!verifiedSha1.equals(commands.scriptLoad(verifiedSource))) throw unavailable();
      // One retry on this same physical connection; a second NOSCRIPT escapes without looping.
      result = commands.evalsha(verifiedSha1, ScriptOutputType.STATUS, keys, arguments);
    }
    if ("MENU_WRITTEN".equals(result)
        || "MENU_CLEARED".equals(result)
        || "MENU_UNCHANGED".equals(result)) return true;
    if ("PREIMAGE_MISMATCH".equals(result) || "EXPIRED_MENU".equals(result)) return false;
    // INVALID_ARGS, null, unknown results and transport failures never claim a successful write.
    throw unavailable();
  }

  private static RedisCommands<byte[], byte[]> requireConnection(
      StatefulRedisConnection<byte[], byte[]> connection) {
    if (connection == null
        || !connection.isOpen()
        || connection.getOptions() == null
        || connection.getOptions().isAutoReconnect()) throw unavailable();
    var commands = connection.sync();
    if (!PRINCIPAL.equals(commands.aclWhoami())) throw unavailable();
    return commands;
  }

  private static byte[] key(ObservedDirectTextTransport transport) {
    // Tenantless, one key, full-key hashing. No fixed hash tag or multi-key atomicity claim.
    return ascii(OWNED_PREFIX + transport.proxyConnectionId());
  }

  private static long requirePositiveSafeEpochMillis(long value) {
    // Canonical Long.toString bytes are passed unchanged to Lua. This caller-observed clock is
    // UI-expiry bookkeeping only, not authentication, reset, restore or initial-LOGIN proof.
    if (value <= 0L || value > MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException("Positive safe-integer UI epoch milliseconds required");
    }
    return value;
  }

  private static void requireTransport(ObservedDirectTextTransport transport) {
    Objects.requireNonNull(transport, "observed transport");
    // Retain the exact numeric spelling, never parse/reformat it into a different binding. Bound
    // the long-sized decimal correlation before allocating the outer envelope; no signs/coercions.
    if (!transport.transportSessionId().matches("[0-9]{1,19}")) {
      throw new IllegalArgumentException("Bounded numeric transport session correlation required");
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "VA_PRIMITIVE_ARRAY_PASSED_TO_OBJECT_VARARG",
      justification =
          "RedisCommands<byte[], byte[]> takes binary keys; byte[][] supplies exactly one key. Real Redis integration verifies absence, readback and expiry semantics.")
  private static byte[] readBounded(RedisCommands<byte[], byte[]> commands, byte[] key) {
    // GETRANGE bounds the reply before allocation/decoding, even for administrator-seeded garbage.
    byte[] bytes = commands.getrange(key, 0, MAX_OUTER_BYTES);
    if (bytes == null) throw unavailable();
    if (bytes.length == 0 && commands.exists(new byte[][] {key}) == 0L) return null;
    return bytes;
  }

  private static byte[] requireSource(RedisScriptCatalog catalog) {
    var descriptor = catalog.require(DirectTextWorldMenuRedisScriptContribution.SCRIPT_ID);
    var expected = new DirectTextWorldMenuRedisScriptContribution().descriptors().iterator().next();
    if (!expected.equals(descriptor)
        || !DirectTextWorldMenuRedisScriptContribution.RESOURCE_PATH.equals(
            descriptor.resourcePath())
        || !"game-session-service".equals(descriptor.owner())
        || !PRINCIPAL.equals(descriptor.principal())
        || descriptor.role() != RedisScriptDescriptor.RedisRole.COORDINATION) throw unavailable();
    try (InputStream input =
        DirectTextWorldMenuRedisStore.class
            .getClassLoader()
            .getResourceAsStream(descriptor.resourcePath())) {
      if (input == null) throw unavailable();
      byte[] source = input.readNBytes(32 * 1024 + 1);
      if (source.length == 0
          || source.length > 32 * 1024
          || !digest("SHA-256", source).equals(descriptor.sha256())) throw unavailable();
      return source;
    } catch (IOException failure) {
      throw new IllegalStateException(
          "Public world menu Coordination store is unavailable", failure);
    }
  }

  private static byte[] encode(ObservedDirectTextTransport transport, byte[] inner) {
    if (inner.length == 0 || inner.length > MAX_INNER_BYTES) throw unavailable();
    byte[] bytes =
        JSON.writeValueAsBytes(
            new Envelope(
                SCHEMA,
                transport.proxyConnectionId().toString(),
                transport.transportSessionId(),
                Base64.getEncoder().encodeToString(inner)));
    if (bytes.length > MAX_OUTER_BYTES) throw unavailable();
    return bytes;
  }

  private static Optional<byte[]> decode(ObservedDirectTextTransport transport, byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_OUTER_BYTES)
      return Optional.empty();
    try {
      var envelope = JSON.readValue(bytes, Envelope.class);
      if (envelope == null
          || !SCHEMA.equals(envelope.schemaVersion())
          || !transport
              .proxyConnectionId()
              .toString()
              .equals(envelope.originalProxyConnectionUuid())
          || !transport.transportSessionId().equals(envelope.transportSessionId())
          || envelope.encodedOriginalMenu() == null
          || envelope.encodedOriginalMenu().isEmpty()
          || envelope.encodedOriginalMenu().length() > MAX_BASE64_CHARS) return Optional.empty();
      byte[] inner = Base64.getDecoder().decode(envelope.encodedOriginalMenu());
      if (inner.length == 0
          || inner.length > MAX_INNER_BYTES
          || !Arrays.equals(bytes, encode(transport, inner))) return Optional.empty();
      return Optional.of(inner);
    } catch (JacksonException | IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  private record Envelope(
      String schemaVersion,
      String originalProxyConnectionUuid,
      String transportSessionId,
      String encodedOriginalMenu) {}

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static String digest(String algorithm, byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Public world menu Coordination store is unavailable");
  }
}
