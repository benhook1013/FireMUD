package net.firedevops.firemud.accountservice.service.auth;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;

/**
 * Owner-local controlled standalone store. A supplied dedicated connection is not proof of
 * production endpoint custody. No ambient Redis factory, topology discovery fallback or retry is
 * used.
 */
public final class AccountCredentialAbuseRedisStore
    implements AccountCredentialAbuseService.Backend {
  private static final String PREFIX = AccountCredentialAbuseSubjectKeys.PREFIX;
  private static final String IDENTIFIER = "([a-z0-9][a-z0-9_-]{0,63})";
  private static final Pattern SUBJECT =
      Pattern.compile(
          Pattern.quote(PREFIX)
              + "subject:"
              + IDENTIFIER
              + ":(source|candidate):rsh-v2-"
              + IDENTIFIER
              + "-([0-9a-f]{64}):([0-9]{1,16})");
  private static final Pattern PRESSURE =
      Pattern.compile(
          Pattern.quote(PREFIX) + "pressure:" + IDENTIFIER + ":" + IDENTIFIER + ":([0-9]{1,16})");
  private final StatefulRedisConnection<byte[], byte[]> connection;
  private final RedisScriptCatalog catalog;
  private final LongSupplier monotonicNanos;
  private final ReentrantLock invocation = new ReentrantLock();
  private final AtomicBoolean uncertain = new AtomicBoolean();

  /**
   * The composition must supply a private connection and retain its lifecycle, never an ambient
   * one.
   */
  public AccountCredentialAbuseRedisStore(StatefulRedisConnection<byte[], byte[]> connection) {
    this(connection, System::nanoTime);
  }

  /** Package-local monotonic clock seam for deterministic deadline tests, not policy input. */
  AccountCredentialAbuseRedisStore(
      StatefulRedisConnection<byte[], byte[]> connection, LongSupplier monotonicNanos) {
    this.monotonicNanos = Objects.requireNonNull(monotonicNanos);
    this.connection =
        Objects.requireNonNull(connection, "Explicit credential-abuse connection required");
    this.catalog =
        RedisScriptCatalog.fromContributions(
            List.of(new AccountCredentialAbuseRedisScriptContribution()));
  }

  @Override
  public AccountCredentialAbusePolicy.Signals admit(
      AccountCredentialAbusePolicy policy,
      AccountCredentialAbuseService.Buckets buckets,
      Instant now) {
    return execute(policy, buckets, now, false);
  }

  @Override
  public boolean recordFailure(
      AccountCredentialAbusePolicy policy,
      AccountCredentialAbuseService.Buckets buckets,
      Instant now) {
    return execute(policy, buckets, now, true) != null;
  }

  private AccountCredentialAbusePolicy.Signals execute(
      AccountCredentialAbusePolicy policy,
      AccountCredentialAbuseService.Buckets buckets,
      Instant now,
      boolean failure) {
    boolean locked = false;
    Attempt attempt = null;
    try {
      validate(policy, buckets, now);
      attempt = new Attempt(policy.decisionTimeout().toNanos());
      locked = invocation.tryLock(attempt.remainingNanos(), TimeUnit.NANOSECONDS);
      if (!locked
          || uncertain.get()
          || !connection.isOpen()
          || connection.getOptions() == null
          || connection.getOptions().isAutoReconnect()) return null;
      attempt.remainingNanos();
      attempt.commands = connection.async();
      attempt.remainingNanos();
      String principal = await(attempt.commands::aclWhoami, attempt, true);
      if (!AccountCredentialAbuseRedisScriptContribution.PRINCIPAL.equals(principal)) return null;
      long[] pressure =
          script(
              AccountCredentialAbuseRedisScriptContribution.ADMISSION_ID,
              buckets.pressure(),
              "pressure-v1",
              policy,
              buckets,
              now,
              failure ? "FAIL" : "ADMIT",
              attempt);
      if (pressure == null) return null;
      long[] source =
          script(
              AccountCredentialAbuseRedisScriptContribution.BUCKET_ID,
              buckets.source().redisKey(),
              buckets.source().collisionWitness(),
              policy,
              buckets,
              now,
              failure ? "FAIL" : "READ",
              attempt);
      if (source == null) return null;
      long[] candidate =
          script(
              AccountCredentialAbuseRedisScriptContribution.BUCKET_ID,
              buckets.candidate().redisKey(),
              buckets.candidate().collisionWitness(),
              policy,
              buckets,
              now,
              failure ? "FAIL" : "READ",
              attempt);
      if (candidate == null || source[1] != 0 || candidate[1] != 0) return null;
      var signals = new AccountCredentialAbusePolicy.Signals(source[0], candidate[0], pressure[1]);
      attempt.remainingNanos();
      return signals;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      if (attempt != null && attempt.inFlight) uncertain.set(true);
      return null;
    } catch (Exception unavailable) {
      // Fence a dispatched command with an unknown outcome, never a queued/pre-dispatch refusal.
      if (attempt != null && attempt.inFlight) uncertain.set(true);
      return null;
    } finally {
      if (locked) invocation.unlock();
    }
  }

  private long[] script(
      String id,
      String key,
      String witness,
      AccountCredentialAbusePolicy policy,
      AccountCredentialAbuseService.Buckets buckets,
      Instant now,
      String operation,
      Attempt attempt)
      throws Exception {
    RedisScriptDescriptor descriptor = catalog.require(id);
    byte[] source;
    try (InputStream input =
        getClass().getClassLoader().getResourceAsStream(descriptor.resourcePath())) {
      if (input == null) return null;
      source = input.readNBytes(32 * 1024 + 1);
    }
    if (source.length == 0
        || source.length > 32 * 1024
        || !digest("SHA-256", source).equals(descriptor.sha256())) return null;
    String sha = await(() -> attempt.commands.scriptLoad(source), attempt, true);
    if (!digest("SHA-1", source).equals(sha)) return null;
    byte[][] arguments = {
      ascii(now.toEpochMilli()),
      ascii(buckets.expiryEpochMillis()),
      ascii(policy.maximumSignalSkew().toMillis()),
      // Every retained-window and pressure key consumes this conservative real-key ceiling.
      ascii(policy.maximumActiveSubjects()),
      ascii(policy.maximumMemoryBytes()),
      ascii(policy.maximumBucketMemoryBytes()),
      ascii(policy.maximumCounter()),
      ascii(policy.maximumAdmissionsPerWindow()),
      ascii(operation),
      ascii(witness),
      ascii(policy.window().toMillis()),
      ascii(policy.timeToLive().toMillis())
    };
    List<Object> result =
        await(
            () ->
                attempt.commands.evalsha(
                    sha, ScriptOutputType.MULTI, new byte[][] {ascii(key)}, arguments),
            attempt);
    return decode(result, policy.maximumCounter());
  }

  static long[] decode(List<Object> result, long maximumCounter) {
    if (result == null
        || result.size() != 3
        || !(result.get(0) instanceof byte[] code)
        || code.length > 32
        || !(result.get(1) instanceof Long first)
        || !(result.get(2) instanceof Long second)
        || first < 0
        || second < 0
        || first > maximumCounter
        || second > maximumCounter) return null;
    String outcome = new String(code, StandardCharsets.US_ASCII);
    return switch (outcome) {
      case "APPLIED", "OBSERVED" -> new long[] {first, second};
      case "UNAVAILABLE", "CLEANED_UNAVAILABLE", "ACCOUNTED_UNAVAILABLE" -> null;
      default -> null;
    };
  }

  private static void validate(
      AccountCredentialAbusePolicy policy,
      AccountCredentialAbuseService.Buckets buckets,
      Instant now) {
    Objects.requireNonNull(policy);
    Objects.requireNonNull(buckets);
    if (!policy.isEffectiveAt(now)) throw invalid();
    var source = SUBJECT.matcher(buckets.source().redisKey());
    var candidate = SUBJECT.matcher(buckets.candidate().redisKey());
    var pressure = PRESSURE.matcher(buckets.pressure());
    if (!source.matches()
        || !candidate.matches()
        || !pressure.matches()
        || !source.group(1).equals(policy.scope())
        || !candidate.group(1).equals(policy.scope())
        || !pressure.group(1).equals(policy.scope())
        || !source.group(2).equals("source")
        || !candidate.group(2).equals("candidate")
        || !source.group(3).equals(candidate.group(3))
        || !source.group(3).equals(pressure.group(2))
        || !source.group(5).equals(candidate.group(5))
        || !source.group(5).equals(pressure.group(3))) throw invalid();
    long window = Long.parseLong(source.group(5));
    if (!Long.toString(window).equals(source.group(5))
        || policy.expiryEpochMillis(window) != buckets.expiryEpochMillis()
        || now.toEpochMilli() >= buckets.expiryEpochMillis()) throw invalid();
    String witnessPrefix = "wsh-v2-" + source.group(3) + "-";
    for (var subject : List.of(buckets.source(), buckets.candidate())) {
      if (!subject.collisionWitness().matches(Pattern.quote(witnessPrefix) + "[0-9a-f]{64}"))
        throw invalid();
    }
  }

  private static <T> T await(Supplier<RedisFuture<T>> dispatch, Attempt attempt) throws Exception {
    return await(dispatch, attempt, false);
  }

  /** A server error reply proves completion only for commands that cannot change bucket state. */
  private static <T> T await(
      Supplier<RedisFuture<T>> dispatch, Attempt attempt, boolean nonMutating) throws Exception {
    // Defer command dispatch until this check; the async accessor is checked separately.
    attempt.remainingNanos();
    attempt.inFlight = true;
    RedisFuture<T> future = dispatch.get();
    try {
      T result = future.get(attempt.remainingNanos(), TimeUnit.NANOSECONDS);
      // get(timeout) may return after the deadline when completion wins the timeout race.
      attempt.remainingNanos();
      attempt.inFlight = false;
      return result;
    } catch (Exception failure) {
      if (nonMutating && isCompletedRedisError(future, failure)) attempt.inFlight = false;
      try {
        future.cancel(false);
      } catch (RuntimeException cancellationFailure) {
        // Cancellation cannot erase the original interruption or uncertain dispatch boundary.
      }
      throw failure;
    }
  }

  private static boolean isCompletedRedisError(RedisFuture<?> future, Exception failure) {
    return failure instanceof ExecutionException executionFailure
        && executionFailure.getCause() instanceof RedisCommandExecutionException
        && future.isDone();
  }

  private final class Attempt {
    private final long started = monotonicNanos.getAsLong();
    private final long budget;
    private boolean inFlight;
    private RedisAsyncCommands<byte[], byte[]> commands;

    private Attempt(long budget) {
      this.budget = budget;
    }

    private long remainingNanos() throws TimeoutException, InterruptedException {
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedException("Credential-abuse decision interrupted");
      long elapsed = monotonicNanos.getAsLong() - started;
      if (elapsed < 0 || elapsed >= budget)
        throw new TimeoutException("Credential-abuse decision expired");
      return budget - elapsed;
    }
  }

  private static byte[] ascii(long value) {
    return ascii(Long.toString(value));
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static String digest(String algorithm, byte[] source) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(source));
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Credential-abuse store input is unavailable");
  }
}
