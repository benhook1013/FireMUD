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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.service.AccountTenantAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot;
import net.firedevops.firemud.accountservice.service.RedisTenantGenerationProjectionStore;
import net.firedevops.firemud.accountservice.service.TenantGenerationProjection;
import net.firedevops.firemud.accountservice.service.TenantGenerationProjectionRedisContract;
import net.firedevops.firemud.common.account.authority.TenantGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.TenantGenerationAuthorityEventV1Codec.TenantGenerationAuthorityEvent;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisTenantGenerationProjectionStoreTest {
  private static final String PASSWORD = "coordination-secret";
  private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final String TENANT_TEXT = TENANT_ID.toString();
  private static final String KEY = "session:auth:generation:tenant:" + TENANT_TEXT;
  private static final String STREAM_KEY = "account:auth-authority:v1:tenant/" + TENANT_TEXT;

  @Test
  void requiresAccountCoordinationPrincipalAndSeparateCacheEndpoint() {
    assertThatThrownBy(
            () ->
                new RedisTenantGenerationProjectionStore.CoordinationEndpoint(
                    "coordination.internal", 6379, "gamesession_coord_app", PASSWORD))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("account_coord_app");
    assertThatThrownBy(
            () ->
                new RedisTenantGenerationProjectionStore.CoordinationEndpoint(
                    "coordination.internal", 6379, "account_coord_app", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("credential");

    var coordination = coordination("coordination.internal", 6379);
    var cache =
        new RedisTenantGenerationProjectionStore.CacheRateLimitEndpoint(
            "COORDINATION.INTERNAL.", 6379);
    assertThatThrownBy(
            () -> newStore(mock(AccountTenantAuthorityEventProducer.class), coordination, cache))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoints must be distinct");
    assertThat(coordination.toString()).contains("password=<redacted>").doesNotContain(PASSWORD);
  }

  @Test
  void constructorRequiresExplicitInitAndDoesNotAcceptAmbientRedisTemplate() {
    RedisTenantGenerationProjectionStore store =
        newStore(
            mock(AccountTenantAuthorityEventProducer.class),
            coordination("coord.internal", 6379),
            cache());

    assertThatThrownBy(() -> store.refreshCurrent(TENANT_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("explicit init()");
    boolean acceptsAmbientTemplate =
        java.util.Arrays.stream(RedisTenantGenerationProjectionStore.class.getConstructors())
            .anyMatch(
                constructor ->
                    java.util.Arrays.stream(constructor.getParameterTypes())
                        .anyMatch(StringRedisTemplate.class::isAssignableFrom));
    assertThat(acceptsAmbientTemplate).isFalse();
    store.close();
  }

  @Test
  void installsProvedOriginalBaselineAndRequiresSourceReadbackAfterExactRedisBytes()
      throws Exception {
    TenantAuthoritySnapshot baseline = source(1L, 1L, 0L, null);
    Harness harness = harness(new FakeRedis(), baseline, baseline);

    RedisTenantGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(TENANT_ID);

    assertThat(result.outcome()).isEqualTo(RedisTenantGenerationProjectionStore.Outcome.APPLIED);
    assertThat(result.snapshot()).isPresent();
    assertThat(result.snapshot().orElseThrow().key()).isEqualTo(KEY);
    assertThat(result.snapshot().orElseThrow().json()).contains("\"outboxSequence\":\"0\"");
    assertThat(harness.redis().bytes)
        .isEqualTo(result.snapshot().orElseThrow().json().getBytes(StandardCharsets.UTF_8));
    verify(harness.source(), times(2)).readCurrent(TENANT_ID);
    harness.store().close();
  }

  @Test
  void exactSnapshotUsesRegisteredNonMutatingVerificationAndReturnsReplay() throws Exception {
    TenantAuthoritySnapshot baseline = source(1L, 1L, 0L, null);
    TenantGenerationProjection projection = TenantGenerationProjection.fromSource(baseline);
    FakeRedis redis = new FakeRedis(projection.toJson().getBytes(StandardCharsets.UTF_8));
    Harness harness = harness(redis, baseline, baseline);

    RedisTenantGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(TENANT_ID);

    assertThat(result.outcome()).isEqualTo(RedisTenantGenerationProjectionStore.Outcome.REPLAYED);
    assertThat(redis.lastMode).isEqualTo("VERIFY");
    assertThat(redis.scriptCalls).isEqualTo(1);
    assertThat(redis.commandOrder).containsExactly("SCRIPT LOAD", "EVALSHA");
    assertThat(redis.loadedScriptSha256)
        .isEqualTo(TenantGenerationProjectionRedisContract.descriptor().sha256());
    assertThat(redis.loadedScriptSha1).isEqualTo(redis.evalShaArgument);
    verify(harness.source(), times(2)).readCurrent(TENANT_ID);
    harness.store().close();
  }

  @Test
  void ttlBearingProjectionIsQuarantinedWithoutMutation() throws Exception {
    TenantAuthoritySnapshot baseline = source(1L, 1L, 0L, null);
    TenantGenerationProjection projection = TenantGenerationProjection.fromSource(baseline);
    FakeRedis redis = new FakeRedis(projection.toJson().getBytes(StandardCharsets.UTF_8));
    redis.ttlMillis = 10_000L;
    Harness harness = harness(redis, baseline);

    RedisTenantGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(TENANT_ID);

    assertThat(result.outcome())
        .isEqualTo(RedisTenantGenerationProjectionStore.Outcome.QUARANTINED);
    assertThat(result.detail()).contains("TTL_PRESENT");
    assertThat(redis.scriptCalls).isZero();
    verify(harness.source(), times(1)).readCurrent(TENANT_ID);
    harness.store().close();
  }

  @Test
  void sourceRegressionNeverOverwritesAnExistingHigherProjection() throws Exception {
    TenantAuthoritySnapshot baseline = source(1L, 1L, 0L, null);
    TenantGenerationProjection newer =
        TenantGenerationProjection.fromSource(source(3L, 3L, 2L, tenantEvent("3", "3", "2")));
    FakeRedis redis = new FakeRedis(newer.toJson().getBytes(StandardCharsets.UTF_8));
    Harness harness = harness(redis, baseline);

    RedisTenantGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(TENANT_ID);

    assertThat(result.outcome())
        .isEqualTo(RedisTenantGenerationProjectionStore.Outcome.STALE_SOURCE);
    assertThat(result.snapshot()).isPresent();
    assertThat(result.snapshot().orElseThrow().json()).isEqualTo(newer.toJson());
    assertThat(redis.scriptCalls).isZero();
    assertThat(redis.bytes).isEqualTo(newer.toJson().getBytes(StandardCharsets.UTF_8));
    harness.store().close();
  }

  @Test
  void sameCheckpointDisagreementFailsClosedWithoutCallingRedisCas() throws Exception {
    TenantGenerationProjection stored =
        TenantGenerationProjection.fromSource(
            source(2L, 2L, 1L, tenantEvent("2", "2", "1", "11111111-1111-4111-8111-111111111111")));
    TenantAuthoritySnapshot conflicting =
        source(2L, 2L, 1L, tenantEvent("2", "2", "1", "22222222-2222-4222-8222-222222222222"));
    FakeRedis redis = new FakeRedis(stored.toJson().getBytes(StandardCharsets.UTF_8));
    Harness harness = harness(redis, conflicting);

    RedisTenantGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(TENANT_ID);

    assertThat(result.outcome())
        .isEqualTo(RedisTenantGenerationProjectionStore.Outcome.QUARANTINED);
    assertThat(result.detail()).contains("SAME_CHECKPOINT_DISAGREEMENT");
    assertThat(redis.scriptCalls).isZero();
    harness.store().close();
  }

  @Test
  void staleCasReturnsWithoutBlindRetry() throws Exception {
    TenantAuthoritySnapshot baseline = source(1L, 1L, 0L, null);
    TenantAuthoritySnapshot advanced = source(2L, 2L, 1L, tenantEvent("2", "2", "1"));
    FakeRedis redis =
        new FakeRedis(
            TenantGenerationProjection.fromSource(baseline)
                .toJson()
                .getBytes(StandardCharsets.UTF_8));
    redis.forcedScriptResult = "STALE";
    Harness harness = harness(redis, advanced);

    RedisTenantGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(TENANT_ID);

    assertThat(result.outcome()).isEqualTo(RedisTenantGenerationProjectionStore.Outcome.STALE);
    assertThat(redis.scriptCalls).isEqualTo(1);
    verify(harness.source(), times(1)).readCurrent(TENANT_ID);
    harness.store().close();
  }

  @Test
  void changedRedisReadbackCannotReturnSuccess() throws Exception {
    TenantAuthoritySnapshot baseline = source(1L, 1L, 0L, null);
    TenantAuthoritySnapshot candidateSource = source(2L, 2L, 1L, tenantEvent("2", "2", "1"));
    TenantGenerationProjection newer =
        TenantGenerationProjection.fromSource(
            source(3L, 3L, 2L, tenantEvent("3", "3", "2", "33333333-3333-4333-8333-333333333333")));
    FakeRedis redis =
        new FakeRedis(
            TenantGenerationProjection.fromSource(baseline)
                .toJson()
                .getBytes(StandardCharsets.UTF_8));
    redis.afterWriteBytes = newer.toJson().getBytes(StandardCharsets.UTF_8);
    Harness harness = harness(redis, candidateSource);

    RedisTenantGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(TENANT_ID);

    assertThat(result.outcome()).isEqualTo(RedisTenantGenerationProjectionStore.Outcome.STALE);
    assertThat(result.snapshot()).isPresent();
    assertThat(result.snapshot().orElseThrow().json()).isEqualTo(newer.toJson());
    assertThat(redis.bytes).isEqualTo(newer.toJson().getBytes(StandardCharsets.UTF_8));
    verify(harness.source(), times(1)).readCurrent(TENANT_ID);
    harness.store().close();
  }

  @Test
  void sourceAdvanceAfterSuccessfulRedisReadbackReturnsSourceChanged() throws Exception {
    TenantAuthoritySnapshot baseline = source(1L, 1L, 0L, null);
    TenantAuthoritySnapshot advanced = source(2L, 2L, 1L, tenantEvent("2", "2", "1"));
    FakeRedis redis = new FakeRedis();
    Harness harness = harness(redis, baseline, advanced);

    RedisTenantGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(TENANT_ID);

    assertThat(result.outcome())
        .isEqualTo(RedisTenantGenerationProjectionStore.Outcome.SOURCE_CHANGED);
    assertThat(result.snapshot()).isPresent();
    assertThat(result.snapshot().orElseThrow().json())
        .isEqualTo(TenantGenerationProjection.fromSource(baseline).toJson());
    assertThat(redis.bytes)
        .isEqualTo(
            TenantGenerationProjection.fromSource(baseline)
                .toJson()
                .getBytes(StandardCharsets.UTF_8));
    assertThat(redis.scriptCalls).isEqualTo(1);
    verify(harness.source(), times(2)).readCurrent(TENANT_ID);
    harness.store().close();
  }

  @Test
  void malformedStoredProjectionIsQuarantined() throws Exception {
    TenantAuthoritySnapshot baseline = source(1L, 1L, 0L, null);
    FakeRedis redis = new FakeRedis("{\"unknown\":true}".getBytes(StandardCharsets.UTF_8));
    Harness harness = harness(redis, baseline);

    RedisTenantGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(TENANT_ID);

    assertThat(result.outcome())
        .isEqualTo(RedisTenantGenerationProjectionStore.Outcome.QUARANTINED);
    assertThat(result.detail()).contains("MALFORMED_STORED_PROJECTION");
    assertThat(redis.scriptCalls).isZero();
    harness.store().close();
  }

  @Test
  void noscriptFailurePropagatesAfterOneLoadAndOneEvalShaWithoutFallback() throws Exception {
    TenantAuthoritySnapshot baseline = source(1L, 1L, 0L, null);
    FakeRedis redis = new FakeRedis();
    redis.evalFailure = new DataAccessResourceFailureException("NOSCRIPT missing script");
    Harness harness = harness(redis, baseline);

    assertThatThrownBy(() -> harness.store().refreshCurrent(TENANT_ID)).isSameAs(redis.evalFailure);
    assertThat(redis.scriptLoads).isEqualTo(1);
    assertThat(redis.scriptCalls).isEqualTo(1);
    assertThat(redis.commandOrder).containsExactly("SCRIPT LOAD", "EVALSHA");
    assertThat(
            org.mockito.Mockito.mockingDetails(redis.scriptingCommands).getInvocations().stream()
                .anyMatch(invocation -> invocation.getMethod().getName().equals("eval")))
        .isFalse();
    harness.store().close();
  }

  @Test
  void redisConnectionFailureRemainsVisibleToCaller() throws Exception {
    TenantAuthoritySnapshot baseline = source(1L, 1L, 0L, null);
    FakeRedis redis = new FakeRedis();
    redis.readFailure = new DataAccessResourceFailureException("Coordination Redis unavailable");
    Harness harness = harness(redis, baseline);

    assertThatThrownBy(() -> harness.store().refreshCurrent(TENANT_ID)).isSameAs(redis.readFailure);
    harness.store().close();
  }

  private static Harness harness(FakeRedis redis, TenantAuthoritySnapshot... sources)
      throws Exception {
    AccountTenantAuthorityEventProducer source = mock(AccountTenantAuthorityEventProducer.class);
    AtomicInteger sourceIndex = new AtomicInteger();
    doAnswer(ignored -> sources[Math.min(sourceIndex.getAndIncrement(), sources.length - 1)])
        .when(source)
        .readCurrent(TENANT_ID);

    RedisConnection connection = mock(RedisConnection.class);
    RedisStringCommands stringCommands = mock(RedisStringCommands.class);
    RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
    RedisScriptingCommands scriptingCommands = mock(RedisScriptingCommands.class);
    redis.scriptingCommands = scriptingCommands;
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
        .thenAnswer(
            invocation -> {
              byte[] script = invocation.getArgument(0);
              redis.scriptLoads++;
              redis.commandOrder.add("SCRIPT LOAD");
              redis.loadedScriptSha1 = sha1(script);
              redis.loadedScriptSha256 = sha256(script);
              return redis.loadedScriptSha1;
            });

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
              redis.commandOrder.add("EVALSHA");
              redis.evalShaArgument = invocation.getArgument(0);
              if (redis.evalFailure != null) {
                throw redis.evalFailure;
              }
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

    RedisTenantGenerationProjectionStore store =
        newStore(
            source,
            coordination("coordination.internal", 6379),
            new RedisTenantGenerationProjectionStore.CacheRateLimitEndpoint(
                "cache.internal", 6380));
    Field templateField =
        RedisTenantGenerationProjectionStore.class.getDeclaredField("redisTemplate");
    templateField.setAccessible(true);
    templateField.set(store, template);
    return new Harness(store, source, redis);
  }

  private static RedisTenantGenerationProjectionStore newStore(
      AccountTenantAuthorityEventProducer source,
      RedisTenantGenerationProjectionStore.CoordinationEndpoint coordination,
      RedisTenantGenerationProjectionStore.CacheRateLimitEndpoint cache) {
    return new RedisTenantGenerationProjectionStore(source, coordination, cache);
  }

  private static RedisTenantGenerationProjectionStore.CoordinationEndpoint coordination(
      String host, int port) {
    return new RedisTenantGenerationProjectionStore.CoordinationEndpoint(
        host, port, "account_coord_app", PASSWORD);
  }

  private static RedisTenantGenerationProjectionStore.CacheRateLimitEndpoint cache() {
    return new RedisTenantGenerationProjectionStore.CacheRateLimitEndpoint("cache.internal", 6380);
  }

  private static String sha1(byte[] script) throws NoSuchAlgorithmException {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(script));
  }

  private static String sha256(byte[] script) throws NoSuchAlgorithmException {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script));
  }

  private static TenantAuthoritySnapshot source(
      long generation, long sourceVersion, long sequence, TenantGenerationAuthorityEvent event) {
    return new TenantAuthoritySnapshot(
        TENANT_ID, generation, sourceVersion, STREAM_KEY, sequence, Optional.ofNullable(event));
  }

  private static TenantGenerationAuthorityEvent tenantEvent(
      String generation, String sourceVersion, String sequence) {
    return tenantEvent(generation, sourceVersion, sequence, "11111111-1111-4111-8111-111111111111");
  }

  private static TenantGenerationAuthorityEvent tenantEvent(
      String generation, String sourceVersion, String sequence, String requestId) {
    var evidence =
        TenantGenerationAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", TenantGenerationAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", TenantGenerationAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry(
                    "eventId", TenantGenerationAuthorityEventV1Codec.EVENT_ID_PREFIX + requestId),
                Map.entry("requestId", requestId),
                Map.entry("tenantId", TENANT_TEXT),
                Map.entry("sourceScope", "tenant/" + TENANT_TEXT),
                Map.entry("outboxStreamKey", STREAM_KEY),
                Map.entry("outboxSequence", sequence),
                Map.entry("tenantAuthorityGeneration", generation),
                Map.entry("sourceVersion", sourceVersion)));
    return evidence;
  }

  private record Harness(
      RedisTenantGenerationProjectionStore store,
      AccountTenantAuthorityEventProducer source,
      FakeRedis redis) {}

  private static final class FakeRedis {
    private byte[] bytes;
    private long ttlMillis = -1L;
    private String forcedScriptResult;
    private byte[] afterWriteBytes;
    private RuntimeException readFailure;
    private RuntimeException evalFailure;
    private int scriptLoads;
    private int scriptCalls;
    private String lastMode;
    private String loadedScriptSha1;
    private String loadedScriptSha256;
    private String evalShaArgument;
    private RedisScriptingCommands scriptingCommands;
    private final List<String> commandOrder = new ArrayList<>();

    private FakeRedis() {}

    private FakeRedis(byte[] bytes) {
      this.bytes = bytes.clone();
    }
  }
}
