package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Manually composed physical custody owner. No result here is an execution outcome. */
final class CanonicalGameplayCapacityOriginRedisOwner {
  enum Action {
    INIT,
    DISPATCH,
    CLOSE_CUSTODY,
    DENY,
    TRANSFER
  }

  private final Supplier<StatefulRedisConnection<byte[], byte[]>> admittedConnection;
  private final LongSupplier remainingMillis;
  private final byte[] script;

  /** Exact script digest must come from the separately verified passive descriptor. */
  CanonicalGameplayCapacityOriginRedisOwner(
      Supplier<StatefulRedisConnection<byte[], byte[]>> admittedConnection,
      LongSupplier remainingMillis,
      byte[] script,
      String descriptorSha256) {
    this.admittedConnection = Objects.requireNonNull(admittedConnection);
    this.remainingMillis = Objects.requireNonNull(remainingMillis);
    this.script = Objects.requireNonNull(script).clone();
    if (!OriginalNewCapacityExecution.sha256(this.script).equals(descriptorSha256))
      throw unavailable();
  }

  /**
   * A lost result remains indeterminate. A later admitted retry must use the retained SQL claim.
   */
  Readback apply(
      CanonicalGameplayCapacityOrigin origin,
      Action action,
      String oldOwner,
      long oldFence,
      String newOwner,
      long newFence,
      CanonicalGameplayCapacityOrigin.State expectedState,
      long absoluteDeadlineMillis) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    if (oldFence <= 0 || newFence <= 0 || oldOwner == null || newOwner == null) throw unavailable();
    remaining();
    var connection = admittedConnection.get();
    try {
      var commands = connection.async();
      if (!"gamesession_coord_app".equals(await(commands::aclWhoami))) throw unavailable();
      String sha = await(() -> commands.scriptLoad(script));
      String localSha;
      try {
        localSha =
            java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(script));
      } catch (java.security.NoSuchAlgorithmException impossible) {
        throw new IllegalStateException(impossible);
      }
      if (!localSha.equals(sha)) throw unavailable();
      Long result =
          await(
              () ->
                  commands.evalsha(
                      sha,
                      ScriptOutputType.INTEGER,
                      new byte[][] {bytes(origin.redisKey())},
                      bytes(action.name()),
                      bytes(origin.digest()),
                      bytes(oldOwner),
                      bytes(Long.toString(oldFence)),
                      bytes(newOwner),
                      bytes(Long.toString(newFence)),
                      bytes(expectedState.name()),
                      bytes(Long.toString(absoluteDeadlineMillis))));
      if (result == null || (result != 1L && result != 2L)) throw unavailable();
      return readPersistentMarker(commands, origin, newOwner, newFence, expectedState);
    } finally {
      connection.closeAsync();
    }
  }

  /** Exact readback only; never repairs a missing or expired marker. */
  Readback readExact(
      CanonicalGameplayCapacityOrigin origin,
      String owner,
      long fence,
      CanonicalGameplayCapacityOrigin.State state) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    remaining();
    var connection = admittedConnection.get();
    try {
      return readExact(connection.async(), origin, owner, fence, state);
    } finally {
      connection.closeAsync();
    }
  }

  Readback readExact(
      RedisAsyncCommands<byte[], byte[]> commands,
      CanonicalGameplayCapacityOrigin origin,
      String owner,
      long fence,
      CanonicalGameplayCapacityOrigin.State state) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    if (!"gamesession_coord_app".equals(await(commands::aclWhoami))) throw unavailable();
    return readPersistentMarker(commands, origin, owner, fence, state);
  }

  private Readback readPersistentMarker(
      RedisAsyncCommands<byte[], byte[]> commands,
      CanonicalGameplayCapacityOrigin origin,
      String owner,
      long fence,
      CanonicalGameplayCapacityOrigin.State state) {
    byte[] key = bytes(origin.redisKey());
    if (await(() -> commands.pttl(key)) != -1L) throw unavailable();
    Map<byte[], byte[]> raw = await(() -> commands.hgetall(key));
    var fields = new java.util.HashMap<String, String>();
    for (var entry : raw.entrySet())
      if (fields.put(
              new String(entry.getKey(), StandardCharsets.US_ASCII),
              new String(entry.getValue(), StandardCharsets.US_ASCII))
          != null) throw unavailable();
    if (!fields.equals(
        Map.of(
            "origin", origin.digest(),
            "owner", owner,
            "fence", Long.toString(fence),
            "state", state.name()))) throw unavailable();
    return new Readback(origin.digest(), owner, fence, state);
  }

  private long remaining() {
    long n = remainingMillis.getAsLong();
    if (n <= 0) throw unavailable();
    return n;
  }

  private <T> T await(Supplier<RedisFuture<T>> operation) {
    long before = remaining();
    try {
      T result = operation.get().get(Math.min(before, remaining()), TimeUnit.MILLISECONDS);
      remaining();
      return result;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException failure) {
      throw unavailable();
    }
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }

  record Readback(
      String originDigest, String owner, long fence, CanonicalGameplayCapacityOrigin.State state) {}

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Exact physical capacity origin custody unavailable");
  }
}
