package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterServerCommands;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

class CanonicalGameplayLegacyRedisSourceTest {
  private static final String TENANT_CONTEXT = "sessionctx:17:29:context";
  private static final String SESSION_ALIAS = "sessionctx:session:29:context";
  private static final String FIRST_PARTY_CONTEXT = "sessionctx:first-party:29:connect-context";

  @Test
  void fullyEnumeratedEmptyPrefixIsOnlyAnEmptySourceObservation() {
    StringRedisTemplate redis = redisWithScans(List.of(List.of(), List.of()));
    var source = new CanonicalGameplayLegacyRedisSource(redis, limits());

    var snapshot = source.captureEveryKnownFamily(cohort());

    assertThat(snapshot.entries()).isEmpty();
    assertThat(snapshot.enumeratedEveryKnownFamily()).isTrue();
    assertThat(snapshot.everyEntryIsReconciled()).isTrue();
    verify(redis, times(2)).execute(any(RedisCallback.class));
  }

  @Test
  void clusterConnectionIsRefusedBeforeLegacyScanEvenWhenInfoClaimsStandalone() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    RedisClusterConnection connection = mock(RedisClusterConnection.class);
    RedisClusterServerCommands serverCommands = mock(RedisClusterServerCommands.class);
    Properties clusterInformation = new Properties();
    clusterInformation.setProperty("cluster_enabled", "0");
    when(serverCommands.info("cluster")).thenReturn(clusterInformation);
    when(connection.serverCommands()).thenReturn(serverCommands);
    invokeThroughRedisCallback(redis, connection);

    assertThatThrownBy(
            () ->
                new CanonicalGameplayLegacyRedisSource(redis, limits())
                    .captureEveryKnownFamily(cohort()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("cluster scan cannot prove complete cluster-wide inventory");

    verify(connection, never()).scan(any(ScanOptions.class));
  }

  @Test
  void unknownOrMissingStandaloneTopologyIsRefusedBeforeLegacyScan() {
    Properties unknown = new Properties();
    unknown.setProperty("cluster_enabled", "unknown");
    for (Properties clusterInformation : new Properties[] {new Properties(), unknown, null}) {
      StringRedisTemplate redis = mock(StringRedisTemplate.class);
      RedisConnection connection = mock(RedisConnection.class);
      RedisServerCommands serverCommands = mock(RedisServerCommands.class);
      when(serverCommands.info("cluster")).thenReturn(clusterInformation);
      when(connection.serverCommands()).thenReturn(serverCommands);
      invokeThroughRedisCallback(redis, connection);

      assertThatThrownBy(
              () ->
                  new CanonicalGameplayLegacyRedisSource(redis, limits())
                      .captureEveryKnownFamily(cohort()))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("standalone topology could not be proven");

      verify(connection, never()).scan(any(ScanOptions.class));
    }
  }

  @Test
  void topologyInfoFailureIsRefusedBeforeLegacyScan() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    RedisConnection connection = mock(RedisConnection.class);
    RedisServerCommands serverCommands = mock(RedisServerCommands.class);
    when(serverCommands.info("cluster")).thenThrow(new IllegalStateException("INFO unavailable"));
    when(connection.serverCommands()).thenReturn(serverCommands);
    invokeThroughRedisCallback(redis, connection);

    assertThatThrownBy(
            () ->
                new CanonicalGameplayLegacyRedisSource(redis, limits())
                    .captureEveryKnownFamily(cohort()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("standalone topology could not be proven");

    verify(connection, never()).scan(any(ScanOptions.class));
  }

  @Test
  void unknownPrefixKeyMakesTheInventoryIncompleteAndNonAuthorizing() {
    StringRedisTemplate redis =
        redisWithScans(List.of(List.of(FIRST_PARTY_CONTEXT), List.of(FIRST_PARTY_CONTEXT)));
    var snapshot =
        new CanonicalGameplayLegacyRedisSource(redis, limits()).captureEveryKnownFamily(cohort());

    assertThat(snapshot.enumeratedEveryKnownFamily()).isFalse();
    assertThat(snapshot.everyEntryIsReconciled()).isFalse();
    assertThat(snapshot.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.family())
                  .isEqualTo(CanonicalGameplayLegacyMigrationSourceSnapshot.Family.UNCLASSIFIED);
              assertThat(entry.disposition())
                  .isEqualTo(CanonicalGameplayLegacyMigrationSourceSnapshot.Disposition.UNKNOWN);
            });
    assertThat(snapshot.toString()).doesNotContain(FIRST_PARTY_CONTEXT);
  }

