package net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AccountPublicJwksCacheTest {
  private static final Instant INITIAL = Instant.parse("2026-10-05T10:00:00Z");
  private static final AccountPublicJwksCache.SourceIdentity PIN =
      new AccountPublicJwksCache.SourceIdentity(
          "prod",
          "cluster-a",
          "11111111-1111-4111-8111-111111111111",
          "firemud-prod",
          "22222222-2222-4222-8222-222222222222",
          "33333333-3333-4333-8333-333333333333",
          "binding-7",
          "https://kubernetes.example:6443",
          "a".repeat(64));

  @Test
  void preparedDenyBlocksWarmKeysAcrossOutageAndOnlyEligibleRecoveryRenewsAge() throws Exception {
    KeyPair key = rsa3072();
    String json = jwks(jwk("known", (RSAPublicKey) key.getPublic()));
    AtomicReference<RuntimeException> failure = new AtomicReference<>();
    AtomicReference<AccountPublicJwksCache.PublicJwksSnapshot> next =
        new AtomicReference<>(snapshot(json));
    MutableClock clock = new MutableClock(INITIAL);
    var cache =
        cache(
            clock,
            () -> {
              if (failure.get() != null) throw failure.get();
              return next.get();
            });
    cache.refresh();
    assertThat(cache.keyFor("known")).isEqualTo(key.getPublic());
    failure.set(new AccountPublicJwksCache.SourcePreparedDenyException());
    assertThatThrownBy(cache::refresh)
        .isInstanceOf(AccountPublicJwksCache.SourcePreparedDenyException.class);
    assertThatThrownBy(() -> cache.keyFor("known"))
        .isInstanceOf(AccountPublicJwksCache.SourcePreparedDenyException.class);
    failure.set(new AccountPublicJwksCache.SourceUnavailableException());
    assertThatThrownBy(cache::refresh)
        .isInstanceOf(AccountPublicJwksCache.SourcePreparedDenyException.class);
    clock.advance(Duration.ofSeconds(11));
    failure.set(null);
    assertThatThrownBy(cache::refresh)
        .isInstanceOf(AccountPublicJwksCache.SourcePreparedDenyException.class);
    assertThatThrownBy(() -> cache.keyFor("known"))
        .isInstanceOf(AccountPublicJwksCache.SourcePreparedDenyException.class);
    next.set(
        new AccountPublicJwksCache.PublicJwksSnapshot(
            PIN, json.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    cache.refresh();
    assertThat(cache.keyFor("known")).isEqualTo(key.getPublic());
    failure.set(new AccountPublicJwksCache.SourceUnavailableException());
    clock.advance(Duration.ofSeconds(11));
    assertThatThrownBy(() -> cache.keyFor("known"))
        .isInstanceOf(AccountPublicJwksCache.SourceUnavailableException.class);
  }

  @Test
  void separateDenyObservationPreventsOlderFlightInstallWithoutBlockingInvalidation()
      throws Exception {
    KeyPair key = rsa3072();
    String json =
        jwks(
            jwk("known", (RSAPublicKey) key.getPublic())
                + ","
                + jwk("cut", (RSAPublicKey) key.getPublic()));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var denied = new java.util.concurrent.atomic.AtomicBoolean();
    var epoch = new java.util.concurrent.atomic.AtomicLong();
    AtomicInteger loads = new AtomicInteger();
    var source =
        new AccountPublicJwksCache.TrustedPublicJwksSource() {
          @Override
          public AccountPublicJwksCache.PublicJwksSnapshot load() {
            long snapshotEpoch = epoch.get();
            if (loads.incrementAndGet() == 2) {
              entered.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS))
                  throw new AssertionError("Release timed out");
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
              }
            }
            return new AccountPublicJwksCache.PublicJwksSnapshot(
                PIN, json.getBytes(java.nio.charset.StandardCharsets.UTF_8), true, snapshotEpoch);
          }

          @Override
          public boolean isPreparedDenied() {
            return denied.get();
          }

          @Override
          public long preparedDenyEpoch() {
            return epoch.get();
          }
        };
    var cache = cache(new MutableClock(INITIAL), source);
    cache.refresh();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var refresh = executor.submit(cache::refresh);
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        epoch.incrementAndGet();
        denied.set(true);
        var invalidate =
            executor.submit(
                () -> {
                  assertThatThrownBy(() -> cache.keyFor("known"))
                      .isInstanceOf(AccountPublicJwksCache.SourcePreparedDenyException.class);
                  cache.invalidateKid("cut");
                });
        invalidate.get(5, TimeUnit.SECONDS);
        // A full source recovery cannot authorize the older cache flight's pre-deny bytes.
        denied.set(false);
      } finally {
        release.countDown();
      }
      assertThatThrownBy(() -> refresh.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(AccountPublicJwksCache.SourcePreparedDenyException.class);
    }
    assertThatThrownBy(() -> cache.keyFor("known"))
        .isInstanceOf(AccountPublicJwksCache.SourcePreparedDenyException.class);
    denied.set(false);
    cache.refresh();
    assertThat(cache.keyFor("known")).isEqualTo(key.getPublic());
    assertThatThrownBy(() -> cache.keyFor("cut"))
        .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
  }

  @Test
  void separateCustodyObservationPreventsOlderFlightInstallAndCachedReads() throws Exception {
    KeyPair key = rsa3072();
    String json = jwks(jwk("known", (RSAPublicKey) key.getPublic()));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var terminal = new java.util.concurrent.atomic.AtomicBoolean();
    AtomicInteger loads = new AtomicInteger();
    var source =
        new AccountPublicJwksCache.TrustedPublicJwksSource() {
          @Override
          public AccountPublicJwksCache.PublicJwksSnapshot load() {
            if (loads.incrementAndGet() == 2) {
              entered.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS))
                  throw new AssertionError("Release timed out");
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
              }
            }
            return snapshot(json);
          }

          @Override
          public boolean isCustodyRejected() {
            return terminal.get();
          }
        };
    var cache = cache(new MutableClock(INITIAL), source);
    cache.refresh();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var refresh = executor.submit(cache::refresh);
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        terminal.set(true);
        assertThatThrownBy(() -> cache.keyFor("known"))
            .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
      } finally {
        release.countDown();
      }
      assertThatThrownBy(() -> refresh.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
    }
    terminal.set(false);
    assertThatThrownBy(cache::refresh)
        .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
    assertThatThrownBy(() -> cache.keyFor("known"))
        .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
  }

  @Test
  void terminalCustodyFailureDuringPreparedDenyCannotRecover() throws Exception {
    KeyPair key = rsa3072();
    String json = jwks(jwk("known", (RSAPublicKey) key.getPublic()));
    AtomicReference<RuntimeException> failure =
        new AtomicReference<>(new AccountPublicJwksCache.SourcePreparedDenyException());
    var cache =
        cache(
            new MutableClock(INITIAL),
            () -> {
              if (failure.get() != null) throw failure.get();
              return new AccountPublicJwksCache.PublicJwksSnapshot(
                  PIN, json.getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
            });
    assertThatThrownBy(cache::refresh)
        .isInstanceOf(AccountPublicJwksCache.SourcePreparedDenyException.class);
    failure.set(new AccountPublicJwksCache.SourceCustodyRejectedException());
    assertThatThrownBy(cache::refresh)
        .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
    failure.set(null);
    assertThatThrownBy(cache::refresh)
        .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
    assertThatThrownBy(() -> cache.keyFor("known"))
        .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
  }

  @Test
  void blockedProactiveLoadAllowsFreshBoundReadsAndImmediateInvalidation() throws Exception {
    KeyPair key = rsa3072();
    String first = jwks(jwk("known", (RSAPublicKey) key.getPublic()));
    String second = first + "\n";
    var source = new BlockingSource(snapshot(first), () -> snapshot(second));
    var cache = cache(new MutableClock(INITIAL), source);
    cache.refresh();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var refresh = executor.submit(cache::refresh);
      try {
        assertThat(source.entered.await(5, TimeUnit.SECONDS)).isTrue();
        var readAndInvalidate =
            executor.submit(
                () -> {
                  assertThat(cache.keyFor("known", PIN, sha256(first))).isEqualTo(key.getPublic());
                  assertThatThrownBy(() -> cache.keyFor("known", PIN, sha256(second)))
                      .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
                  cache.invalidateKid("known");
                  assertThatThrownBy(() -> cache.keyFor("known"))
                      .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
                  return null;
                });
        readAndInvalidate.get(5, TimeUnit.SECONDS);
      } finally {
        source.release.countDown();
      }
      refresh.get(5, TimeUnit.SECONDS);
    }
    assertThatThrownBy(() -> cache.keyFor("known"))
        .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
    assertThat(source.loads).hasValue(2);
  }

  @Test
  void proactiveUnknownAndStaleRequestsShareOneFlightInEitherOrder() throws Exception {
    KeyPair key = rsa3072();
    String first = jwks(jwk("known", (RSAPublicKey) key.getPublic()));
    String second =
        jwks(
            jwk("known", (RSAPublicKey) key.getPublic())
                + ","
                + jwk("rotated", (RSAPublicKey) key.getPublic()));
    for (boolean demandFirst : new boolean[] {false, true}) {
      var source = new BlockingSource(snapshot(first), () -> snapshot(second));
      MutableClock clock = new MutableClock(INITIAL);
      var cache = cache(clock, source);
      cache.refresh();
      try (var executor = Executors.newFixedThreadPool(3)) {
        var firstRequest =
            executor.submit(
                () -> {
                  if (demandFirst) {
                    assertThat(cache.keyFor("rotated", PIN, sha256(second)))
                        .isEqualTo(key.getPublic());
                  } else {
                    cache.refresh();
                  }
                  return null;
                });
        try {
          assertThat(source.entered.await(5, TimeUnit.SECONDS)).isTrue();
          AtomicReference<Thread> proactiveThread = new AtomicReference<>();
          var proactive =
              executor.submit(
                  () -> {
                    proactiveThread.set(Thread.currentThread());
                    cache.refresh();
                  });
          awaitWaiting(proactiveThread);
          clock.advance(Duration.ofSeconds(11));
          AtomicReference<Thread> staleThread = new AtomicReference<>();
          var stale =
              executor.submit(
                  () -> {
                    staleThread.set(Thread.currentThread());
                    return cache.keyFor("known", PIN, sha256(second));
                  });
          awaitWaiting(staleThread);
          assertThat(source.loads).hasValue(2);
          source.release.countDown();
          firstRequest.get(5, TimeUnit.SECONDS);
          proactive.get(5, TimeUnit.SECONDS);
          assertThat(stale.get(5, TimeUnit.SECONDS)).isEqualTo(key.getPublic());
        } finally {
          source.release.countDown();
        }
      }
      assertThat(source.loads).hasValue(2);
      assertThatThrownBy(() -> cache.keyFor("known", PIN, sha256(first)))
          .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
      assertThat(cache.keyFor("rotated", PIN, sha256(second))).isEqualTo(key.getPublic());
    }
  }

  @Test
  void proactiveJoinPermanentlyRejectsInvalidDemandFlight() throws Exception {
    KeyPair key = rsa3072();
    var first = snapshot(jwks(jwk("known", (RSAPublicKey) key.getPublic())));
    var source = new BlockingSource(first, () -> snapshot("{\"keys\":[]}"));
    var cache = cache(new MutableClock(INITIAL), source);
    cache.refresh();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var demand =
          executor.submit(
              () ->
                  assertThatThrownBy(() -> cache.keyFor("unknown"))
                      .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class));
      try {
        assertThat(source.entered.await(5, TimeUnit.SECONDS)).isTrue();
        AtomicReference<Thread> proactiveThread = new AtomicReference<>();
        var proactive =
            executor.submit(
                () -> {
                  proactiveThread.set(Thread.currentThread());
                  assertThatThrownBy(cache::refresh)
                      .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
                });
        awaitWaiting(proactiveThread);
        source.release.countDown();
        demand.get(5, TimeUnit.SECONDS);
        proactive.get(5, TimeUnit.SECONDS);
      } finally {
        source.release.countDown();
      }
    }
    assertThatThrownBy(() -> cache.keyFor("known"))
        .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
    assertThatThrownBy(cache::refresh)
        .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
    assertThat(source.loads).hasValue(2);
  }

  @Test
  void blockedRefreshOutageCannotUseSnapshotThatExpiredDuringLoad() throws Exception {
    KeyPair key = rsa3072();
    var first = snapshot(jwks(jwk("known", (RSAPublicKey) key.getPublic())));
    var source =
        new BlockingSource(
            first,
            () -> {
              throw new AccountPublicJwksCache.SourceUnavailableException();
            });
    MutableClock clock = new MutableClock(INITIAL);
    var cache = cache(clock, source);
    cache.refresh();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var refresh =
          executor.submit(
              () ->
                  assertThatThrownBy(cache::refresh)
                      .isInstanceOf(AccountPublicJwksCache.SourceUnavailableException.class));
      try {
        assertThat(source.entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(cache.keyFor("known")).isEqualTo(key.getPublic());
        clock.advance(Duration.ofSeconds(11));
      } finally {
        source.release.countDown();
      }
      refresh.get(5, TimeUnit.SECONDS);
    }
    assertThatThrownBy(() -> cache.keyFor("known"))
        .isInstanceOf(AccountPublicJwksCache.SourceUnavailableException.class);
  }

  private static void awaitWaiting(AtomicReference<Thread> thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while ((thread.get() == null || thread.get().getState() != Thread.State.WAITING)
        && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertThat(thread.get()).isNotNull();
    assertThat(thread.get().getState()).isEqualTo(Thread.State.WAITING);
  }

  private static final class BlockingSource
      implements AccountPublicJwksCache.TrustedPublicJwksSource {
    private final AccountPublicJwksCache.PublicJwksSnapshot initial;
    private final AccountPublicJwksCache.TrustedPublicJwksSource next;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger loads = new AtomicInteger();

    private BlockingSource(
        AccountPublicJwksCache.PublicJwksSnapshot initial,
        AccountPublicJwksCache.TrustedPublicJwksSource next) {
      this.initial = initial;
      this.next = next;
    }

    @Override
    public AccountPublicJwksCache.PublicJwksSnapshot load() {
      if (loads.incrementAndGet() == 1) return initial;
      entered.countDown();
      try {
        if (!release.await(30, TimeUnit.SECONDS)) {
          throw new AssertionError("source release timed out");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
      return next.load();
    }
  }

  @Test
  void unknownKidCustodyRejectionImmediatelyLatchesKnownKeysAndLaterRefresh() throws Exception {
    KeyPair key = rsa3072();
    var accepted = snapshot(jwks(jwk("known", (RSAPublicKey) key.getPublic())));
    for (boolean typedCustodyRejection : new boolean[] {true, false}) {
      MutableClock clock = new MutableClock(INITIAL);
      AtomicInteger loads = new AtomicInteger();
      AtomicReference<AccountPublicJwksCache.PublicJwksSnapshot> current =
          new AtomicReference<>(accepted);
      AtomicReference<Boolean> custodyRejected = new AtomicReference<>(false);
      var cache =
          cache(
              clock,
              () -> {
                loads.incrementAndGet();
                if (typedCustodyRejection && custodyRejected.get()) {
                  throw new AccountPublicJwksCache.SourceCustodyRejectedException();
                }
                return current.get();
              });
      assertThat(cache.keyFor("known")).isEqualTo(key.getPublic());
      custodyRejected.set(true);
      current.set(new AccountPublicJwksCache.PublicJwksSnapshot(otherPin(), accepted.jwksBytes()));
      assertThatThrownBy(() -> cache.keyFor("unknown"))
          .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
      // Neither a restored source nor another lookup can silently requalify a rejected instance.
      custodyRejected.set(false);
      current.set(accepted);
      assertThatThrownBy(() -> cache.keyFor("known"))
          .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
      assertThatThrownBy(cache::refresh)
          .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
      assertThat(loads).hasValue(2);
    }
  }

  @Test
  void proactiveRefreshOutagesNeverExtendAgeAndDeniedKidsStayDenied() throws Exception {
    KeyPair key = rsa3072();
    MutableClock clock = new MutableClock(INITIAL);
    AtomicReference<AccountPublicJwksCache.TrustedPublicJwksSource> current =
        new AtomicReference<>(
            () ->
                snapshot(
                    jwks(
                        jwk("known", (RSAPublicKey) key.getPublic())
                            + ","
                            + jwk("denied", (RSAPublicKey) key.getPublic()))));
    var cache = cache(clock, () -> current.get().load());
    cache.refresh();
    cache.invalidateKid("denied");
    current.set(
        () -> {
          throw new AccountPublicJwksCache.SourceUnavailableException();
        });
    clock.advance(Duration.ofSeconds(3));
    cache.refresh();
    clock.advance(Duration.ofSeconds(3));
    cache.refresh();
    assertThat(cache.keyFor("known")).isEqualTo(key.getPublic());
    assertThatThrownBy(() -> cache.keyFor("denied"))
        .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
    clock.advance(Duration.ofSeconds(5));
    assertThatThrownBy(cache::refresh)
        .isInstanceOf(AccountPublicJwksCache.SourceUnavailableException.class);
    assertThatThrownBy(() -> cache.keyFor("known"))
        .isInstanceOf(AccountPublicJwksCache.SourceUnavailableException.class);
    assertThatThrownBy(() -> cache.keyFor("denied"))
        .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
  }

  @Test
  void proactiveInvalidSourcePermanentlyRefusesFreshKnownAndUnknownKids() throws Exception {
    KeyPair key = rsa3072();
    var valid = snapshot(jwks(jwk("known", (RSAPublicKey) key.getPublic())));
    var changed = new AccountPublicJwksCache.PublicJwksSnapshot(otherPin(), valid.jwksBytes());
    for (var invalid :
        new AccountPublicJwksCache.PublicJwksSnapshot[] {
          changed,
          snapshot("{\"keys\":[]}"),
          snapshot(jwks(jwk("known", (RSAPublicKey) rsa3072().getPublic())))
        }) {
      MutableClock clock = new MutableClock(INITIAL);
      AtomicReference<AccountPublicJwksCache.PublicJwksSnapshot> current =
          new AtomicReference<>(valid);
      var cache = cache(clock, current::get);
      cache.refresh();
      cache.invalidateKid("denied");
      current.set(invalid);
      assertThatThrownBy(cache::refresh)
          .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
      current.set(valid);
      assertThatThrownBy(() -> cache.keyFor("known"))
          .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
      assertThatThrownBy(() -> cache.keyFor("unknown"))
          .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
      assertThatThrownBy(cache::refresh)
          .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
      assertThatThrownBy(() -> cache.keyFor("denied"))
          .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
    }
  }

  @Test
  void snapshotBoundKnownKeyRejectsNewEvidenceUntilOrdinaryRefreshMatchesIt() throws Exception {
    KeyPair key = rsa3072();
    String first = jwks(jwk("known", (RSAPublicKey) key.getPublic()));
    String second = first + "\n";
    AtomicReference<String> current = new AtomicReference<>(first);
    AtomicInteger loads = new AtomicInteger();
    MutableClock clock = new MutableClock(INITIAL);
    AccountPublicJwksCache cache =
        cache(
            clock,
            () -> {
              loads.incrementAndGet();
              return snapshot(current.get());
            });

    assertThat(cache.keyFor("known", PIN, sha256(first))).isEqualTo(key.getPublic());
    current.set(second);
    assertThatThrownBy(() -> cache.keyFor("known", PIN, sha256(second)))
        .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
    assertThatThrownBy(() -> cache.keyFor("known", otherPin(), sha256(first)))
        .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
    assertThat(cache.keyFor("known", PIN, sha256(first))).isEqualTo(key.getPublic());
    assertThat(loads).hasValue(1);
    for (String malformed : new String[] {null, "", "A".repeat(64), "a".repeat(63)}) {
      assertThatThrownBy(() -> cache.keyFor("known", PIN, malformed))
          .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
    }
    assertThatThrownBy(() -> cache.keyFor("known", null, sha256(first)))
        .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
    assertThat(loads).hasValue(1);

    clock.advance(Duration.ofSeconds(11));
    assertThatThrownBy(() -> cache.keyFor("known", PIN, sha256(first)))
        .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
    assertThat(cache.keyFor("known", PIN, sha256(second))).isEqualTo(key.getPublic());
    assertThat(loads).hasValue(2);
    cache.invalidateKid("known");
    assertThatThrownBy(() -> cache.keyFor("known", PIN, sha256(second)))
        .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
    assertThat(loads).hasValue(2);
  }

  @Test
  void concurrentRefreshCannotPairAKeyWithAnotherSnapshotsBinding() throws Exception {
    KeyPair key = rsa3072();
    String first = jwks(jwk("known", (RSAPublicKey) key.getPublic()));
    String second = first + "\n";
    var refreshEntered = new java.util.concurrent.CountDownLatch(1);
    var releaseRefresh = new java.util.concurrent.CountDownLatch(1);
    AtomicInteger loads = new AtomicInteger();
    MutableClock clock = new MutableClock(INITIAL);
    AccountPublicJwksCache cache =
        cache(
            clock,
            () -> {
              if (loads.incrementAndGet() == 1) {
                return snapshot(first);
              }
              refreshEntered.countDown();
              try {
                if (!releaseRefresh.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                  throw new AssertionError("refresh release timed out");
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
              }
              return snapshot(second);
            });
    cache.keyFor("known", PIN, sha256(first));
    clock.advance(Duration.ofSeconds(11));
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var oldBinding =
          executor.submit(
              () -> {
                assertThatThrownBy(() -> cache.keyFor("known", PIN, sha256(first)))
                    .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
              });
      try {
        assertThat(refreshEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var newBinding = executor.submit(() -> cache.keyFor("known", PIN, sha256(second)));
        releaseRefresh.countDown();
        oldBinding.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(newBinding.get(5, java.util.concurrent.TimeUnit.SECONDS))
            .isEqualTo(key.getPublic());
      } finally {
        releaseRefresh.countDown();
      }
    }
    assertThat(loads).hasValue(2);
  }

  private static String sha256(String value) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  @Test
  void parsesStrongPublicOnlyKeysAndRetainsBothKeysPresentInOneSnapshot() throws Exception {
    KeyPair oldKey = rsa3072();
    KeyPair newKey = rsa3072();
    String jwks =
        jwks(
            jwk("old", (RSAPublicKey) oldKey.getPublic())
                + ","
                + jwk("new", (RSAPublicKey) newKey.getPublic()));
    MutableClock clock = new MutableClock(INITIAL);
    AccountPublicJwksCache cache = cache(clock, () -> snapshot(jwks));

    assertThat(cache.keyFor("old")).isEqualTo(oldKey.getPublic());
    assertThat(cache.keyFor("new")).isEqualTo(newKey.getPublic());
    assertThat(cache.sourceIdentity()).isEqualTo(PIN);
  }

  @Test
  void unknownKidGetsOneForcedLoadThenCooldownPreventsRefreshStorm() throws Exception {
    KeyPair key = rsa3072();
    AtomicInteger loads = new AtomicInteger();
    MutableClock clock = new MutableClock(INITIAL);
    AccountPublicJwksCache cache =
        cache(
            clock,
            () -> {
              loads.incrementAndGet();
              return snapshot(jwks(jwk("known", (RSAPublicKey) key.getPublic())));
            });

    assertThatThrownBy(() -> cache.keyFor("unknown"))
        .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
    assertThatThrownBy(() -> cache.keyFor("unknown"))
        .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
    assertThat(loads).hasValue(1);

    clock.advance(Duration.ofSeconds(1));
    assertThatThrownBy(() -> cache.keyFor("unknown-two"))
        .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
    assertThat(loads).hasValue(2);
  }

  @Test
  void distinctUnknownKidsShareRefreshCooldownAndRotationRefreshWorksAfterOneSecond()
      throws Exception {
    KeyPair known = rsa3072();
    KeyPair rotated = rsa3072();
    AtomicInteger loads = new AtomicInteger();
    AtomicReference<String> currentJwks =
        new AtomicReference<>(jwks(jwk("known", (RSAPublicKey) known.getPublic())));
    MutableClock clock = new MutableClock(INITIAL);
    AccountPublicJwksCache cache =
        cache(
            clock,
            () -> {
              loads.incrementAndGet();
              return snapshot(currentJwks.get());
            });

    assertThat(cache.keyFor("known")).isEqualTo(known.getPublic());
    assertThatThrownBy(() -> cache.keyFor("unknown-one"))
        .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
    assertThat(loads).hasValue(2);

    assertThatThrownBy(() -> cache.keyFor("unknown-two"))
        .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
    assertThatThrownBy(() -> cache.keyFor("unknown-three"))
        .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
    assertThat(cache.keyFor("known")).isEqualTo(known.getPublic());
    assertThat(loads).hasValue(2);

    currentJwks.set(
        jwks(
            jwk("known", (RSAPublicKey) known.getPublic())
                + ","
                + jwk("rotated", (RSAPublicKey) rotated.getPublic())));
    clock.advance(Duration.ofSeconds(1));
    assertThat(cache.keyFor("rotated")).isEqualTo(rotated.getPublic());
    assertThat(loads).hasValue(3);

    assertThatThrownBy(() -> cache.keyFor("unknown-after-rotation"))
        .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
    assertThat(loads).hasValue(3);
  }

  @Test
  void unavailableUnknownKidRefreshIsRateLimitedAndBackwardClockFailsClosed() throws Exception {
    KeyPair known = rsa3072();
    AtomicInteger loads = new AtomicInteger();
    AtomicReference<Boolean> unavailable = new AtomicReference<>(false);
    MutableClock clock = new MutableClock(INITIAL);
    AccountPublicJwksCache cache =
        cache(
            clock,
            () -> {
              loads.incrementAndGet();
              if (unavailable.get()) {
                throw new AccountPublicJwksCache.SourceUnavailableException();
              }
              return snapshot(jwks(jwk("known", (RSAPublicKey) known.getPublic())));
            });

    assertThat(cache.keyFor("known")).isEqualTo(known.getPublic());
    unavailable.set(true);
    clock.advance(Duration.ofMillis(500));
    assertThatThrownBy(() -> cache.keyFor("unknown-one"))
        .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
    assertThat(loads).hasValue(2);

    clock.advance(Duration.ofMillis(250));
    clock.advance(Duration.ofMillis(-500));
    assertThat(cache.keyFor("known")).isEqualTo(known.getPublic());
    assertThatThrownBy(() -> cache.keyFor("unknown-two"))
        .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
    assertThat(loads).hasValue(2);

    clock.advance(Duration.ofSeconds(10));
    assertThatThrownBy(() -> cache.keyFor("known"))
        .isInstanceOf(AccountPublicJwksCache.SourceUnavailableException.class);
    assertThat(loads).hasValue(3);
  }

  @Test
  void neverLoadedUnknownKidRetriesSourceAfterUnavailableResponseBackoff() {
    AtomicInteger loads = new AtomicInteger();
    MutableClock clock = new MutableClock(INITIAL);
    AccountPublicJwksCache cache =
        cache(
            clock,
            () -> {
              loads.incrementAndGet();
              throw new AccountPublicJwksCache.SourceUnavailableException();
            });

    assertThatThrownBy(() -> cache.keyFor("future"))
        .isInstanceOf(AccountPublicJwksCache.SourceUnavailableException.class);
    assertThatThrownBy(() -> cache.keyFor("future"))
        .isInstanceOf(AccountPublicJwksCache.SourceUnavailableException.class);
    assertThat(loads).hasValue(1);

    clock.advance(Duration.ofSeconds(1));
    assertThatThrownBy(() -> cache.keyFor("future"))
        .isInstanceOf(AccountPublicJwksCache.SourceUnavailableException.class);
    assertThat(loads).hasValue(2);
  }

  @Test
  void staleUnavailableDistinctUnknownKidsDoNotGrowRefreshHistoryPastItsBound() throws Exception {
    KeyPair known = rsa3072();
    AtomicInteger loads = new AtomicInteger();
    AtomicReference<Boolean> unavailable = new AtomicReference<>(false);
    MutableClock clock = new MutableClock(INITIAL);
    AccountPublicJwksCache cache =
        cache(
            clock,
            () -> {
              loads.incrementAndGet();
              if (unavailable.get()) {
                throw new AccountPublicJwksCache.SourceUnavailableException();
              }
              return snapshot(jwks(jwk("known", (RSAPublicKey) known.getPublic())));
            });

    assertThat(cache.keyFor("known")).isEqualTo(known.getPublic());
    unavailable.set(true);
    clock.advance(Duration.ofSeconds(11));

    for (int i = 0; i < 260; i++) {
      String kid = "unknown-" + i;
      assertThatThrownBy(() -> cache.keyFor(kid))
          .isInstanceOf(AccountPublicJwksCache.SourceUnavailableException.class);
      clock.advance(Duration.ofSeconds(1));
    }

    assertThat(loads).hasValue(261);
    assertThat(rememberedUnknownKidRefreshCount(cache)).isEqualTo(256);
    assertThatThrownBy(() -> cache.keyFor("known"))
        .isInstanceOf(AccountPublicJwksCache.SourceUnavailableException.class);
    assertThat(loads).hasValue(262);
  }

  private static int rememberedUnknownKidRefreshCount(AccountPublicJwksCache cache)
      throws ReflectiveOperationException {
    java.lang.reflect.Field field =
        AccountPublicJwksCache.class.getDeclaredField("unknownKidsRefreshedForSnapshot");
    field.setAccessible(true);
    return ((java.util.Set<?>) field.get(cache)).size();
  }

  @Test
  void previousUnknownKidIsRetriedAfterCacheAgeAndCanResolveALaterPublishedKey() throws Exception {
    KeyPair known = rsa3072();
    KeyPair later = rsa3072();
    AtomicInteger loads = new AtomicInteger();
    AtomicReference<String> currentJwks =
        new AtomicReference<>(jwks(jwk("known", (RSAPublicKey) known.getPublic())));
    MutableClock clock = new MutableClock(INITIAL);
    AccountPublicJwksCache cache =
        cache(
            clock,
            () -> {
              loads.incrementAndGet();
              return snapshot(currentJwks.get());
            });
    assertThat(cache.keyFor("known")).isEqualTo(known.getPublic());
    assertThatThrownBy(() -> cache.keyFor("future"))
        .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);

    currentJwks.set(
        jwks(
            jwk("known", (RSAPublicKey) known.getPublic())
                + ","
                + jwk("future", (RSAPublicKey) later.getPublic())));
    clock.advance(Duration.ofSeconds(11));

    assertThat(cache.keyFor("future")).isEqualTo(later.getPublic());
    assertThat(loads).hasValue(3);
  }

  @Test
  void knownKeyUsesOnlyStillFreshCacheDuringUnavailableSourceAndNeverExtendsAge() throws Exception {
    KeyPair key = rsa3072();
    AtomicReference<Boolean> unavailable = new AtomicReference<>(false);
    MutableClock clock = new MutableClock(INITIAL);
    AccountPublicJwksCache cache =
        cache(
            clock,
            () -> {
              if (unavailable.get()) {
                throw new AccountPublicJwksCache.SourceUnavailableException();
              }
              return snapshot(jwks(jwk("known", (RSAPublicKey) key.getPublic())));
            });

    assertThat(cache.keyFor("known")).isEqualTo(key.getPublic());
    unavailable.set(true);
    clock.advance(Duration.ofSeconds(4));
    assertThat(cache.keyFor("known")).isEqualTo(key.getPublic());
    clock.advance(Duration.ofSeconds(7));
    assertThatThrownBy(() -> cache.keyFor("known"))
        .isInstanceOf(AccountPublicJwksCache.SourceUnavailableException.class);
  }

  @Test
  void repeatedStaleKnownKeyLookupsRemainUnavailableDuringSourceOutage() throws Exception {
    KeyPair key = rsa3072();
    AtomicInteger loads = new AtomicInteger();
    MutableClock clock = new MutableClock(INITIAL);
    AccountPublicJwksCache cache =
        cache(
            clock,
            () -> {
              if (loads.incrementAndGet() > 1) {
                throw new AccountPublicJwksCache.SourceUnavailableException();
              }
              return snapshot(jwks(jwk("known", (RSAPublicKey) key.getPublic())));
            });

    assertThat(cache.keyFor("known")).isEqualTo(key.getPublic());
    clock.advance(Duration.ofSeconds(11));

    assertThatThrownBy(() -> cache.keyFor("known"))
        .isInstanceOf(AccountPublicJwksCache.SourceUnavailableException.class);
    assertThatThrownBy(() -> cache.keyFor("known"))
        .isInstanceOf(AccountPublicJwksCache.SourceUnavailableException.class);
    assertThat(loads).hasValue(2);
  }

  @Test
  void repeatedStaleKnownKeyLookupsBackOffInvalidJwksAndRetryAfterOneSecond() throws Exception {
    KeyPair key = rsa3072();
    AtomicInteger loads = new AtomicInteger();
    AtomicReference<String> currentJwks =
        new AtomicReference<>(jwks(jwk("known", (RSAPublicKey) key.getPublic())));
    MutableClock clock = new MutableClock(INITIAL);
    AccountPublicJwksCache cache =
        cache(
            clock,
            () -> {
              loads.incrementAndGet();
              return snapshot(currentJwks.get());
            });

    assertThat(cache.keyFor("known")).isEqualTo(key.getPublic());
    clock.advance(Duration.ofSeconds(11));
    currentJwks.set("not-json");

    assertThatThrownBy(() -> cache.keyFor("known"))
        .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
    assertThatThrownBy(() -> cache.keyFor("known"))
        .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
    assertThat(loads).hasValue(2);

    currentJwks.set(jwks(jwk("known", (RSAPublicKey) key.getPublic())));
    clock.advance(Duration.ofSeconds(1));
    assertThat(cache.keyFor("known")).isEqualTo(key.getPublic());
    assertThat(loads).hasValue(3);
  }

  @Test
  void changedSourceIdentityAndReusedKidMaterialAreRejected() throws Exception {
    KeyPair first = rsa3072();
    KeyPair replacement = rsa3072();
    AtomicReference<AccountPublicJwksCache.PublicJwksSnapshot> next =
        new AtomicReference<>(snapshot(jwks(jwk("same", (RSAPublicKey) first.getPublic()))));
    MutableClock clock = new MutableClock(INITIAL);
    AccountPublicJwksCache cache = cache(clock, next::get);
    assertThat(cache.keyFor("same")).isEqualTo(first.getPublic());

    next.set(snapshot(jwks(jwk("same", (RSAPublicKey) replacement.getPublic()))));
    clock.advance(Duration.ofSeconds(11));
    assertThatThrownBy(() -> cache.keyFor("same"))
        .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);

    byte[] wrongSourceBytes =
        jwks(jwk("x", (RSAPublicKey) first.getPublic()))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    AccountPublicJwksCache wrongSource =
        cache(
            clock,
            () -> new AccountPublicJwksCache.PublicJwksSnapshot(otherPin(), wrongSourceBytes));
    assertThatThrownBy(() -> wrongSource.keyFor("x"))
        .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
  }

  @Test
  void hardCutoverDenialOverridesFreshCacheAndUnavailableSource() throws Exception {
    KeyPair key = rsa3072();
    AtomicReference<Boolean> unavailable = new AtomicReference<>(false);
    MutableClock clock = new MutableClock(INITIAL);
    AccountPublicJwksCache cache =
        cache(
            clock,
            () -> {
              if (unavailable.get()) {
                throw new AccountPublicJwksCache.SourceUnavailableException();
              }
              return snapshot(jwks(jwk("cut", (RSAPublicKey) key.getPublic())));
            });
    assertThat(cache.keyFor("cut")).isEqualTo(key.getPublic());
    cache.invalidateKid("cut");
    unavailable.set(true);

    assertThatThrownBy(() -> cache.keyFor("cut"))
        .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
  }

  @Test
  void hardCutoverBoundExhaustionQuarantinesAllKeysWithoutRefreshing() throws Exception {
    KeyPair revoked = rsa3072();
    KeyPair current = rsa3072();
    AtomicInteger loads = new AtomicInteger();
    AtomicReference<Boolean> unavailable = new AtomicReference<>(false);
    MutableClock clock = new MutableClock(INITIAL);
    AccountPublicJwksCache cache =
        cache(
            clock,
            () -> {
              loads.incrementAndGet();
              if (unavailable.get()) {
                throw new AccountPublicJwksCache.SourceUnavailableException();
              }
              return snapshot(
                  jwks(
                      jwk("revoked65", (RSAPublicKey) revoked.getPublic())
                          + ","
                          + jwk("current", (RSAPublicKey) current.getPublic())));
            });

    assertThat(cache.keyFor("revoked65")).isEqualTo(revoked.getPublic());
    assertThat(cache.keyFor("current")).isEqualTo(current.getPublic());
    assertThat(loads).hasValue(1);

    for (int i = 0; i < AccountPublicJwksCache.MAX_KEYS; i++) {
      cache.invalidateKid("denied" + i);
    }
    assertThat(cache.keyFor("current")).isEqualTo(current.getPublic());
    assertThatThrownBy(() -> cache.keyFor("denied0"))
        .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);

    assertThatThrownBy(() -> cache.invalidateKid("revoked65"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account public JWKS hard-cutover bound is exhausted");
    assertQuarantined(cache, "revoked65", "denied0", "current");
    assertThat(loads).hasValue(1);

    clock.advance(Duration.ofSeconds(11));
    assertQuarantined(cache, "revoked65", "denied0", "current");
    assertThat(loads).hasValue(1);

    unavailable.set(true);
    assertQuarantined(cache, "revoked65", "denied0", "current");
    assertThat(loads).hasValue(1);
  }

  private static void assertQuarantined(AccountPublicJwksCache cache, String... kids) {
    for (String kid : kids) {
      assertThatThrownBy(() -> cache.keyFor(kid))
          .isInstanceOf(AccountPublicJwksCache.UnknownKeyException.class);
    }
  }

  @Test
  void rejectsPrivateWeakDuplicateTrailingAndNonCanonicalJwks() throws Exception {
    KeyPair strong = rsa3072();
    String publicKey = jwk("valid", (RSAPublicKey) strong.getPublic());
    MutableClock clock = new MutableClock(INITIAL);
    for (String invalid :
        new String[] {
          jwks(publicKey.substring(0, publicKey.length() - 1) + ",\"d\":\"private\"}"),
          jwks(jwk("weak", (RSAPublicKey) rsa2048().getPublic())),
          "{\"keys\":[" + publicKey + "," + publicKey + "]}",
          jwks(publicKey) + " {}",
          jwks(publicKey.replace("\"key_ops\":[\"verify\"]", "\"key_ops\":[\"sign\"]"))
        }) {
      AccountPublicJwksCache cache = cache(clock, () -> snapshot(invalid));
      assertThatThrownBy(() -> cache.keyFor("valid"))
          .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
    }
  }

  private static AccountPublicJwksCache cache(
      MutableClock clock, AccountPublicJwksCache.TrustedPublicJwksSource source) {
    return new AccountPublicJwksCache(source, PIN, clock, Duration.ofSeconds(10));
  }

  private static AccountPublicJwksCache.PublicJwksSnapshot snapshot(String json) {
    return new AccountPublicJwksCache.PublicJwksSnapshot(
        PIN, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static AccountPublicJwksCache.SourceIdentity otherPin() {
    return new AccountPublicJwksCache.SourceIdentity(
        "prod",
        "cluster-b",
        "11111111-1111-4111-8111-111111111111",
        "firemud-prod",
        "22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333",
        "binding-7",
        "https://kubernetes.example:6443",
        "a".repeat(64));
  }

  private static KeyPair rsa3072() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    return generator.generateKeyPair();
  }

  private static KeyPair rsa2048() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static String jwks(String keys) {
    return "{\"keys\":[" + keys + "]}";
  }

  private static String jwk(String kid, RSAPublicKey key) {
    String modulus =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(unsigned(key.getModulus().toByteArray()));
    String exponent =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(unsigned(key.getPublicExponent().toByteArray()));
    return "{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\""
        + kid
        + "\",\"n\":\""
        + modulus
        + "\",\"e\":\""
        + exponent
        + "\",\"key_ops\":[\"verify\"]}";
  }

  private static byte[] unsigned(byte[] value) {
    if (value.length > 1 && value[0] == 0) {
      return java.util.Arrays.copyOfRange(value, 1, value.length);
    }
    return value;
  }

  private static final class MutableClock extends Clock {
    private volatile Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    void advance(Duration duration) {
      current = current.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }
  }
}
