package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.common.config.RedisProperties;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import net.firedevops.firemud.gamesession.service.MovementEffectIdempotencyService.MoveEffectApplyStatus;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Physical containment proof with controlled records, not issuer, grant or admission authority. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class SessionContextSecretAndPresenceRedisIntegrationTest {
  private static final String ACCOUNT = "b8d093f7-cb70-40ed-9fac-3c82d4bf28f1";
  private static final String RAW_JWT = "private-delegation-jwt-must-remain-process-local";
  private static final Duration STORE_TTL = Duration.ofMinutes(2);
  private static final Duration RETAINED_TTL = Duration.ofSeconds(20);
  private static final AtomicLong SEQUENCE = new AtomicLong(90_000L);

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  private final List<String> ownedKeys = new ArrayList<>();
  private LettuceConnectionFactory connectionFactory;
  private RedisTemplate<String, Object> template;
  private RedisSessionContextService sessions;
  private RedisMovementEffectIdempotencyService movement;
  private final long sessionId = SEQUENCE.incrementAndGet();
  private final long gameInstanceId = sessionId + 1_000L;
  private final long characterId = sessionId + 2_000L;

  @BeforeEach
  void setUp() {
    connectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
    connectionFactory.afterPropertiesSet();
    template =
        new DatabaseAutoConfiguration(new PostgresProperties(), new RedisProperties())
            .redisTemplate(connectionFactory);
    template.afterPropertiesSet();
    sessions = new RedisSessionContextService(template, STORE_TTL.toMillis());
    movement = new RedisMovementEffectIdempotencyService(template, STORE_TTL.toMillis());
    ownedKeys.addAll(contextKeys());
    ownedKeys.add(effectKey());
    ownedKeys.add(presenceKey());
    ownedKeys.add(gamePresenceKey());
    ownedKeys.add(accountPresenceKey());
  }

  @AfterEach
  void tearDown() {
    try {
      if (template != null) {
        // Only this case's exact keys; no database-wide cleanup or shared Redis ownership.
        template.delete(ownedKeys);
      }
    } finally {
      if (connectionFactory != null) {
        connectionFactory.destroy();
      }
    }
  }

  @Test
  void canonicalAndIndexWritesPhysicallyPersistOnlyTheNoSecretProjection() {
    SessionContext original = context("R-1021");

    sessions.save(original);

    for (String key : contextKeys()) {
      assertStoredContext(key, original.withoutJwt());
      assertThat(remainingTtl(key)).isPositive().isLessThanOrEqualTo(STORE_TTL.toMillis());
    }
    assertThat(original.jwt()).isEqualTo(RAW_JWT);
  }

  @Test
  void allFourLegacyLookupsScrubOnlyReturnedValuesWithoutChangingBytesOrExtendingTtl() {
    SessionContext legacy = context("R-1021");
    for (String key : contextKeys()) {
      // Seed actual legacy serialization, bypassing the corrected writer intentionally.
      template.opsForValue().set(key, legacy, RETAINED_TTL);
      assertThat(new String(rawValue(key), StandardCharsets.ISO_8859_1)).contains(RAW_JWT);
    }
    Map<String, RetainedValue> retained = snapshot(contextKeys());

    assertThat(sessions.findByTenantAndSessionId(42L, sessionId)).contains(legacy.withoutJwt());
    assertThat(sessions.findBySessionId(sessionId)).contains(legacy.withoutJwt());
    assertThat(sessions.findByGameplayIdentity(42L, gameInstanceId, characterId))
        .contains(legacy.withoutJwt());
    assertThat(sessions.findByGameplayName(42L, gameInstanceId, "Player"))
        .contains(legacy.withoutJwt());

    assertRetainedUnchanged(retained);
    for (String key : contextKeys()) {
      assertThat(template.opsForValue().get(key)).isEqualTo(legacy);
    }
  }

  @Test
  void movementAndEffectWritesScrubLegacyJwtAndExactRetryPreservesPhysicalValuesAndTtl() {
    SessionContext legacy = context("R-1021");
    for (String key : contextKeys()) {
      template.opsForValue().set(key, legacy, RETAINED_TTL);
    }

    var applied = movement.apply("physical-move", legacy, "R-2045");

    assertThat(applied.status()).isEqualTo(MoveEffectApplyStatus.APPLIED);
    SessionContext expected = context("R-2045").withoutJwt();
    assertThat(applied.context()).isEqualTo(expected);
    List<String> writtenKeys = new ArrayList<>(contextKeys());
    writtenKeys.add(effectKey());
    for (String key : writtenKeys) {
      assertStoredContext(key, expected);
      // A shorter real deadline makes any retry refresh to the writer TTL observable.
      assertThat(template.expire(key, RETAINED_TTL)).isTrue();
    }
    Map<String, RetainedValue> retained = snapshot(writtenKeys);

    var replayed = movement.apply("physical-move", legacy, "R-2045");

    assertThat(replayed.status()).isEqualTo(MoveEffectApplyStatus.REPLAYED);
    assertThat(replayed.context()).isEqualTo(expected);
    assertRetainedUnchanged(retained);
    assertThat(legacy.jwt()).isEqualTo(RAW_JWT);
  }

  @ParameterizedTest
  @EnumSource(
      value = GameplayPresenceRole.class,
      names = {"GOD", "ADMIN", "MODERATOR"})
  void retainedElevationReadsProjectPlayerWithoutMutationAndActivityStoresPlayer(
      GameplayPresenceRole retainedRole) {
    GameplayPresence legacy = presence(retainedRole, 1_200L, 1_300L);
    template.opsForValue().set(presenceKey(), legacy, RETAINED_TTL);
    template.opsForSet().add(gamePresenceKey(), Long.toString(sessionId));
    template.opsForSet().add(accountPresenceKey(), Long.toString(sessionId));
    assertThat(template.expire(gamePresenceKey(), RETAINED_TTL)).isTrue();
    assertThat(template.expire(accountPresenceKey(), RETAINED_TTL)).isTrue();
    Map<String, RetainedValue> retained = snapshot(List.of(presenceKey()));
    long gameIndexTtl = remainingTtl(gamePresenceKey());
    long accountIndexTtl = remainingTtl(accountPresenceKey());
    var presence = new RedisGameplayPresenceService(template, STORE_TTL.toMillis(), () -> 9_000L);
    GameplayPresence projected = presence(GameplayPresenceRole.PLAYER, 1_200L, 1_300L);

    assertThat(presence.findConnectedBySessionId(sessionId)).contains(projected);
    assertThat(presence.listConnectedByGameInstance(42L, gameInstanceId))
        .containsExactly(projected);
    assertThat(presence.listConnectedByAccountIds(42L, List.of(ACCOUNT)))
        .containsExactlyEntriesOf(Map.of(ACCOUNT, List.of(projected)));

    assertRetainedUnchanged(retained);
    assertThat(template.opsForValue().get(presenceKey())).isEqualTo(legacy);
    assertThat(remainingTtl(gamePresenceKey())).isPositive().isLessThanOrEqualTo(gameIndexTtl);
    assertThat(remainingTtl(accountPresenceKey()))
        .isPositive()
        .isLessThanOrEqualTo(accountIndexTtl);
    assertThat(template.opsForSet().members(gamePresenceKey()))
        .containsExactly(Long.toString(sessionId));
    assertThat(template.opsForSet().members(accountPresenceKey()))
        .containsExactly(Long.toString(sessionId));

    presence.recordCommandActivity(sessionId, true);

    assertThat(template.opsForValue().get(presenceKey()))
        .isEqualTo(presence(GameplayPresenceRole.PLAYER, 9_000L, 9_000L));
    assertThat(remainingTtl(presenceKey())).isGreaterThan(RETAINED_TTL.toMillis());
  }

  private SessionContext context(String room) {
    return new SessionContext(
        sessionId,
        42L,
        ACCOUNT,
        "player@example.com",
        characterId,
        "Player",
        gameInstanceId,
        room,
        RAW_JWT,
        "fr",
        gameInstanceId,
        "demo",
        "production",
        7L,
        "SHARED",
        "retained-scope",
        "retained-request");
  }

  private GameplayPresence presence(GameplayPresenceRole role, long accepted, long meaningful) {
    return new GameplayPresence(
        sessionId,
        42L,
        gameInstanceId,
        "SHARED",
        "demo",
        "production",
        7L,
        ACCOUNT,
        characterId,
        "Player",
        role,
        1_000L,
        1_100L,
        accepted,
        meaningful);
  }

  private List<String> contextKeys() {
    return List.of(
        SessionContextRedisKeys.contextKey(42L, sessionId),
        SessionContextRedisKeys.sessionKey(sessionId),
        SessionContextRedisKeys.identityKey(42L, gameInstanceId, characterId),
        SessionContextRedisKeys.nameKey(42L, gameInstanceId, "Player"));
  }

  private String effectKey() {
    return SessionContextRedisKeys.movementEffectKey(42L, sessionId, "physical-move");
  }

  private String presenceKey() {
    return "gameplaypresence:session:" + sessionId;
  }

  private String gamePresenceKey() {
    return "gameplaypresence:42:" + gameInstanceId + ":sessions";
  }

  private String accountPresenceKey() {
    return "gameplaypresence:42:account:" + ACCOUNT + ":sessions";
  }

  private void assertStoredContext(String key, SessionContext expected) {
    assertThat(template.opsForValue().get(key)).isEqualTo(expected);
    assertThat(new String(rawValue(key), StandardCharsets.ISO_8859_1)).doesNotContain(RAW_JWT);
  }

  private Map<String, RetainedValue> snapshot(List<String> keys) {
    Map<String, RetainedValue> retained = new LinkedHashMap<>();
    for (String key : keys) {
      long ttl = remainingTtl(key);
      assertThat(ttl).isPositive();
      retained.put(key, new RetainedValue(rawValue(key), ttl));
    }
    return retained;
  }

  private void assertRetainedUnchanged(Map<String, RetainedValue> retained) {
    retained.forEach(
        (key, value) -> {
          assertThat(rawValue(key)).containsExactly(value.bytes());
          assertThat(remainingTtl(key)).isPositive().isLessThanOrEqualTo(value.ttlMillis());
        });
  }

  private long remainingTtl(String key) {
    return Objects.requireNonNull(template.getExpire(key, TimeUnit.MILLISECONDS));
  }

  private byte[] rawValue(String key) {
    byte[] serializedKey = serializedKey(key);
    return Objects.requireNonNull(
        template.execute(
            (RedisCallback<byte[]>) connection -> connection.stringCommands().get(serializedKey)));
  }

  @SuppressWarnings("unchecked")
  private byte[] serializedKey(String key) {
    return Objects.requireNonNull(
        ((RedisSerializer<String>) Objects.requireNonNull(template.getKeySerializer()))
            .serialize(key));
  }

  private record RetainedValue(byte[] bytes, long ttlMillis) {
    private RetainedValue {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}