  @Test
  void duplicateScanResultsAreDeduplicatedButRetainedKnownKeysRemainUnmappableAndUntouched() {
    StringRedisTemplate redis =
        redisWithScans(
            List.of(
                List.of(TENANT_CONTEXT, TENANT_CONTEXT), List.of(TENANT_CONTEXT, TENANT_CONTEXT)));
    var snapshot = new CanonicalGameplayLegacyRedisSource(redis, limits());

    var observed = snapshot.captureEveryKnownFamily(cohort());

    assertThat(observed.entries()).hasSize(1);
    assertThat(observed.entries().getFirst().sourceKeyDigest()).startsWith("sha256:");
    assertThat(observed.entries().getFirst().family())
        .isEqualTo(CanonicalGameplayLegacyMigrationSourceSnapshot.Family.TENANT_SESSION_CONTEXT);
    assertThat(observed.entries().getFirst().disposition())
        .isEqualTo(CanonicalGameplayLegacyMigrationSourceSnapshot.Disposition.UNMAPPABLE);
    assertThat(observed.toString()).doesNotContain(TENANT_CONTEXT);
    verify(redis, never()).opsForValue();
    verify(redis, never()).delete(any(String.class));
  }

  @Test
  void cursorFailureAndUniqueKeyLimitFailClosed() {
    StringRedisTemplate brokenCursorRedis = mock(StringRedisTemplate.class);
    RedisConnection brokenConnection = mock(RedisConnection.class);
    Cursor<byte[]> brokenCursor = mock(Cursor.class);
    when(brokenConnection.scan(any(ScanOptions.class))).thenReturn(brokenCursor);
    when(brokenCursor.hasNext()).thenThrow(new IllegalStateException("incomplete cursor"));
    RedisServerCommands brokenServerCommands = standaloneServerCommands();
    when(brokenConnection.serverCommands()).thenReturn(brokenServerCommands);
    invokeThroughRedisCallback(brokenCursorRedis, brokenConnection);

    assertThatThrownBy(
            () ->
                new CanonicalGameplayLegacyRedisSource(brokenCursorRedis, limits())
                    .captureEveryKnownFamily(cohort()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("cursor failed before complete exhaustion");

    StringRedisTemplate incompleteCursorRedis = mock(StringRedisTemplate.class);
    RedisConnection incompleteConnection = mock(RedisConnection.class);
    Cursor<byte[]> incompleteCursor = mock(Cursor.class);
    when(incompleteConnection.scan(any(ScanOptions.class))).thenReturn(incompleteCursor);
    when(incompleteCursor.hasNext()).thenReturn(false);
    when(incompleteCursor.getCursorId()).thenReturn(17L);
    RedisServerCommands incompleteServerCommands = standaloneServerCommands();
    when(incompleteConnection.serverCommands()).thenReturn(incompleteServerCommands);
    invokeThroughRedisCallback(incompleteCursorRedis, incompleteConnection);
    assertThatThrownBy(
            () ->
                new CanonicalGameplayLegacyRedisSource(incompleteCursorRedis, limits())
                    .captureEveryKnownFamily(cohort()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("terminal cursor identity");

    StringRedisTemplate overLimitRedis =
        redisWithScans(
            List.of(
                List.of(TENANT_CONTEXT, SESSION_ALIAS), List.of(TENANT_CONTEXT, SESSION_ALIAS)));
    var tightLimits =
        new CanonicalGameplayLegacyRedisSource.ScanLimits(2, 1, 4, 256, Duration.ofSeconds(5));
    assertThatThrownBy(
            () ->
                new CanonicalGameplayLegacyRedisSource(overLimitRedis, tightLimits)
                    .captureEveryKnownFamily(cohort()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("unique-key bound");
    verify(overLimitRedis, times(1)).execute(any(RedisCallback.class));
  }

  @Test
  void differentFirstAndSecondInventoriesAreRejected() {
    StringRedisTemplate redis =
        redisWithScans(List.of(List.of(TENANT_CONTEXT), List.of(SESSION_ALIAS)));

    assertThatThrownBy(
            () ->
                new CanonicalGameplayLegacyRedisSource(redis, limits())
                    .captureEveryKnownFamily(cohort()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("changed between complete fenced scans");
  }

  @Test
  void fenceDriftDuringABatchPreventsTheSecondScan() {
    StringRedisTemplate redis =
        redisWithScans(List.of(List.of(TENANT_CONTEXT), List.of(TENANT_CONTEXT)));
    var driftingCohort = new TestCohort(3);
    var oneKeyPerFenceCheck =
        new CanonicalGameplayLegacyRedisSource.ScanLimits(1, 10, 10, 256, Duration.ofSeconds(5));

    assertThatThrownBy(
            () ->
                new CanonicalGameplayLegacyRedisSource(redis, oneKeyPerFenceCheck)
                    .captureEveryKnownFamily(driftingCohort))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    verify(redis, times(1)).execute(any(RedisCallback.class));
  }

  private static CanonicalGameplayLegacyRedisSource.ScanLimits limits() {
    return new CanonicalGameplayLegacyRedisSource.ScanLimits(2, 20, 40, 256, Duration.ofSeconds(5));
  }

  private static RedisServerCommands standaloneServerCommands() {
    RedisServerCommands serverCommands = mock(RedisServerCommands.class);
    Properties clusterInformation = new Properties();
    clusterInformation.setProperty("cluster_enabled", "0");
    when(serverCommands.info("cluster")).thenReturn(clusterInformation);
    return serverCommands;
  }

  private static StringRedisTemplate redisWithScans(List<List<String>> scans) {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    RedisConnection connection = mock(RedisConnection.class);
    AtomicInteger scanIndex = new AtomicInteger();
    when(connection.scan(any(ScanOptions.class)))
        .thenAnswer(
            ignored -> {
              int index = scanIndex.getAndIncrement();
              if (index >= scans.size()) {
                throw new AssertionError("unexpected third Redis scan");
              }
              return cursor(scans.get(index));
            });
    RedisServerCommands serverCommands = mock(RedisServerCommands.class);
    Properties clusterInformation = new Properties();
    clusterInformation.setProperty("cluster_enabled", "0");
    when(serverCommands.info("cluster")).thenReturn(clusterInformation);
    when(connection.serverCommands()).thenReturn(serverCommands);
    invokeThroughRedisCallback(redis, connection);
    return redis;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void invokeThroughRedisCallback(
      StringRedisTemplate redis, RedisConnection connection) {
    doAnswer(
            invocation -> {
              RedisCallback callback = invocation.getArgument(0);
              return callback.doInRedis(connection);
            })
        .when(redis)
        .execute(any(RedisCallback.class));
  }

  @SuppressWarnings("unchecked")
  private static Cursor<byte[]> cursor(List<String> keys) {
    Cursor<byte[]> cursor = mock(Cursor.class);
    AtomicInteger index = new AtomicInteger();
    when(cursor.hasNext()).thenAnswer(ignored -> index.get() < keys.size());
    when(cursor.next())
        .thenAnswer(ignored -> keys.get(index.getAndIncrement()).getBytes(StandardCharsets.UTF_8));
    return cursor;
  }

  private static TestCohort cohort() {
    return new TestCohort(0);
  }

  private static final class TestCohort
      implements CanonicalGameplayLegacyMigrationOwner.FencedCohort {
    private final UUID cohortId = UUID.randomUUID();
    private final UUID writerFence = UUID.randomUUID();
    private final CanonicalGameplayLegacyMigrationStorageIdentity identity =
        new CanonicalGameplayLegacyMigrationStorageIdentity(
            "cluster-uid",
            "namespace-uid",
            "producer-pod-uid",
            "container-id",
            "node-uid",
            "987654321",
            42L,
            "postgres-volume-uid",
            "redis-run-id",
            "redis-volume-uid");
    private final int failAtFenceCheck;
    private final AtomicInteger fenceChecks = new AtomicInteger();

    private TestCohort(int failAtFenceCheck) {
      this.failAtFenceCheck = failAtFenceCheck;
    }

    @Override
    public UUID cohortId() {
      return cohortId;
    }

    @Override
    public UUID legacyWriterFence() {
      return writerFence;
    }

    @Override
    public CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity() {
      return identity;
    }

    @Override
    public void requireStillFenced(
        CanonicalGameplayLegacyMigrationStorageIdentity expectedIdentity) {
      assertThat(expectedIdentity).isEqualTo(identity);
      int check = fenceChecks.incrementAndGet();
      if (failAtFenceCheck > 0 && check >= failAtFenceCheck) {
        throw new CanonicalGameplayBindingInventoryConflictException("synthetic fence drift");
      }
    }
  }
}
