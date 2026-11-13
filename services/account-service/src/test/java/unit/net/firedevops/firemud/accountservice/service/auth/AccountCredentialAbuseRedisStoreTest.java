package net.firedevops.firemud.accountservice.service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import org.junit.jupiter.api.Test;

class AccountCredentialAbuseRedisStoreTest {
  @Test
  void decodesOnlyClosedBoundedScriptOutcomes() {
    assertThat(AccountCredentialAbuseRedisStore.decode(List.of(bytes("APPLIED"), 2L, 3L), 100))
        .containsExactly(2, 3);
    assertThat(AccountCredentialAbuseRedisStore.decode(List.of(bytes("OBSERVED"), 0L, 0L), 100))
        .containsExactly(0, 0);
    for (String refusal :
        List.of("UNAVAILABLE", "CLEANED_UNAVAILABLE", "ACCOUNTED_UNAVAILABLE", "UNKNOWN")) {
      assertThat(AccountCredentialAbuseRedisStore.decode(List.of(bytes(refusal), 0L, 0L), 100))
          .isNull();
    }
    for (List<Object> malformed :
        Arrays.<List<Object>>asList(
            null,
            List.of(),
            List.of(bytes("APPLIED"), -1L, 0L),
            List.of(bytes("APPLIED"), 101L, 0L),
            List.of(bytes("APPLIED"), 1, 0L),
            List.of("APPLIED", 0L, 0L))) {
      assertThat(AccountCredentialAbuseRedisStore.decode(malformed, 100)).isNull();
    }
  }

