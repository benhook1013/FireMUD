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
    private Instant current;

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
