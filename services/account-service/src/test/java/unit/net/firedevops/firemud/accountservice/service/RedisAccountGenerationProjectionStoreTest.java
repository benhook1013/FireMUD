package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader.AccountSourceSnapshot;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.accountservice.service.RedisAccountGenerationProjectionStore;
import net.firedevops.firemud.common.account.authority.AccountLogoutAllAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisAccountGenerationProjectionStoreTest {
  private static final String PASSWORD = "coordination-secret";
  private static final UUID ACCOUNT_ID = UUID.fromString("c980fa44-619e-4ca4-8ad6-75b0538a66a3");
  private static final String ACCOUNT_TEXT = ACCOUNT_ID.toString();
  private static final String KEY = "session:auth:generation:account:" + ACCOUNT_TEXT;
  private static final String STREAM_KEY = "account:auth-authority:v1:account/" + ACCOUNT_TEXT;

  @Test
  void requiresAccountCoordinationPrincipalAndSeparateCacheEndpoint() {
    assertThatThrownBy(
            () ->
                new RedisAccountGenerationProjectionStore.CoordinationEndpoint(
                    "coordination.internal", 6379, "gamesession_coord_app", PASSWORD))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("account_coord_app");
    assertThatThrownBy(
            () ->
                new RedisAccountGenerationProjectionStore.CoordinationEndpoint(
                    "coordination.internal", 6379, "account_coord_app", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("credential");

    var coordination = coordination("coordination.internal", 6379);
    var cache =
        new RedisAccountGenerationProjectionStore.CacheRateLimitEndpoint(
            "COORDINATION.INTERNAL.", 6379);
    assertThatThrownBy(
            () -> newStore(mock(AccountAuthoritySourceReader.class), coordination, cache))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoints must be distinct");
    assertThat(coordination.toString()).contains("password=<redacted>").doesNotContain(PASSWORD);
  }

  @Test
  void constructorDoesNotAcceptAmbientRedisTemplatesOrOpenRedis() {
    RedisAccountGenerationProjectionStore store =
        newStore(
            mock(AccountAuthoritySourceReader.class),
            coordination("coord.internal", 6379),
            cache());

    assertThatThrownBy(() -> store.refreshCurrent(ACCOUNT_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("explicit init()");
    boolean acceptsAmbientTemplate =
        Arrays.stream(RedisAccountGenerationProjectionStore.class.getConstructors())
            .anyMatch(
                constructor ->
                    Arrays.stream(constructor.getParameterTypes())
                        .anyMatch(StringRedisTemplate.class::isAssignableFrom));
    assertThat(acceptsAmbientTemplate).isFalse();
    store.close();
  }

  @Test
  void rebuildsMissingProjectionOnlyFromOwnerCurrentSnapshotAndVerifiesExactBytes()
      throws Exception {
    AccountSourceSnapshot baseline = source(1L, 1L, 0L, null, 1L);
    Harness harness = harness(new FakeRedis(), baseline, baseline);

    RedisAccountGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(ACCOUNT_ID);

    assertThat(result.outcome()).isEqualTo(RedisAccountGenerationProjectionStore.Outcome.APPLIED);
    assertThat(result.snapshot()).isPresent();
    assertThat(result.snapshot().orElseThrow().key()).isEqualTo(KEY);
    assertThat(result.snapshot().orElseThrow().json()).contains("\"outboxSequence\":\"0\"");
    assertThat(harness.redis().bytes)
        .isEqualTo(result.snapshot().orElseThrow().json().getBytes(StandardCharsets.UTF_8));
    verify(harness.reader(), times(2)).readCurrent(ACCOUNT_ID);
    harness.store().close();
  }

  @Test
  void exactSnapshotUsesNonMutatingVerificationAndReturnsReplay() throws Exception {
    AccountSourceSnapshot baseline = source(1L, 1L, 0L, null, 1L);
    AccountGenerationProjection projection = AccountGenerationProjection.fromSource(baseline);
    FakeRedis redis = new FakeRedis(projection.toJson().getBytes(StandardCharsets.UTF_8));
    Harness harness = harness(redis, baseline, baseline);

    RedisAccountGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(ACCOUNT_ID);

    assertThat(result.outcome()).isEqualTo(RedisAccountGenerationProjectionStore.Outcome.REPLAYED);
    assertThat(redis.lastMode).isEqualTo("VERIFY");
    assertThat(redis.scriptCalls).isEqualTo(1);
    verify(harness.reader(), times(2)).readCurrent(ACCOUNT_ID);
    harness.store().close();
  }

  @Test
  void ttlBearingProjectionIsQuarantinedWithoutMutation() throws Exception {
    AccountSourceSnapshot baseline = source(1L, 1L, 0L, null, 1L);
    AccountGenerationProjection projection = AccountGenerationProjection.fromSource(baseline);
    FakeRedis redis = new FakeRedis(projection.toJson().getBytes(StandardCharsets.UTF_8));
    redis.ttlMillis = 10_000L;
    Harness harness = harness(redis, baseline);

    RedisAccountGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(ACCOUNT_ID);

    assertThat(result.outcome())
        .isEqualTo(RedisAccountGenerationProjectionStore.Outcome.QUARANTINED);
    assertThat(result.detail()).contains("TTL_PRESENT");
    assertThat(redis.scriptCalls).isZero();
    verify(harness.reader(), times(1)).readCurrent(ACCOUNT_ID);
    harness.store().close();
  }

  @Test
  void sourceRegressionNeverOverwritesAnExistingHigherProjection() throws Exception {
    AccountSourceSnapshot baseline = source(1L, 1L, 0L, null, 1L);
    AccountGenerationProjection newer =
        AccountGenerationProjection.fromSource(
            source(2L, 2L, 1L, logoutEvent("2", "2", "1", null), 2L));
    FakeRedis redis = new FakeRedis(newer.toJson().getBytes(StandardCharsets.UTF_8));
    Harness harness = harness(redis, baseline);

    RedisAccountGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(ACCOUNT_ID);

    assertThat(result.outcome())
        .isEqualTo(RedisAccountGenerationProjectionStore.Outcome.STALE_SOURCE);
    assertThat(result.snapshot()).isPresent();
    assertThat(result.snapshot().orElseThrow().json()).isEqualTo(newer.toJson());
    assertThat(redis.scriptCalls).isZero();
    assertThat(redis.bytes).isEqualTo(newer.toJson().getBytes(StandardCharsets.UTF_8));
    harness.store().close();
  }

  @Test
  void sameCheckpointDisagreementFailsClosed() throws Exception {
    Event storedEvent = logoutEvent("2", "2", "1", "11111111-1111-4111-8111-111111111111");
    Event sourceEvent = logoutEvent("2", "2", "1", "22222222-2222-4222-8222-222222222222");
    AccountGenerationProjection stored =
        AccountGenerationProjection.fromSource(source(2L, 2L, 1L, storedEvent, 2L));
    AccountSourceSnapshot candidate = source(2L, 2L, 1L, sourceEvent, 3L);
    FakeRedis redis = new FakeRedis(stored.toJson().getBytes(StandardCharsets.UTF_8));
    Harness harness = harness(redis, candidate);

    RedisAccountGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(ACCOUNT_ID);

    assertThat(result.outcome())
        .isEqualTo(RedisAccountGenerationProjectionStore.Outcome.QUARANTINED);
    assertThat(result.detail()).contains("SAME_CHECKPOINT_DISAGREEMENT");
    assertThat(redis.scriptCalls).isZero();
    harness.store().close();
  }

  @Test
  void completeSecurityStateSnapshotReplaysWithExactCanonicalBytes() throws Exception {
    // Synthetic owner snapshot: this proves projection consumption, not receipt-backed SQL reads.
    AccountSourceSnapshot source = source(2L, 2L, 1L, securityEvent(true), 2L);
    AccountGenerationProjection projection = AccountGenerationProjection.fromSource(source);
    FakeRedis redis = new FakeRedis(projection.toJson().getBytes(StandardCharsets.UTF_8));
    Harness harness = harness(redis, source, source);

    var result = harness.store().refreshCurrent(ACCOUNT_ID);

    assertThat(result.outcome()).isEqualTo(RedisAccountGenerationProjectionStore.Outcome.REPLAYED);
    assertThat(redis.lastMode).isEqualTo("VERIFY");
    assertThat(redis.bytes).isEqualTo(projection.toJson().getBytes(StandardCharsets.UTF_8));
    verify(harness.reader(), times(2)).readCurrent(ACCOUNT_ID);
    harness.store().close();
  }

  @Test
  void sameSecurityCheckpointWithResealedChangedStateIsQuarantinedWithoutWrite() throws Exception {
    // Both events have valid digests and identical request/counters; complete payloads still
    // differ.
    AccountGenerationProjection stored =
        AccountGenerationProjection.fromSource(source(2L, 2L, 1L, securityEvent(true), 2L));
    AccountSourceSnapshot changed = source(2L, 2L, 1L, securityEvent(false), 2L);
    FakeRedis redis = new FakeRedis(stored.toJson().getBytes(StandardCharsets.UTF_8));
    Harness harness = harness(redis, changed);

    var result = harness.store().refreshCurrent(ACCOUNT_ID);

    assertThat(result.outcome())
        .isEqualTo(RedisAccountGenerationProjectionStore.Outcome.QUARANTINED);
    assertThat(result.detail()).contains("SAME_CHECKPOINT_DISAGREEMENT");
    assertThat(redis.scriptCalls).isZero();
    assertThat(redis.bytes).isEqualTo(stored.toJson().getBytes(StandardCharsets.UTF_8));
    verify(harness.reader(), times(1)).readCurrent(ACCOUNT_ID);
    harness.store().close();
  }

  @Test
  void staleCasReturnsWithoutBlindRetry() throws Exception {
    AccountSourceSnapshot baseline = source(1L, 1L, 0L, null, 1L);
    AccountSourceSnapshot advanced = source(2L, 2L, 1L, logoutEvent("2", "2", "1", null), 2L);
    FakeRedis redis =
        new FakeRedis(
            AccountGenerationProjection.fromSource(baseline)
                .toJson()
                .getBytes(StandardCharsets.UTF_8));
    redis.forcedScriptResult = "STALE";
    Harness harness = harness(redis, advanced);

    RedisAccountGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(ACCOUNT_ID);

    assertThat(result.outcome()).isEqualTo(RedisAccountGenerationProjectionStore.Outcome.STALE);
    assertThat(redis.scriptCalls).isEqualTo(1);
    verify(harness.reader(), times(1)).readCurrent(ACCOUNT_ID);
    harness.store().close();
  }

  @Test
  void postWriteReadbackChangedByNewerWriterCannotReturnSuccess() throws Exception {
    AccountSourceSnapshot baseline = source(1L, 1L, 0L, null, 1L);
    AccountSourceSnapshot candidateSource =
        source(2L, 2L, 1L, logoutEvent("2", "2", "1", null), 2L);
    AccountGenerationProjection newer =
        AccountGenerationProjection.fromSource(
            source(
                3L,
                3L,
                2L,
                logoutEvent("3", "3", "2", "33333333-3333-4333-8333-333333333333"),
                3L));
    FakeRedis redis =
        new FakeRedis(
            AccountGenerationProjection.fromSource(baseline)
                .toJson()
                .getBytes(StandardCharsets.UTF_8));
    redis.afterWriteBytes = newer.toJson().getBytes(StandardCharsets.UTF_8);
    Harness harness = harness(redis, candidateSource);

    RedisAccountGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(ACCOUNT_ID);

    assertThat(result.outcome()).isEqualTo(RedisAccountGenerationProjectionStore.Outcome.STALE);
    assertThat(result.snapshot()).isPresent();
    assertThat(result.snapshot().orElseThrow().json()).isEqualTo(newer.toJson());
    assertThat(redis.bytes).isEqualTo(newer.toJson().getBytes(StandardCharsets.UTF_8));
    verify(harness.reader(), times(1)).readCurrent(ACCOUNT_ID);
    harness.store().close();
  }

  @Test
  void sourceAdvanceAfterSuccessfulRedisReadbackReturnsSourceChangedWithObservation()
      throws Exception {
    AccountSourceSnapshot baseline = source(1L, 1L, 0L, null, 1L);
    AccountSourceSnapshot advanced = source(2L, 2L, 1L, logoutEvent("2", "2", "1", null), 2L);
    FakeRedis redis = new FakeRedis();
    Harness harness = harness(redis, baseline, advanced);

    RedisAccountGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(ACCOUNT_ID);

    assertThat(result.outcome())
        .isEqualTo(RedisAccountGenerationProjectionStore.Outcome.SOURCE_CHANGED);
    assertThat(result.snapshot()).isPresent();
    assertThat(result.snapshot().orElseThrow().json())
        .isEqualTo(AccountGenerationProjection.fromSource(baseline).toJson());
    assertThat(redis.bytes)
        .isEqualTo(
            AccountGenerationProjection.fromSource(baseline)
                .toJson()
                .getBytes(StandardCharsets.UTF_8));
    assertThat(redis.scriptCalls).isEqualTo(1);
    verify(harness.reader(), times(2)).readCurrent(ACCOUNT_ID);
    harness.store().close();
  }

  @Test
  void malformedStoredProjectionIsQuarantined() throws Exception {
    AccountSourceSnapshot baseline = source(1L, 1L, 0L, null, 1L);
    FakeRedis redis = new FakeRedis("{\"unknown\":true}".getBytes(StandardCharsets.UTF_8));
    Harness harness = harness(redis, baseline);

    RedisAccountGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(ACCOUNT_ID);

    assertThat(result.outcome())
        .isEqualTo(RedisAccountGenerationProjectionStore.Outcome.QUARANTINED);
    assertThat(result.detail()).contains("MALFORMED_STORED_PROJECTION");
    assertThat(redis.scriptCalls).isZero();
    harness.store().close();
  }

  @Test
  void redisConnectionFailureRemainsVisibleToTheCaller() throws Exception {
    AccountSourceSnapshot baseline = source(1L, 1L, 0L, null, 1L);
    FakeRedis redis = new FakeRedis();
    redis.readFailure = new DataAccessResourceFailureException("Coordination Redis unavailable");
    Harness harness = harness(redis, baseline);

    assertThatThrownBy(() -> harness.store().refreshCurrent(ACCOUNT_ID))
        .isSameAs(redis.readFailure);
    harness.store().close();
  }

  private static Harness harness(FakeRedis redis, AccountSourceSnapshot... sources)
      throws Exception {
    AccountAuthoritySourceReader reader = mock(AccountAuthoritySourceReader.class);
    AtomicInteger sourceIndex = new AtomicInteger();
    doAnswer(ignored -> sources[Math.min(sourceIndex.getAndIncrement(), sources.length - 1)])
        .when(reader)
        .readCurrent(ACCOUNT_ID);

    RedisConnection connection = mock(RedisConnection.class);
    RedisStringCommands stringCommands = mock(RedisStringCommands.class);
    RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
    RedisScriptingCommands scriptingCommands = mock(RedisScriptingCommands.class);
    org.mockito.Mockito.when(connection.stringCommands()).thenReturn(stringCommands);
    org.mockito.Mockito.when(connection.keyCommands()).thenReturn(keyCommands);
    org.mockito.Mockito.when(connection.scriptingCommands()).thenReturn(scriptingCommands);
    org.mockito.Mockito.when(stringCommands.get(any(byte[].class)))
        .thenAnswer(
            ignored -> {
              if (redis.readFailure != null) {
                throw redis.readFailure;
              }
              return redis.bytes == null ? null : redis.bytes.clone();
            });
    org.mockito.Mockito.when(keyCommands.pTtl(any(byte[].class)))
        .thenAnswer(ignored -> redis.ttlMillis);
    org.mockito.Mockito.when(scriptingCommands.scriptLoad(any(byte[].class)))
        .thenAnswer(invocation -> sha1(invocation.getArgument(0)));

    StringRedisTemplate template = mock(StringRedisTemplate.class);
    doAnswer(
            invocation -> {
              RedisCallback<?> callback = invocation.getArgument(0);
              return callback.doInRedis(connection);
            })
        .when(template)
        .execute(any(RedisCallback.class));
    doAnswer(
            invocation -> {
              redis.scriptCalls++;
              byte[][] keysAndArgs = (byte[][]) invocation.getRawArguments()[3];
              String mode = new String(keysAndArgs[1], StandardCharsets.US_ASCII);
              String expected = new String(keysAndArgs[2], StandardCharsets.UTF_8);
              String candidate = new String(keysAndArgs[3], StandardCharsets.UTF_8);
              redis.lastMode = mode;
              if (redis.forcedScriptResult != null) {
                return redis.forcedScriptResult.getBytes(StandardCharsets.US_ASCII);
              }
              if (redis.bytes != null && redis.ttlMillis != -1L) {
                return "TTL_PRESENT".getBytes(StandardCharsets.US_ASCII);
              }
              String current =
                  redis.bytes == null ? null : new String(redis.bytes, StandardCharsets.UTF_8);
              if ("VERIFY".equals(mode)) {
                return (expected.equals(current) ? "REPLAY" : "STALE")
                    .getBytes(StandardCharsets.US_ASCII);
              }
              if (candidate.equals(current)) {
                return "REPLAY".getBytes(StandardCharsets.US_ASCII);
              }
              if ("ABSENT".equals(mode) ? current != null : !expected.equals(current)) {
                return "STALE".getBytes(StandardCharsets.US_ASCII);
              }
              redis.bytes = candidate.getBytes(StandardCharsets.UTF_8);
              if (redis.afterWriteBytes != null) {
                redis.bytes = redis.afterWriteBytes.clone();
              }
              return "APPLIED".getBytes(StandardCharsets.US_ASCII);
            })
        .when(scriptingCommands)
        .evalSha(any(String.class), eq(ReturnType.VALUE), eq(1), any(byte[][].class));

    RedisAccountGenerationProjectionStore store =
        newStore(
            reader,
            coordination("coordination.internal", 6379),
            new RedisAccountGenerationProjectionStore.CacheRateLimitEndpoint(
                "cache.internal", 6380));
    Field templateField =
        RedisAccountGenerationProjectionStore.class.getDeclaredField("redisTemplate");
    templateField.setAccessible(true);
    templateField.set(store, template);
    return new Harness(store, reader, redis);
  }

  private static RedisAccountGenerationProjectionStore newStore(
      AccountAuthoritySourceReader reader,
      RedisAccountGenerationProjectionStore.CoordinationEndpoint coordination,
      RedisAccountGenerationProjectionStore.CacheRateLimitEndpoint cache) {
    return new RedisAccountGenerationProjectionStore(reader, coordination, cache);
  }

  private static RedisAccountGenerationProjectionStore.CoordinationEndpoint coordination(
      String host, int port) {
    return new RedisAccountGenerationProjectionStore.CoordinationEndpoint(
        host, port, "account_coord_app", PASSWORD);
  }

  private static RedisAccountGenerationProjectionStore.CacheRateLimitEndpoint cache() {
    return new RedisAccountGenerationProjectionStore.CacheRateLimitEndpoint("cache.internal", 6380);
  }

  private static String sha1(byte[] script) throws NoSuchAlgorithmException {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(script));
  }

  private static AccountSourceSnapshot source(
      long generation, long sourceVersion, long sequence, Event event, long fenceValue) {
    return new AccountSourceSnapshot(
        ACCOUNT_ID,
        new ScopeState(
            AuthorityScope.account(ACCOUNT_ID),
            generation,
            sourceVersion,
            new IssuanceFence(ACCOUNT_ID, fenceValue, fenceValue)),
        STREAM_KEY,
        sequence,
        java.util.Optional.ofNullable(event));
  }

  private static Event logoutEvent(
      String generation, String sourceVersion, String sequence, String requestId) {
    String exactRequestId = requestId == null ? "11111111-1111-4111-8111-111111111111" : requestId;
    var evidence =
        AccountLogoutAllAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", AccountLogoutAllAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", AccountLogoutAllAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry(
                    "eventId",
                    AccountLogoutAllAuthorityEventV1Codec.EVENT_ID_PREFIX + exactRequestId),
                Map.entry("requestId", exactRequestId),
                Map.entry("accountId", ACCOUNT_TEXT),
                Map.entry("sourceScope", "account/" + ACCOUNT_TEXT),
                Map.entry("outboxStreamKey", STREAM_KEY),
                Map.entry("outboxSequence", sequence),
                Map.entry("accountAuthorityGeneration", generation),
                Map.entry("sourceVersion", sourceVersion),
                Map.entry(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration", generation,
                        "outboxStreamKey", STREAM_KEY,
                        "outboxSequence", sequence))));
    return new Event(
        STREAM_KEY,
        exactRequestId,
        Long.parseLong(sequence),
        evidence.eventId(),
        evidence.eventDigest(),
        evidence.canonicalJsonUtf8());
  }

  private static Event securityEvent(boolean emailVerified) {
    String requestId = "11111111-1111-4111-8111-111111111111";
    var evidence =
        AccountSecurityStateAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry(
                    "schemaVersion", AccountSecurityStateAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", AccountSecurityStateAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry(
                    "eventId",
                    AccountSecurityStateAuthorityEventV1Codec.EVENT_ID_PREFIX + requestId),
                Map.entry("requestId", requestId),
                Map.entry("accountId", ACCOUNT_TEXT),
                Map.entry("sourceScope", "account/" + ACCOUNT_TEXT),
                Map.entry("outboxStreamKey", STREAM_KEY),
                Map.entry("outboxSequence", "1"),
                Map.entry("accountAuthorityGeneration", "2"),
                Map.entry("sourceVersion", "2"),
                Map.entry(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration",
                        "2",
                        "outboxStreamKey",
                        STREAM_KEY,
                        "outboxSequence",
                        "1")),
                Map.entry("mutationKinds", List.of("EMAIL_LOGIN_ELIGIBILITY_CHANGED")),
                Map.entry(
                    "accountState",
                    Map.of(
                        "emailVerified",
                        emailVerified,
                        "loginAuthModes",
                        List.of("PASSWORD"),
                        "globalRoles",
                        List.of(),
                        "lifecycleState",
                        "ACTIVE"))));
    return new Event(
        STREAM_KEY,
        requestId,
        1L,
        evidence.eventId(),
        evidence.eventDigest(),
        evidence.canonicalJsonUtf8());
  }

  private record Harness(
      RedisAccountGenerationProjectionStore store,
      AccountAuthoritySourceReader reader,
      FakeRedis redis) {}

  private static final class FakeRedis {
    private byte[] bytes;
    private long ttlMillis = -1L;
    private String forcedScriptResult;
    private byte[] afterWriteBytes;
    private RuntimeException readFailure;
    private int scriptCalls;
    private String lastMode;

    private FakeRedis() {}

    private FakeRedis(byte[] bytes) {
      this.bytes = bytes.clone();
    }
  }
}