  @Test
  void descriptorsPinTwoSingleKeyCacheScriptsWithoutActivation() throws Exception {
    var descriptors = new AccountCredentialAbuseRedisScriptContribution().descriptors();
    assertThat(descriptors).hasSize(2);
    for (var descriptor : descriptors) {
      assertThat(descriptor.role()).isEqualTo(RedisScriptDescriptor.RedisRole.CACHE_RATE_LIMIT);
      assertThat(descriptor.keys()).hasSize(1);
      assertThat(descriptor.keys().getFirst().hashTagDeclaration())
          .isEqualTo(RedisScriptDescriptor.HashTagDeclaration.NOT_REQUIRED);
      assertThat(descriptor.principal()).isEqualTo("account_cache_app");
      byte[] content;
      try (var input = getClass().getClassLoader().getResourceAsStream(descriptor.resourcePath())) {
        assertThat(input).isNotNull();
        content = input.readNBytes(32 * 1024 + 1);
      }
      assertThat(content.length).isLessThanOrEqualTo(32 * 1024);
      assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)))
          .isEqualTo(descriptor.sha256());
      String script = new String(content, StandardCharsets.UTF_8);
      assertThat(script).startsWith("#!lua flags=no-cluster\n");
      assertThat(script)
          .contains(
              "redis.call('DBSIZE')",
              "redis.acl_check_cmd('PEXPIRETIME', KEYS[1])",
              "'MEMORY', 'USAGE'",
              "'PXAT'");
      assertThat(script).doesNotContain("KEYS[2]", "redis.call('ACL'", "allow-oom");
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void refusesClosedAndReconnectingConnectionsWithoutDispatch() {
    var open = new AtomicBoolean();
    var reconnecting = ClientOptions.builder().autoReconnect(true).build();
    StatefulRedisConnection<byte[], byte[]> connection =
        (StatefulRedisConnection<byte[], byte[]>)
            Proxy.newProxyInstance(
                StatefulRedisConnection.class.getClassLoader(),
                new Class<?>[] {StatefulRedisConnection.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "isOpen" -> open.get();
                      case "getOptions" -> reconnecting;
                      default ->
                          throw new AssertionError(
                              "Unexpected connection interaction: " + method.getName());
                    });
    var store = new AccountCredentialAbuseRedisStore(connection);
    try (var key = new AccountCredentialAbuseSubjectKeys.DigestKey("test-key", new byte[32])) {
      var policy = AccountCredentialAbuseServiceTest.policy();
      Instant now = policy.validFrom();
      var keys = new AccountCredentialAbuseSubjectKeys(policy.scope(), key);
      long window = now.toEpochMilli() / policy.window().toMillis();
      var buckets =
          new AccountCredentialAbuseService.Buckets(
              keys.source("192.0.2.1", window),
              keys.candidate("unknown@example.test", window),
              keys.pressure(window),
              policy.expiryEpochMillis(window));
      assertThat(store.admit(policy, buckets, now)).isNull();
      open.set(true);
      assertThat(store.admit(policy, buckets, now)).isNull();
    }
  }

  @Test
  void successfulAsyncSequenceVerifiesScriptsAndReturnsSharedSignals() {
    var fixture = new AsyncFixture();
    var store = fixture.store();
    assertThat(store.admit(fixture.policy, fixture.buckets, fixture.now))
        .isEqualTo(new AccountCredentialAbusePolicy.Signals(3, 4, 2));
    assertThat(fixture.calls)
        .containsExactly("WHOAMI", "LOAD", "EVAL:ADMIT", "LOAD", "EVAL:READ", "LOAD", "EVAL:READ");
    assertThat(store.recordFailure(fixture.policy, fixture.buckets, fixture.now)).isTrue();
    assertThat(fixture.calls.subList(7, 14))
        .containsExactly("WHOAMI", "LOAD", "EVAL:FAIL", "LOAD", "EVAL:FAIL", "LOAD", "EVAL:FAIL");
    assertThat(fixture.asyncAccesses.get()).isEqualTo(2);
    assertThat(fixture.cancellations.get()).isZero();
  }

  @Test
  void interruptedQueuedWaiterDoesNotPoisonConcurrentHolderOrNextRequest() throws Exception {
    var fixture = new AsyncFixture();
    var store = fixture.store();
    var holderEntered = new CountDownLatch(1);
    var releaseHolder = new CountDownLatch(1);
    fixture.completion =
        (command, evaluation, reply, timeout, unit) -> {
          if (command.equals("EVAL") && evaluation == 1) {
            holderEntered.countDown();
            if (!releaseHolder.await(timeout, unit))
              throw new TimeoutException("Fixture holder expired");
          }
          return reply;
        };
    var executor = Executors.newSingleThreadExecutor();
    var waiterResult = new AtomicReference<AccountCredentialAbusePolicy.Signals>();
    var waiterInterrupted = new AtomicBoolean();
    Thread waiter =
        new Thread(
            () -> {
              waiterResult.set(store.admit(fixture.policy, fixture.buckets, fixture.now));
              waiterInterrupted.set(Thread.currentThread().isInterrupted());
            },
            "credential-policy-queued-waiter");
    try {
      var holder = executor.submit(() -> store.admit(fixture.policy, fixture.buckets, fixture.now));
      assertThat(holderEntered.await(2, TimeUnit.SECONDS)).isTrue();
      waiter.start();
      awaitTimedWait(waiter);
      waiter.interrupt();
      waiter.join(2_000);
      assertThat(waiter.isAlive()).isFalse();
      assertThat(waiterResult.get()).isNull();
      assertThat(waiterInterrupted.get()).isTrue();
      releaseHolder.countDown();
      assertThat(holder.get(2, TimeUnit.SECONDS)).isNotNull();
      assertThat(store.admit(fixture.policy, fixture.buckets, fixture.now)).isNotNull();
      assertThat(fixture.calls).hasSize(14);
      assertThat(fixture.cancellations.get()).isZero();
    } finally {
      releaseHolder.countDown();
      waiter.interrupt();
      waiter.join(2_000);
      executor.shutdownNow();
      assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void lateFinalCompletionCannotUseTheLargerSignalSkewAndFencesUncertainty() {
    var fixture = new AsyncFixture();
    var store = fixture.store();
    fixture.completion =
        (command, evaluation, reply, timeout, unit) -> {
          if (command.equals("EVAL") && evaluation == 3) {
            fixture.nanos.set(fixture.policy.decisionTimeout().toNanos() + 1);
          }
          return reply;
        };
    assertThat(store.admit(fixture.policy, fixture.buckets, fixture.now)).isNull();
    assertThat(fixture.nanos.get()).isLessThan(fixture.policy.maximumSignalSkew().toNanos());
    assertThat(fixture.calls).hasSize(7);
    assertThat(fixture.cancellations.get()).isEqualTo(1);
    fixture.completion = AsyncFixture.IMMEDIATE;
    assertThat(store.admit(fixture.policy, fixture.buckets, fixture.now)).isNull();
    assertThat(fixture.calls).hasSize(7);
  }

  @Test
  void exhaustedBudgetBeforeDispatchSendsNoCommandsAndDoesNotPoisonNextRequest() {
    for (boolean expireDuringAsyncAccessor : List.of(false, true)) {
      var fixture = new AsyncFixture();
      var store = fixture.store();
      Runnable expire = () -> fixture.nanos.set(fixture.policy.decisionTimeout().toNanos());
      if (expireDuringAsyncAccessor) fixture.asyncHook = expire;
      else fixture.optionsHook = expire;
      assertThat(store.admit(fixture.policy, fixture.buckets, fixture.now)).isNull();
      assertThat(fixture.calls).isEmpty();
      assertThat(fixture.cancellations.get()).isZero();
      assertThat(fixture.asyncAccesses.get()).isEqualTo(expireDuringAsyncAccessor ? 1 : 0);
      fixture.optionsHook = () -> {};
      fixture.asyncHook = () -> {};
      assertThat(store.admit(fixture.policy, fixture.buckets, fixture.now)).isNotNull();
      assertThat(fixture.calls).hasSize(7);
    }
  }

  @Test
  void deadlineCrossedDuringFinalDecodingIsCheckedBeforeAcceptedReturn() {
    var fixture = new AsyncFixture();
    var store = fixture.store();
    fixture.completion =
        (command, evaluation, reply, timeout, unit) -> {
          if (!command.equals("EVAL") || evaluation != 3) return reply;
          @SuppressWarnings("unchecked")
          List<Object> values = (List<Object>) reply;
          return new AbstractList<Object>() {
            @Override
            public int size() {
              return values.size();
            }

            @Override
            public Object get(int index) {
              if (index == 2) fixture.nanos.set(fixture.policy.decisionTimeout().toNanos() + 1);
              return values.get(index);
            }
          };
        };
    assertThat(store.admit(fixture.policy, fixture.buckets, fixture.now)).isNull();
    assertThat(fixture.calls).hasSize(7);
    // The response completed within budget; no unknown in-flight mutation was left behind.
    assertThat(fixture.cancellations.get()).isZero();
    fixture.completion = AsyncFixture.IMMEDIATE;
    assertThat(store.admit(fixture.policy, fixture.buckets, fixture.now)).isNotNull();
  }

  @Test
  void interruptedDispatchedMutationKeepsTheConnectionFencedAndPreservesInterrupt() {
    var fixture = new AsyncFixture();
    var store = fixture.store();
    fixture.completion =
        (command, evaluation, reply, timeout, unit) -> {
          if (command.equals("EVAL"))
            throw new InterruptedException("Fixture mutation interrupted");
          return reply;
        };
    try {
      assertThat(store.admit(fixture.policy, fixture.buckets, fixture.now)).isNull();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
    assertThat(fixture.calls).hasSize(3);
    assertThat(fixture.cancellations.get()).isEqualTo(1);
    fixture.completion = AsyncFixture.IMMEDIATE;
    assertThat(store.admit(fixture.policy, fixture.buckets, fixture.now)).isNull();
    assertThat(fixture.calls).hasSize(3);
  }

  @Test
  void completedErrorsFromNonMutatingPreparationCommandsDoNotFenceLaterAttempts() {
    for (String failedCommand : List.of("WHOAMI", "LOAD")) {
      var fixture = new AsyncFixture();
      var store = fixture.store();
      var failOnce = new AtomicBoolean(true);
      fixture.completion =
          (command, evaluation, reply, timeout, unit) -> {
            if (command.equals(failedCommand) && failOnce.getAndSet(false))
              throw new RedisCommandExecutionException("ERR controlled preparation failure");
            return reply;
          };

      assertThat(store.admit(fixture.policy, fixture.buckets, fixture.now)).isNull();
      assertThat(fixture.calls).hasSize(failedCommand.equals("WHOAMI") ? 1 : 2);
      fixture.completion = AsyncFixture.IMMEDIATE;
      assertThat(store.admit(fixture.policy, fixture.buckets, fixture.now)).isNotNull();
      assertThat(fixture.calls).hasSize(failedCommand.equals("WHOAMI") ? 8 : 9);
    }
  }

  @Test
  void timedOutNonMutatingPreparationCommandsDoNotFenceLaterAttempts() {
    for (String failedCommand : List.of("WHOAMI", "LOAD")) {
      var fixture = new AsyncFixture();
      var store = fixture.store();
      var failOnce = new AtomicBoolean(true);
      fixture.completion =
          (command, evaluation, reply, timeout, unit) -> {
            if (command.equals(failedCommand) && failOnce.getAndSet(false))
              throw new TimeoutException("Fixture preparation timeout");
            return reply;
          };

      assertThat(store.admit(fixture.policy, fixture.buckets, fixture.now)).isNull();
      assertThat(fixture.calls).hasSize(failedCommand.equals("WHOAMI") ? 1 : 2);
      assertThat(fixture.cancellations.get()).isEqualTo(1);
      fixture.completion = AsyncFixture.IMMEDIATE;
      assertThat(store.admit(fixture.policy, fixture.buckets, fixture.now)).isNotNull();
      assertThat(fixture.calls).hasSize(failedCommand.equals("WHOAMI") ? 8 : 9);
    }
  }

  @Test
  void completedRedisErrorFromScriptEvaluationKeepsTheConnectionFenced() {
    var fixture = new AsyncFixture();
    var store = fixture.store();
    var failOnce = new AtomicBoolean(true);
    fixture.completion =
        (command, evaluation, reply, timeout, unit) -> {
          if (command.equals("EVAL") && failOnce.getAndSet(false))
            throw new RedisCommandExecutionException("ERR controlled script failure");
          return reply;
        };

    assertThat(store.admit(fixture.policy, fixture.buckets, fixture.now)).isNull();
    assertThat(fixture.calls).hasSize(3);
    fixture.completion = AsyncFixture.IMMEDIATE;
    assertThat(store.admit(fixture.policy, fixture.buckets, fixture.now)).isNull();
    assertThat(fixture.calls).hasSize(3);
  }

  private static void awaitTimedWait(Thread thread) throws InterruptedException {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (thread.isAlive()
        && thread.getState() != Thread.State.TIMED_WAITING
        && System.nanoTime() < until) Thread.sleep(1);
    assertThat(thread.getState()).isEqualTo(Thread.State.TIMED_WAITING);
  }

  @FunctionalInterface
  private interface Completion {
    Object get(String command, int evaluation, Object reply, long timeout, TimeUnit unit)
        throws Exception;
  }

  /** Exercises the real store command path with controlled futures, never a Redis process. */
  private static final class AsyncFixture {
    private static final Completion IMMEDIATE =
        (command, evaluation, reply, timeout, unit) -> reply;
    final AtomicLong nanos = new AtomicLong();
    final AtomicInteger asyncAccesses = new AtomicInteger();
    final AtomicInteger cancellations = new AtomicInteger();
    final List<String> calls = new CopyOnWriteArrayList<>();
    final AccountCredentialAbusePolicy policy = asyncPolicy();
    final Instant now = policy.validFrom();
    final AccountCredentialAbuseService.Buckets buckets;
    final StatefulRedisConnection<byte[], byte[]> connection;
    final ClientOptions options = ClientOptions.builder().autoReconnect(false).build();
    Completion completion = IMMEDIATE;
    Runnable optionsHook = () -> {};
    Runnable asyncHook = () -> {};
    int evaluations;
    String loadedSha;

    @SuppressWarnings("unchecked")
    AsyncFixture() {
      try (var key = new AccountCredentialAbuseSubjectKeys.DigestKey("test-key", new byte[32])) {
        var keys = new AccountCredentialAbuseSubjectKeys(policy.scope(), key);
        long window = now.toEpochMilli() / policy.window().toMillis();
        buckets =
            new AccountCredentialAbuseService.Buckets(
                keys.source("192.0.2.1", window),
                keys.candidate("unknown@example.test", window),
                keys.pressure(window),
                policy.expiryEpochMillis(window));
      }
      var commands =
          (RedisAsyncCommands<byte[], byte[]>)
              Proxy.newProxyInstance(
                  RedisAsyncCommands.class.getClassLoader(),
                  new Class<?>[] {RedisAsyncCommands.class},
                  (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                      case "aclWhoami" -> {
                        calls.add("WHOAMI");
                        yield future("WHOAMI", evaluations, "account_cache_app");
                      }
                      case "scriptLoad" -> {
                        byte[] script = (byte[]) arguments[0];
                        loadedSha =
                            HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-1").digest(script));
                        calls.add("LOAD");
                        yield future("LOAD", evaluations, loadedSha);
                      }
                      case "evalsha" -> {
                        assertThat(arguments[0]).isEqualTo(loadedSha);
                        assertThat(arguments[1]).isEqualTo(ScriptOutputType.MULTI);
                        byte[][] keys = (byte[][]) arguments[2];
                        byte[][] argv = (byte[][]) arguments[3];
                        assertThat(keys.length).isEqualTo(1);
                        assertThat(argv.length).isEqualTo(12);
                        int position = evaluations++ % 3;
                        String expectedKey =
                            switch (position) {
                              case 0 -> buckets.pressure();
                              case 1 -> buckets.source().redisKey();
                              default -> buckets.candidate().redisKey();
                            };
                        assertThat(keys[0]).isEqualTo(bytes(expectedKey));
                        assertThat(argv[1])
                            .isEqualTo(bytes(Long.toString(buckets.expiryEpochMillis())));
                        String operation = new String(argv[8], StandardCharsets.US_ASCII);
                        assertThat(operation).isIn(position == 0 ? "ADMIT" : "READ", "FAIL");
                        calls.add("EVAL:" + operation);
                        List<Object> reply =
                            List.of(
                                bytes("APPLIED"),
                                position == 0 ? 1L : position == 1 ? 3L : 4L,
                                position == 0 ? 2L : 0L);
                        yield future("EVAL", evaluations, reply);
                      }
                      default ->
                          throw new AssertionError("Unexpected async command: " + method.getName());
                    };
                  });
      connection =
          (StatefulRedisConnection<byte[], byte[]>)
              Proxy.newProxyInstance(
                  StatefulRedisConnection.class.getClassLoader(),
                  new Class<?>[] {StatefulRedisConnection.class},
                  (proxy, method, arguments) ->
                      switch (method.getName()) {
                        case "isOpen" -> true;
                        case "getOptions" -> {
                          optionsHook.run();
                          yield options;
                        }
                        case "async" -> {
                          asyncAccesses.incrementAndGet();
                          asyncHook.run();
                          yield commands;
                        }
                        default ->
                            throw new AssertionError(
                                "Unexpected connection method: " + method.getName());
                      });
    }

    AccountCredentialAbuseRedisStore store() {
      return new AccountCredentialAbuseRedisStore(connection, nanos::get);
    }

    private Object future(String command, int evaluation, Object reply) {
      var completed = new CompletableFuture<Object>();
      return Proxy.newProxyInstance(
          RedisFuture.class.getClassLoader(),
          new Class<?>[] {RedisFuture.class},
          (proxy, method, arguments) ->
              switch (method.getName()) {
                case "get" -> {
                  assertThat(arguments).hasSize(2);
                  long timeout = (Long) arguments[0];
                  TimeUnit unit = (TimeUnit) arguments[1];
                  assertThat(timeout)
                      .isPositive()
                      .isLessThanOrEqualTo(policy.decisionTimeout().toNanos());
                  assertThat(unit).isEqualTo(TimeUnit.NANOSECONDS);
                  try {
                    completed.complete(completion.get(command, evaluation, reply, timeout, unit));
                  } catch (RedisCommandExecutionException redisError) {
                    completed.completeExceptionally(redisError);
                  }
                  yield completed.get(timeout, unit);
                }
                case "cancel" -> {
                  cancellations.incrementAndGet();
                  yield completed.cancel((Boolean) arguments[0]);
                }
                case "isDone" -> completed.isDone();
                default ->
                    throw new AssertionError("Unexpected future method: " + method.getName());
              });
    }
  }

  private static AccountCredentialAbusePolicy asyncPolicy() {
    var p = AccountCredentialAbuseServiceTest.policy();
    return new AccountCredentialAbusePolicy(
        p.scope(),
        p.validFrom(),
        p.validUntil(),
        p.window(),
        p.timeToLive(),
        p.maximumLiveWindows(),
        p.maximumActiveSubjects(),
        p.maximumMemoryBytes(),
        p.maximumBucketMemoryBytes(),
        p.maximumAdmissionsPerWindow(),
        p.maximumCounter(),
        Duration.ofSeconds(2),
        Duration.ofSeconds(3),
        p.maximumRetry(),
        p.unavailableRetry(),
        p.rules());
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
