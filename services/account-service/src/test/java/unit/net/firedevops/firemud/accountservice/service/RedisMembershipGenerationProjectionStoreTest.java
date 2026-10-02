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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto.MembershipBaseline;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountMembershipSourceReader;
import net.firedevops.firemud.accountservice.service.AccountMembershipSourceReader.MembershipSourceSnapshot;
import net.firedevops.firemud.accountservice.service.MembershipGenerationProjection;
import net.firedevops.firemud.accountservice.service.RedisMembershipGenerationProjectionStore;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisMembershipGenerationProjectionStoreTest {
  private static final String PASSWORD = "coordination-secret";
  private static final UUID ACCOUNT_ID = UUID.fromString("c980fa44-619e-4ca4-8ad6-75b0538a66a3");
  private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final String ACCOUNT_TEXT = ACCOUNT_ID.toString();
  private static final String TENANT_TEXT = TENANT_ID.toString();
  private static final String KEY =
      "session:auth:generation:membership:" + ACCOUNT_TEXT + ":" + TENANT_TEXT;
  private static final String STREAM_KEY =
      "account:auth-authority:v1:membership/" + ACCOUNT_TEXT + "/" + TENANT_TEXT;

  @Test
  void requiresAccountCoordinationPrincipalAndDistinctCacheEndpoint() {
    assertThatThrownBy(
            () ->
                new RedisMembershipGenerationProjectionStore.CoordinationEndpoint(
                    "coordination.internal", 6379, "gamesession_coord_app", PASSWORD))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("account_coord_app");
    assertThatThrownBy(
            () ->
                new RedisMembershipGenerationProjectionStore.CoordinationEndpoint(
                    "coordination.internal", 6379, "account_coord_app", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("credential");

    var coordination = coordination("coordination.internal", 6379);
    var cache =
        new RedisMembershipGenerationProjectionStore.CacheRateLimitEndpoint(
            "COORDINATION.INTERNAL.", 6379);
    assertThatThrownBy(
            () -> newStore(mock(AccountMembershipSourceReader.class), coordination, cache))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoints must be distinct");
    assertThat(coordination.toString()).contains("password=<redacted>").doesNotContain(PASSWORD);
  }

  @Test
  void requiresExplicitInitializationAndDoesNotAcceptAmbientRedisTemplate() {
    AccountMembershipSourceReader reader = mock(AccountMembershipSourceReader.class);
    RedisMembershipGenerationProjectionStore store =
        newStore(reader, coordination("coord.internal", 6379), cache());

    assertThatThrownBy(() -> store.refreshCurrent(ACCOUNT_ID, TENANT_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("explicit init()");
    boolean acceptsAmbientTemplate =
        java.util.Arrays.stream(RedisMembershipGenerationProjectionStore.class.getConstructors())
            .anyMatch(
                constructor ->
                    java.util.Arrays.stream(constructor.getParameterTypes())
                        .anyMatch(StringRedisTemplate.class::isAssignableFrom));
    assertThat(acceptsAmbientTemplate).isFalse();
    store.close();
    assertThatThrownBy(store::init)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
    verify(reader, times(0)).readCurrent(any(), any());
  }

  @Test
  void rebuildsOnlyTheExistingBaselineThenVerifiesExactBytesAndSource() throws Exception {
    MembershipSourceSnapshot baseline = baselineSource(1L, 1L);
    Harness harness = harness(new FakeRedis(), baseline, baseline);

    RedisMembershipGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(ACCOUNT_ID, TENANT_ID);

    assertThat(result.outcome())
        .isEqualTo(RedisMembershipGenerationProjectionStore.Outcome.APPLIED);
    assertThat(result.snapshot()).isPresent();
    assertThat(result.snapshot().orElseThrow().key()).isEqualTo(KEY);
    assertThat(MembershipGenerationProjection.parse(result.snapshot().orElseThrow().json()))
        .isEqualTo(MembershipGenerationProjection.fromSource(baseline));
    assertThat(harness.redis().bytes)
        .isEqualTo(result.snapshot().orElseThrow().json().getBytes(StandardCharsets.UTF_8));
    assertThat(harness.redis().lastMode).isEqualTo("ABSENT");
    assertThat(harness.redis().ttlMillis).isEqualTo(-1L);
    verify(harness.reader(), times(2)).readCurrent(ACCOUNT_ID, TENANT_ID);
    harness.store().close();
  }

  @Test
  void appliesPositiveFirstJoinWithIndependentSourceVersionAndNoEventMirror() throws Exception {
    MembershipSourceSnapshot joined =
        positiveSource(1L, 5L, 2L, 1L, membershipEvent("1", "2", "1", "join-a"));
    Harness harness = harness(new FakeRedis(), joined, joined);

    RedisMembershipGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(ACCOUNT_ID, TENANT_ID);
    MembershipGenerationProjection projection =
        MembershipGenerationProjection.parse(result.snapshot().orElseThrow().json());

    assertThat(result.outcome())
        .isEqualTo(RedisMembershipGenerationProjectionStore.Outcome.APPLIED);
    assertThat(projection.membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(projection.sourceVersion()).isEqualTo("5");
    assertThat(projection.membershipVersion()).isEqualTo(Map.of(TENANT_TEXT, "2"));
    assertThat(projection.outboxSequence()).isEqualTo("1");
    assertThat(projection.sourceEvent()).contains(joined.snapshot().sourceEvent().canonicalJson());
    assertThat(harness.redis().scriptCalls).isEqualTo(1);
    harness.store().close();
  }

  @Test
  void exactSourceReplayUsesOneNonMutatingCasAndFreshSourceRead() throws Exception {
    MembershipSourceSnapshot source = baselineSource(1L, 1L);
    MembershipGenerationProjection projection = MembershipGenerationProjection.fromSource(source);
    FakeRedis redis = new FakeRedis(projection.toJson().getBytes(StandardCharsets.UTF_8));
    Harness harness = harness(redis, source, source);

    RedisMembershipGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(ACCOUNT_ID, TENANT_ID);

    assertThat(result.outcome())
        .isEqualTo(RedisMembershipGenerationProjectionStore.Outcome.REPLAYED);
    assertThat(redis.lastMode).isEqualTo("VERIFY");
    assertThat(redis.scriptCalls).isEqualTo(1);
    verify(harness.reader(), times(2)).readCurrent(ACCOUNT_ID, TENANT_ID);
    harness.store().close();
  }

  @Test
  void sourceAdvanceAfterExactRedisReadbackReturnsSourceChangedWithoutRetry() throws Exception {
    MembershipSourceSnapshot baseline = baselineSource(1L, 1L);
    MembershipSourceSnapshot joined =
        positiveSource(1L, 2L, 2L, 1L, membershipEvent("1", "2", "1", "join-a"));
    FakeRedis redis = new FakeRedis();
    Harness harness = harness(redis, baseline, joined);

    RedisMembershipGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(ACCOUNT_ID, TENANT_ID);

    assertThat(result.outcome())
        .isEqualTo(RedisMembershipGenerationProjectionStore.Outcome.SOURCE_CHANGED);
    assertThat(result.snapshot()).isPresent();
    assertThat(redis.scriptCalls).isEqualTo(1);
    assertThat(redis.bytes)
        .isEqualTo(
            MembershipGenerationProjection.fromSource(baseline)
                .toJson()
                .getBytes(StandardCharsets.UTF_8));
    verify(harness.reader(), times(2)).readCurrent(ACCOUNT_ID, TENANT_ID);
    harness.store().close();
  }

  @Test
  void newerValidRedisReadbackAfterCasIsReturnedAsStaleWithoutRetry() throws Exception {
    MembershipSourceSnapshot baseline = baselineSource(1L, 1L);
    MembershipSourceSnapshot candidateSource =
        positiveSource(1L, 1L, 2L, 1L, membershipEvent("1", "2", "1", "join-a"));
    MembershipSourceSnapshot laterSource =
        positiveSource(2L, 2L, 3L, 2L, membershipEvent("2", "3", "2", "join-b"));
    MembershipGenerationProjection laterProjection =
        MembershipGenerationProjection.fromSource(laterSource);
    FakeRedis redis =
        new FakeRedis(
            MembershipGenerationProjection.fromSource(baseline)
                .toJson()
                .getBytes(StandardCharsets.UTF_8));
    redis.afterWriteBytes = laterProjection.toJson().getBytes(StandardCharsets.UTF_8);
    Harness harness = harness(redis, candidateSource);

    RedisMembershipGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(ACCOUNT_ID, TENANT_ID);

    assertThat(result.outcome()).isEqualTo(RedisMembershipGenerationProjectionStore.Outcome.STALE);
    assertThat(result.detail()).contains("REDIS_READBACK_CHANGED");
    assertThat(result.snapshot())
        .contains(
            new RedisMembershipGenerationProjectionStore.ProjectionSnapshot(
                KEY, laterProjection.toJson()));
    assertThat(redis.bytes).isEqualTo(laterProjection.toJson().getBytes(StandardCharsets.UTF_8));
    assertThat(redis.scriptCalls).isEqualTo(1);
    verify(harness.reader(), times(1)).readCurrent(ACCOUNT_ID, TENANT_ID);
    harness.store().close();
  }

  @Test
  void aheadProjectionAndSameCheckpointConflictAreNotOverwritten() throws Exception {
    MembershipSourceSnapshot baseline = baselineSource(1L, 1L);
    MembershipGenerationProjection ahead =
        MembershipGenerationProjection.fromSource(
            positiveSource(2L, 3L, 2L, 1L, membershipEvent("2", "2", "1", "join-a")));
    FakeRedis aheadRedis = new FakeRedis(ahead.toJson().getBytes(StandardCharsets.UTF_8));
    Harness aheadHarness = harness(aheadRedis, baseline);

    RedisMembershipGenerationProjectionStore.ApplyResult aheadResult =
        aheadHarness.store().refreshCurrent(ACCOUNT_ID, TENANT_ID);

    assertThat(aheadResult.outcome())
        .isEqualTo(RedisMembershipGenerationProjectionStore.Outcome.STALE_SOURCE);
    assertThat(aheadRedis.scriptCalls).isZero();
    aheadHarness.store().close();

    MembershipSourceSnapshot first =
        positiveSource(1L, 2L, 2L, 1L, membershipEvent("1", "2", "1", "join-a"));
    MembershipSourceSnapshot conflict =
        positiveSource(1L, 2L, 2L, 1L, membershipEvent("1", "2", "1", "join-b"));
    MembershipGenerationProjection stored = MembershipGenerationProjection.fromSource(first);
    FakeRedis conflictRedis = new FakeRedis(stored.toJson().getBytes(StandardCharsets.UTF_8));
    Harness conflictHarness = harness(conflictRedis, conflict);

    RedisMembershipGenerationProjectionStore.ApplyResult conflictResult =
        conflictHarness.store().refreshCurrent(ACCOUNT_ID, TENANT_ID);

    assertThat(conflictResult.outcome())
        .isEqualTo(RedisMembershipGenerationProjectionStore.Outcome.QUARANTINED);
    assertThat(conflictResult.detail()).contains("SAME_CHECKPOINT_DISAGREEMENT");
    assertThat(conflictRedis.scriptCalls).isZero();
    conflictHarness.store().close();
  }

  @Test
  void ttlAndMalformedRedisStateAreQuarantinedWithoutCas() throws Exception {
    MembershipSourceSnapshot source = baselineSource(1L, 1L);
    MembershipGenerationProjection projection = MembershipGenerationProjection.fromSource(source);
    FakeRedis ttlRedis = new FakeRedis(projection.toJson().getBytes(StandardCharsets.UTF_8));
    ttlRedis.ttlMillis = 10_000L;
    Harness ttlHarness = harness(ttlRedis, source);

    RedisMembershipGenerationProjectionStore.ApplyResult ttlResult =
        ttlHarness.store().refreshCurrent(ACCOUNT_ID, TENANT_ID);

    assertThat(ttlResult.outcome())
        .isEqualTo(RedisMembershipGenerationProjectionStore.Outcome.QUARANTINED);
    assertThat(ttlResult.detail()).contains("TTL_PRESENT");
    assertThat(ttlRedis.scriptCalls).isZero();
    ttlHarness.store().close();

    FakeRedis malformedRedis = new FakeRedis("{\"unknown\":true}".getBytes(StandardCharsets.UTF_8));
    Harness malformedHarness = harness(malformedRedis, source);

    RedisMembershipGenerationProjectionStore.ApplyResult malformedResult =
        malformedHarness.store().refreshCurrent(ACCOUNT_ID, TENANT_ID);

    assertThat(malformedResult.outcome())
        .isEqualTo(RedisMembershipGenerationProjectionStore.Outcome.QUARANTINED);
    assertThat(malformedResult.detail()).contains("MALFORMED_STORED_PROJECTION");
    assertThat(malformedRedis.scriptCalls).isZero();
    malformedHarness.store().close();
  }

  @Test
  void casStalenessDoesNotTriggerASecondAttempt() throws Exception {
    MembershipSourceSnapshot baseline = baselineSource(1L, 1L);
    FakeRedis redis = new FakeRedis();
    redis.forcedScriptResult = "STALE";
    Harness harness = harness(redis, baseline);

    RedisMembershipGenerationProjectionStore.ApplyResult result =
        harness.store().refreshCurrent(ACCOUNT_ID, TENANT_ID);

    assertThat(result.outcome()).isEqualTo(RedisMembershipGenerationProjectionStore.Outcome.STALE);
    assertThat(redis.scriptCalls).isEqualTo(1);
    verify(harness.reader(), times(1)).readCurrent(ACCOUNT_ID, TENANT_ID);
    harness.store().close();
  }

  @Test
  void redisFailureRemainsVisible() throws Exception {
    MembershipSourceSnapshot source = baselineSource(1L, 1L);
    FakeRedis redis = new FakeRedis();
    redis.readFailure = new DataAccessResourceFailureException("Coordination Redis unavailable");
    Harness harness = harness(redis, source);

    assertThatThrownBy(() -> harness.store().refreshCurrent(ACCOUNT_ID, TENANT_ID))
        .isSameAs(redis.readFailure);
    harness.store().close();
  }

  private static Harness harness(FakeRedis redis, MembershipSourceSnapshot... sources)
      throws Exception {
    AccountMembershipSourceReader reader = mock(AccountMembershipSourceReader.class);
    AtomicInteger sourceIndex = new AtomicInteger();
    doAnswer(ignored -> sources[Math.min(sourceIndex.getAndIncrement(), sources.length - 1)])
        .when(reader)
        .readCurrent(ACCOUNT_ID, TENANT_ID);

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

    RedisMembershipGenerationProjectionStore store =
        newStore(reader, coordination("coordination.internal", 6379), cache());
    Field templateField =
        RedisMembershipGenerationProjectionStore.class.getDeclaredField("redisTemplate");
    templateField.setAccessible(true);
    templateField.set(store, template);
    return new Harness(store, reader, redis);
  }

  private static RedisMembershipGenerationProjectionStore newStore(
      AccountMembershipSourceReader reader,
      RedisMembershipGenerationProjectionStore.CoordinationEndpoint coordination,
      RedisMembershipGenerationProjectionStore.CacheRateLimitEndpoint cache) {
    return new RedisMembershipGenerationProjectionStore(reader, coordination, cache);
  }

  private static RedisMembershipGenerationProjectionStore.CoordinationEndpoint coordination(
      String host, int port) {
    return new RedisMembershipGenerationProjectionStore.CoordinationEndpoint(
        host, port, "account_coord_app", PASSWORD);
  }

  private static RedisMembershipGenerationProjectionStore.CacheRateLimitEndpoint cache() {
    return new RedisMembershipGenerationProjectionStore.CacheRateLimitEndpoint(
        "cache.internal", 6380);
  }

  private static String sha1(byte[] script) throws NoSuchAlgorithmException {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(script));
  }

  private static MembershipSourceSnapshot baselineSource(long generation, long sourceVersion) {
    RuntimeMembershipSnapshotDto snapshot =
        new RuntimeMembershipSnapshotDto(
            ACCOUNT_TEXT,
            TENANT_TEXT,
            ACCOUNT_TEXT,
            TENANT_TEXT,
            false,
            false,
            new MembershipBaseline("MISSING", Map.of(TENANT_TEXT, "1"), "1"),
            List.of(),
            tuple("1"),
            "1",
            Instant.parse("2026-10-01T00:00:00Z"),
            checkpoints("0"),
            List.of(),
            null);
    return source(snapshot, generation, sourceVersion, 1L);
  }

  private static MembershipSourceSnapshot positiveSource(
      long generation,
      long sourceVersion,
      long membershipVersion,
      long sequence,
      MembershipEvent event) {
    String generationText = Long.toString(generation);
    String versionText = Long.toString(membershipVersion);
    String sequenceText = Long.toString(sequence);
    RuntimeMembershipSnapshotDto snapshot =
        new RuntimeMembershipSnapshotDto(
            ACCOUNT_TEXT,
            TENANT_TEXT,
            ACCOUNT_TEXT,
            TENANT_TEXT,
            true,
            true,
            new MembershipBaseline("ACTIVE", Map.of(TENANT_TEXT, versionText), generationText),
            List.of("player"),
            tuple(generationText),
            event.issuanceFence(),
            Instant.parse("2026-10-01T00:00:00Z"),
            checkpoints(sequenceText),
            List.of(
                new OutboxSourceEvidence(
                    STREAM_KEY,
                    sequenceText,
                    event.eventId(),
                    event.eventDigest(),
                    event.canonicalJson())),
            event);
    return source(snapshot, generation, sourceVersion, Long.parseLong(event.issuanceFence()));
  }

  private static List<OutboxCheckpointEntry> checkpoints(String membershipSequence) {
    return List.of(
        new OutboxCheckpointEntry("account:auth-authority:v1:account/" + ACCOUNT_TEXT, "0"),
        new OutboxCheckpointEntry(
            "account:auth-authority:v1:issuer/" + AccountServiceImpl.ACCOUNT_JWT_ISSUER, "0"),
        new OutboxCheckpointEntry(STREAM_KEY, membershipSequence),
        new OutboxCheckpointEntry("account:auth-authority:v1:tenant/" + TENANT_TEXT, "0"));
  }

  private static MembershipSourceSnapshot source(
      RuntimeMembershipSnapshotDto snapshot, long generation, long sourceVersion, long fenceValue) {
    return new MembershipSourceSnapshot(
        ACCOUNT_ID,
        TENANT_ID,
        snapshot,
        new ScopeState(
            AuthorityScope.membership(ACCOUNT_ID, TENANT_ID),
            generation,
            sourceVersion,
            new IssuanceFence(ACCOUNT_ID, fenceValue, 1L)));
  }

  private static AuthorityTuple tuple(String membershipGeneration) {
    return new AuthorityTuple(
        "1",
        "1",
        Map.of(TENANT_TEXT, "1"),
        Map.of(TENANT_TEXT, membershipGeneration),
        List.of(),
        Optional.empty(),
        Optional.empty());
  }

  private static MembershipEvent membershipEvent(
      String generation, String membershipVersion, String sequence, String requestId) {
    return MembershipAuthorityEventV1Codec.seal(
        Map.ofEntries(
            Map.entry("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION),
            Map.entry("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE),
            Map.entry("eventId", "event-" + requestId),
            Map.entry("requestId", requestId),
            Map.entry("outboxStreamKey", STREAM_KEY),
            Map.entry("outboxSequence", sequence),
            Map.entry("sourceScope", "membership/" + ACCOUNT_TEXT + "/" + TENANT_TEXT),
            Map.entry("accountId", ACCOUNT_TEXT),
            Map.entry("tenantId", TENANT_TEXT),
            Map.entry("membershipExists", true),
            Map.entry("membershipLifecycleState", "ACTIVE"),
            Map.entry("membershipVersion", Map.of(TENANT_TEXT, membershipVersion)),
            Map.entry("membershipAuthorityGeneration", generation),
            Map.entry(
                "authorityTuple",
                Map.of(
                    "issuerAuthGeneration", "1",
                    "accountAuthorityGeneration", "1",
                    "tenantAuthorityGeneration", Map.of(TENANT_TEXT, "1"),
                    "membershipAuthorityGeneration", Map.of(TENANT_TEXT, generation),
                    "privateRealmGrantVersions", List.of())),
            Map.entry("issuanceFence", "1"),
            Map.entry("roles", List.of("player")),
            Map.entry("gameplayAdmissionAllowed", true),
            Map.entry("callerBoundAuthorityInvalidated", false)));
  }

  private record Harness(
      RedisMembershipGenerationProjectionStore store,
      AccountMembershipSourceReader reader,
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
