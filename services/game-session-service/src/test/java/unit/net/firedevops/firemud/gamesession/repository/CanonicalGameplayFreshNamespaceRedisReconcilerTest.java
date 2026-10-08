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

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountCoverageEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingAccountIndexObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventorySnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIssuerIndexObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRegionBridgeObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationReadback;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterServerCommands;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Mock-cursor contract tests only; none of these fixtures proves physical Redis coverage. */
class CanonicalGameplayFreshNamespaceRedisReconcilerTest {
  private static final String LEGACY_CONTEXT = "sessionctx:17:29:context";
  private static final String TARGET_KEY = "session:game:unexpected:retained";

  @Test
  void fullyScannedEmptyPhysicalDoubleSucceedsOnlyWithStableFencedEmptyInventory() {
    StringRedisTemplate redis = redisWithScans(emptyScans(8));
    TestCohort cohort = new TestCohort(0);
    CanonicalGameplayFreshNamespaceRedisReconciler reconciler = reconciler(redis);

    reconciler.rebuildExact(cohort, emptyLegacySnapshot(), emptyCanonicalSnapshot(41));
    CanonicalGameplayLegacyMigrationReadback readback =
        reconciler.readBackExact(cohort, emptyCanonicalSnapshot(41));

    assertThat(readback.sessionRecords()).isEmpty();
    assertThat(readback.characterIndexRecords()).isEmpty();
    assertThat(readback.remainingLegacySourceKeyDigests()).isEmpty();
    assertThat(readback.unexpectedNamespaceKeyDigests()).isEmpty();
    assertThat(cohort.fenceChecks.get()).isPositive();
    verify(redis, times(10)).execute(any(RedisCallback.class));
    verifyNoRedisMutationOrValueRead(redis);
  }

  @Test
  void canonicalPendingActiveRowsAndEveryObligationFamilyAreRejectedBeforeRedisAccess() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    CanonicalGameplayFreshNamespaceRedisReconciler reconciler = reconciler(redis);

    for (CanonicalGameplayBindingInventorySnapshot snapshot : nonemptyCanonicalSnapshots()) {
      assertThatThrownBy(() -> reconciler.rebuildExact(cohort(), emptyLegacySnapshot(), snapshot))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("Nonempty canonical gameplay inventory");
    }

    verify(redis, never()).execute(any(RedisCallback.class));
  }

  @Test
  void retainedLegacySourceRemainsUnchangedAndBlocksReconciliation() {
    StringRedisTemplate redis =
        redisWithScans(List.of(List.of(LEGACY_CONTEXT), List.of(LEGACY_CONTEXT)));
    CanonicalGameplayFreshNamespaceRedisReconciler reconciler = reconciler(redis);

    assertThatThrownBy(
            () ->
                reconciler.rebuildExact(cohort(), emptyLegacySnapshot(), emptyCanonicalSnapshot(1)))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("Complete empty legacy source inventory");

    verify(redis, times(3)).execute(any(RedisCallback.class));
    verifyNoRedisMutationOrValueRead(redis);
  }

  @Test
  void retainedTargetOrUnknownNamespaceKeyRemainsUnchangedAndBlocksReconciliation() {
    StringRedisTemplate redis =
        redisWithScans(scans(List.of(), List.of(), List.of(TARGET_KEY), List.of(TARGET_KEY)));
    CanonicalGameplayFreshNamespaceRedisReconciler reconciler = reconciler(redis);

    assertThatThrownBy(
            () ->
                reconciler.rebuildExact(cohort(), emptyLegacySnapshot(), emptyCanonicalSnapshot(1)))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("session or index family is retained or unknown");

    verify(redis, times(5)).execute(any(RedisCallback.class));
    verifyNoRedisMutationOrValueRead(redis);
  }

  @Test
  void onlyCanonicalNonNilIssuerProjectionIsExcludedAndPreservedWithoutReadingValue() {
    String issuerProjection = "session:game:auth:issuer-generation:v1:" + UUID.randomUUID();
    StringRedisTemplate redis =
        redisWithScans(
            scans(
                List.of(),
                List.of(),
                List.of(issuerProjection, issuerProjection),
                List.of(issuerProjection, issuerProjection),
                List.of(),
                List.of(),
                List.of(issuerProjection, issuerProjection),
                List.of(issuerProjection, issuerProjection)));
    CanonicalGameplayFreshNamespaceRedisReconciler reconciler = reconciler(redis);
    TestCohort cohort = cohort();
    CanonicalGameplayBindingInventorySnapshot canonical = emptyCanonicalSnapshot(3);

    reconciler.rebuildExact(cohort, emptyLegacySnapshot(), canonical);
    CanonicalGameplayLegacyMigrationReadback readback = reconciler.readBackExact(cohort, canonical);

    assertThat(readback.sessionRecords()).isEmpty();
    assertThat(readback.characterIndexRecords()).isEmpty();
    verify(redis, times(10)).execute(any(RedisCallback.class));
    verifyNoRedisMutationOrValueRead(redis);
  }

  @Test
  void malformedIssuerProjectionKeyIsDeniedWithoutMutation() {
    for (String issuerId : List.of("not-a-uuid", "00000000-0000-0000-0000-000000000000")) {
      String malformed = "session:game:auth:issuer-generation:v1:" + issuerId;
      StringRedisTemplate redis = redisWithScans(scans(List.of(), List.of(), List.of(malformed)));

      assertThatThrownBy(
              () ->
                  reconciler(redis)
                      .rebuildExact(cohort(), emptyLegacySnapshot(), emptyCanonicalSnapshot(2)))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("malformed issuer projection key");

      verify(redis, times(4)).execute(any(RedisCallback.class));
      verifyNoRedisMutationOrValueRead(redis);
    }
  }

  @Test
  void repeatedCanonicalIssuerVisitsAreDeduplicatedButRemainVisitBounded() {
    String issuerProjection = "session:game:auth:issuer-generation:v1:" + UUID.randomUUID();
    StringRedisTemplate redis =
        redisWithScans(
            scans(
                List.of(),
                List.of(),
                List.of(issuerProjection, issuerProjection, issuerProjection)));
    var visitLimit =
        new CanonicalGameplayFreshNamespaceRedisReconciler.ScanLimits(
            2, 1, 2, 256, Duration.ofSeconds(5));
    CanonicalGameplayFreshNamespaceRedisReconciler reconciler =
        new CanonicalGameplayFreshNamespaceRedisReconciler(redis, visitLimit);

    assertThatThrownBy(
            () ->
                reconciler.rebuildExact(cohort(), emptyLegacySnapshot(), emptyCanonicalSnapshot(2)))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("duplicate-safe visit bound");

    verify(redis, times(4)).execute(any(RedisCallback.class));
    verifyNoRedisMutationOrValueRead(redis);
  }

  @Test
  void nonterminalCursorAndCursorFailureFailClosed() {
    StringRedisTemplate nonterminal =
        redisWithResults(
            List.of(ScanResult.empty(), ScanResult.empty(), ScanResult.withCursor(List.of(), 19L)));
    assertThatThrownBy(
            () ->
                reconciler(nonterminal)
                    .rebuildExact(cohort(), emptyLegacySnapshot(), emptyCanonicalSnapshot(1)))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("terminal cursor identity");

    StringRedisTemplate failed =
        redisWithResults(
            List.of(ScanResult.empty(), ScanResult.empty(), ScanResult.failedCursor()));
    assertThatThrownBy(
            () ->
                reconciler(failed)
                    .rebuildExact(cohort(), emptyLegacySnapshot(), emptyCanonicalSnapshot(1)))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("cursor failed before complete exhaustion");
    verifyNoRedisMutationOrValueRead(nonterminal);
    verifyNoRedisMutationOrValueRead(failed);
  }

  @Test
  void clusterConnectionIsRefusedBecauseSingleScanCannotProveClusterCoverage() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    RedisClusterConnection connection = mock(RedisClusterConnection.class);
    RedisClusterServerCommands serverCommands = mock(RedisClusterServerCommands.class);
    Properties clusterInformation = new Properties();
    clusterInformation.setProperty("cluster_enabled", "0");
    when(serverCommands.info("cluster")).thenReturn(clusterInformation);
    when(connection.serverCommands()).thenReturn(serverCommands);
    Cursor<byte[]> emptyCursor = cursor(ScanResult.empty());
    when(connection.scan(any(ScanOptions.class))).thenReturn(emptyCursor);
    invokeThroughRedisCallback(redis, connection);
    CanonicalGameplayFreshNamespaceRedisReconciler reconciler = reconciler(redis);

    assertThatThrownBy(
            () ->
                reconciler.rebuildExact(cohort(), emptyLegacySnapshot(), emptyCanonicalSnapshot(1)))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("cannot prove complete cluster-wide namespace coverage");

    // The existing legacy adapter makes two harmless empty scans first; the new target-family
    // reconciler then refuses to treat a single cluster cursor as complete cluster coverage.
    verify(connection, times(2)).scan(any(ScanOptions.class));
    verifyNoRedisMutationOrValueRead(redis);
  }

  @Test
  void standaloneWrapperReportingClusterEnabledIsRefusedBeforeAnyNamespaceScan() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    RedisConnection connection = mock(RedisConnection.class);
    RedisServerCommands serverCommands = serverCommandsWithTopology("1");
    when(connection.serverCommands()).thenReturn(serverCommands);
    invokeThroughRedisCallback(redis, connection);

    assertThatThrownBy(
            () ->
                reconciler(redis)
                    .rebuildExact(cohort(), emptyLegacySnapshot(), emptyCanonicalSnapshot(1)))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("standalone topology could not be proven");

    verify(connection, never()).scan(any(ScanOptions.class));
    verifyNoRedisMutationOrValueRead(redis);
  }

  @Test
  void unavailableTopologyObservationIsRefusedBeforeAnyNamespaceScan() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    RedisConnection connection = mock(RedisConnection.class);
    RedisServerCommands serverCommands = mock(RedisServerCommands.class);
    when(serverCommands.info("cluster")).thenThrow(new IllegalStateException("INFO unavailable"));
    when(connection.serverCommands()).thenReturn(serverCommands);
    invokeThroughRedisCallback(redis, connection);

    assertThatThrownBy(
            () ->
                reconciler(redis)
                    .rebuildExact(cohort(), emptyLegacySnapshot(), emptyCanonicalSnapshot(1)))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("standalone topology could not be proven");

    verify(connection, never()).scan(any(ScanOptions.class));
    verifyNoRedisMutationOrValueRead(redis);
  }

  @Test
  void firstAndSecondTargetInventoriesMustBeIdentical() {
    StringRedisTemplate redis =
        redisWithScans(scans(List.of(), List.of(), List.of(), List.of(TARGET_KEY)));

    assertThatThrownBy(
            () ->
                reconciler(redis)
                    .rebuildExact(cohort(), emptyLegacySnapshot(), emptyCanonicalSnapshot(1)))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("changed between complete fenced scans");

    verify(redis, times(5)).execute(any(RedisCallback.class));
    verifyNoRedisMutationOrValueRead(redis);
  }

  @Test
  void fenceDriftDuringTargetScanPreventsFurtherInventoryOrReadback() {
    StringRedisTemplate redis = redisWithScans(emptyScans(8));
    TestCohort driftingCohort = new TestCohort(11);

    assertThatThrownBy(
            () ->
                reconciler(redis)
                    .rebuildExact(driftingCohort, emptyLegacySnapshot(), emptyCanonicalSnapshot(1)))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("changed during reconciliation");

    verify(redis, times(4)).execute(any(RedisCallback.class));
    verifyNoRedisMutationOrValueRead(redis);
  }

  @Test
  void readbackRechecksRedisAndRejectsAKeyThatAppearsAfterTheNoOpRebuild() {
    StringRedisTemplate redis =
        redisWithScans(
            scans(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(TARGET_KEY)));
    TestCohort cohort = cohort();
    CanonicalGameplayFreshNamespaceRedisReconciler reconciler = reconciler(redis);
    CanonicalGameplayBindingInventorySnapshot canonical = emptyCanonicalSnapshot(5);
    reconciler.rebuildExact(cohort, emptyLegacySnapshot(), canonical);

    assertThatThrownBy(() -> reconciler.readBackExact(cohort, canonical))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("changed between complete fenced scans");

    verify(redis, times(10)).execute(any(RedisCallback.class));
    verifyNoRedisMutationOrValueRead(redis);
  }

  @Test
  void readbackRechecksLegacyFamiliesInsteadOfTrustingTheEarlierEmptySnapshot() {
    StringRedisTemplate redis =
        redisWithScans(
            scans(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(LEGACY_CONTEXT),
                List.of(LEGACY_CONTEXT)));
    TestCohort cohort = cohort();
    CanonicalGameplayFreshNamespaceRedisReconciler reconciler = reconciler(redis);
    CanonicalGameplayBindingInventorySnapshot canonical = emptyCanonicalSnapshot(5);
    reconciler.rebuildExact(cohort, emptyLegacySnapshot(), canonical);

    assertThatThrownBy(() -> reconciler.readBackExact(cohort, canonical))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("Complete empty legacy source inventory");

    verify(redis, times(8)).execute(any(RedisCallback.class));
    verifyNoRedisMutationOrValueRead(redis);
  }

  @Test
  void scanBoundsFailClosedBeforeACompleteNamespaceClaim() {
    StringRedisTemplate redis =
        redisWithScans(
            scans(List.of(), List.of(), List.of("session:game:first", "session:game:second")));
    var tightLimits =
        new CanonicalGameplayFreshNamespaceRedisReconciler.ScanLimits(
            2, 1, 2, 256, Duration.ofSeconds(5));
    CanonicalGameplayFreshNamespaceRedisReconciler reconciler =
        new CanonicalGameplayFreshNamespaceRedisReconciler(redis, tightLimits);

    assertThatThrownBy(
            () ->
                reconciler.rebuildExact(cohort(), emptyLegacySnapshot(), emptyCanonicalSnapshot(1)))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("unique-key bound");

    verifyNoRedisMutationOrValueRead(redis);
  }

  @Test
  void keyLengthAndElapsedTimeBoundsFailClosed() {
    StringRedisTemplate longKeyRedis =
        redisWithScans(scans(List.of(), List.of(), List.of("session:game:" + "x".repeat(300))));
    assertThatThrownBy(
            () ->
                reconciler(longKeyRedis)
                    .rebuildExact(cohort(), emptyLegacySnapshot(), emptyCanonicalSnapshot(1)))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("exceeds its byte bound");

    StringRedisTemplate timedRedis = mock(StringRedisTemplate.class);
    var immediateDeadline =
        new CanonicalGameplayFreshNamespaceRedisReconciler.ScanLimits(
            2, 20, 40, 256, Duration.ofNanos(1));
    var timedReconciler =
        new CanonicalGameplayFreshNamespaceRedisReconciler(timedRedis, immediateDeadline);
    assertThatThrownBy(
            () ->
                timedReconciler.rebuildExact(
                    cohort(), emptyLegacySnapshot(), emptyCanonicalSnapshot(1)))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
        .hasMessageContaining("time bound");
    verify(timedRedis, never()).execute(any(RedisCallback.class));
    verifyNoRedisMutationOrValueRead(longKeyRedis);
    verifyNoRedisMutationOrValueRead(timedRedis);
  }

  private static CanonicalGameplayFreshNamespaceRedisReconciler reconciler(
      StringRedisTemplate redis) {
    return new CanonicalGameplayFreshNamespaceRedisReconciler(redis, limits());
  }

  private static CanonicalGameplayFreshNamespaceRedisReconciler.ScanLimits limits() {
    return new CanonicalGameplayFreshNamespaceRedisReconciler.ScanLimits(
        2, 20, 40, 256, Duration.ofSeconds(5));
  }

  private static List<List<String>> emptyScans(int count) {
    List<List<String>> scans = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      scans.add(List.of());
    }
    return scans;
  }

  @SafeVarargs
  private static List<List<String>> scans(List<String>... scans) {
    return List.of(scans);
  }

  private static StringRedisTemplate redisWithScans(List<List<String>> scans) {
    return redisWithResults(scans.stream().map(ScanResult::withKeys).toList());
  }

  private static StringRedisTemplate redisWithResults(List<ScanResult> scans) {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    RedisConnection connection = mock(RedisConnection.class);
    AtomicInteger scanIndex = new AtomicInteger();
    when(connection.scan(any(ScanOptions.class)))
        .thenAnswer(
            ignored -> {
              int index = scanIndex.getAndIncrement();
              if (index >= scans.size()) {
                throw new AssertionError("unexpected additional Redis scan");
              }
              return cursor(scans.get(index));
            });
    RedisServerCommands serverCommands = serverCommandsWithTopology("0");
    when(connection.serverCommands()).thenReturn(serverCommands);
    invokeThroughRedisCallback(redis, connection);
    return redis;
  }

  private static RedisServerCommands serverCommandsWithTopology(String clusterEnabled) {
    RedisServerCommands serverCommands = mock(RedisServerCommands.class);
    Properties clusterInformation = new Properties();
    clusterInformation.setProperty("cluster_enabled", clusterEnabled);
    when(serverCommands.info("cluster")).thenReturn(clusterInformation);
    return serverCommands;
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
  private static Cursor<byte[]> cursor(ScanResult result) {
    Cursor<byte[]> cursor = mock(Cursor.class);
    AtomicInteger index = new AtomicInteger();
    if (result.failCursor()) {
      when(cursor.hasNext()).thenThrow(new IllegalStateException("cursor unavailable"));
    } else {
      when(cursor.hasNext()).thenAnswer(ignored -> index.get() < result.keys().size());
      when(cursor.next())
          .thenAnswer(
              ignored ->
                  result.keys().get(index.getAndIncrement()).getBytes(StandardCharsets.UTF_8));
      when(cursor.getCursorId()).thenReturn(result.terminalCursorId());
    }
    return cursor;
  }

  private static CanonicalGameplayLegacyMigrationSourceSnapshot emptyLegacySnapshot() {
    return new CanonicalGameplayLegacyMigrationSourceSnapshot(
        EnumSet.of(
            CanonicalGameplayLegacyMigrationSourceSnapshot.Family.TENANT_SESSION_CONTEXT,
            CanonicalGameplayLegacyMigrationSourceSnapshot.Family.SESSION_ALIAS_CONTEXT,
            CanonicalGameplayLegacyMigrationSourceSnapshot.Family.GAMEPLAY_IDENTITY_CONTEXT,
            CanonicalGameplayLegacyMigrationSourceSnapshot.Family.GAMEPLAY_NAME_CONTEXT,
            CanonicalGameplayLegacyMigrationSourceSnapshot.Family.MOVEMENT_EFFECT,
            CanonicalGameplayLegacyMigrationSourceSnapshot.Family.DURABLE_EFFECT),
        List.of());
  }

  private static CanonicalGameplayBindingInventorySnapshot emptyCanonicalSnapshot(long revision) {
    return new CanonicalGameplayBindingInventorySnapshot(
        BigInteger.valueOf(revision),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static List<CanonicalGameplayBindingInventorySnapshot> nonemptyCanonicalSnapshots() {
    CanonicalRows rows = canonicalRows();
    return List.of(
        canonicalSnapshot(
            List.of(rows.activeBinding()), List.of(), List.of(), List.of(), List.of(), List.of()),
        canonicalSnapshot(
            List.of(rows.pendingBinding()), List.of(), List.of(), List.of(), List.of(), List.of()),
        canonicalSnapshot(
            List.of(), List.of(rows.transition()), List.of(), List.of(), List.of(), List.of()),
        canonicalSnapshot(
            List.of(), List.of(), List.of(rows.reservation()), List.of(), List.of(), List.of()),
        canonicalSnapshot(
            List.of(),
            List.of(),
            List.of(),
            List.of(rows.accountObligation()),
            List.of(),
            List.of()),
        canonicalSnapshot(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(rows.issuerObligation()),
            List.of()),
        canonicalSnapshot(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(rows.regionObligation())));
  }

  private static CanonicalGameplayBindingInventorySnapshot canonicalSnapshot(
      List<CanonicalGameplayBindingInventoryEntry> bindings,
      List<CanonicalGameplayBindingTransitionSnapshot> transitions,
      List<CanonicalIssuerPartitionReservation> reservations,
      List<CanonicalGameplayBindingAccountIndexObligation> accountObligations,
      List<CanonicalGameplayBindingIssuerIndexObligation> issuerObligations,
      List<CanonicalGameplayBindingRegionBridgeObligation> regionObligations) {
    return new CanonicalGameplayBindingInventorySnapshot(
        BigInteger.valueOf(7),
        bindings,
        transitions,
        reservations,
        accountObligations,
        issuerObligations,
        regionObligations);
  }

  private static CanonicalRows canonicalRows() {
    UUID accountId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID namespaceId = UUID.randomUUID();
    UUID characterId = UUID.randomUUID();
    UUID gameInstanceId = UUID.randomUUID();
    UUID regionId = UUID.randomUUID();
    UUID issuerId = UUID.randomUUID();
    UUID transitionId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    UUID accountIndexFence = UUID.randomUUID();
    UUID reservationFence = UUID.randomUUID();
    UUID issuerCoverageOperationId = UUID.randomUUID();
    BigInteger rowRevision = BigInteger.valueOf(5);
    BigInteger generation = BigInteger.valueOf(2);
    CanonicalGameplayBindingIdentity identity =
        new CanonicalGameplayBindingIdentity(
            accountId,
            tenantId,
            namespaceId,
            characterId,
            "SHARED",
            gameInstanceId,
            29L,
            UUID.randomUUID().toString(),
            regionId,
            BigInteger.ONE,
            issuerId,
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.valueOf(8),
            BigInteger.valueOf(32));
    var ref = identity.bindingRef();
    CanonicalGameplayBindingInventoryEntry active =
        new CanonicalGameplayBindingInventoryEntry(
            identity,
            ref,
            generation,
            accountIndexFence,
            reservationId,
            transitionId,
            CanonicalGameplayBindingInventoryEntry.Lifecycle.ACTIVE,
            CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED,
            CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED,
            rowRevision);
    CanonicalGameplayBindingInventoryEntry pending =
        new CanonicalGameplayBindingInventoryEntry(
            identity,
            ref,
            generation,
            accountIndexFence,
            reservationId,
            transitionId,
            CanonicalGameplayBindingInventoryEntry.Lifecycle.CANDIDATE_PREPARED,
            CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED,
            CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED,
            rowRevision);
    CanonicalGameplayBindingTransitionSnapshot transition =
        new CanonicalGameplayBindingTransitionSnapshot(
            transitionId,
            identity,
            generation,
            ref,
            rowRevision,
            reservationId,
            reservationFence,
            accountIndexFence,
            null,
            null,
            CanonicalGameplayBindingTransitionSnapshot.Status.PREPARED,
            CanonicalGameplayAccountCoverageEvidence.noActiveFlow());
    CanonicalIssuerPartitionReservation reservation =
        new CanonicalIssuerPartitionReservation(
            reservationId,
            identity,
            generation,
            transitionId,
            ref.partitionId(identity.issuerIndexPartitionCount()),
            CanonicalIssuerPartitionReservation.Lifecycle.RESERVED,
            reservationFence,
            issuerCoverageOperationId,
            BigInteger.ONE,
            BigInteger.ONE,
            rowRevision,
            rowRevision);
    CanonicalGameplayBindingAccountIndexObligation accountObligation =
        new CanonicalGameplayBindingAccountIndexObligation(
            transitionId,
            0,
            ref,
            accountId,
            tenantId,
            CanonicalGameplayBindingAccountIndexObligation.Action.ADD_OR_RETAIN,
            generation,
            null,
            accountIndexFence,
            CanonicalGameplayBindingAccountIndexObligation.ExecutionPhase.BEFORE_FINAL_CAS,
            CanonicalGameplayBindingAccountIndexObligation.Status.REQUIRED,
            rowRevision,
            CanonicalGameplayBindingAccountIndexObligation.ProjectionState.REQUIRED,
            null,
            null);
    CanonicalGameplayBindingIssuerIndexObligation issuerObligation =
        new CanonicalGameplayBindingIssuerIndexObligation(
            transitionId,
            ref,
            generation,
            reservationId,
            CanonicalGameplayBindingIssuerIndexObligation.Status.REQUIRED,
            rowRevision);
    CanonicalGameplayBindingRegionBridgeObligation regionObligation =
        new CanonicalGameplayBindingRegionBridgeObligation(
            transitionId,
            ref,
            generation,
            accountId,
            tenantId,
            gameInstanceId,
            29L,
            identity.sessionId(),
            regionId,
            BigInteger.ONE,
            CanonicalGameplayBindingRegionBridgeObligation.Status.REQUIRED,
            rowRevision);
    return new CanonicalRows(
        active,
        pending,
        transition,
        reservation,
        accountObligation,
        issuerObligation,
        regionObligation);
  }

  private static void verifyNoRedisMutationOrValueRead(StringRedisTemplate redis) {
    verify(redis, never()).delete(any(String.class));
    verify(redis, never()).opsForValue();
    verify(redis, never()).opsForHash();
    verify(redis, never()).opsForSet();
  }

  private static TestCohort cohort() {
    return new TestCohort(0);
  }

  private static final class TestCohort
      implements CanonicalGameplayLegacyMigrationOwner.FencedCohort {
    private final UUID cohortId = UUID.randomUUID();
    private UUID writerFence = UUID.randomUUID();
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
    private final int driftAtFenceCheck;
    private final AtomicInteger fenceChecks = new AtomicInteger();

    private TestCohort(int driftAtFenceCheck) {
      this.driftAtFenceCheck = driftAtFenceCheck;
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
      int check = fenceChecks.incrementAndGet();
      if (driftAtFenceCheck > 0 && check == driftAtFenceCheck) {
        writerFence = UUID.randomUUID();
      }
      if (!identity.equals(expectedIdentity)) {
        throw new CanonicalGameplayBindingInventoryConflictException(
            "synthetic storage identity drift");
      }
    }
  }

  private record ScanResult(List<String> keys, long terminalCursorId, boolean failCursor) {
    private static ScanResult empty() {
      return withKeys(List.of());
    }

    private static ScanResult withKeys(List<String> keys) {
      return new ScanResult(keys, 0L, false);
    }

    private static ScanResult withCursor(List<String> keys, long cursorId) {
      return new ScanResult(keys, cursorId, false);
    }

    private static ScanResult failedCursor() {
      return new ScanResult(List.of(), 0L, true);
    }
  }

  private record CanonicalRows(
      CanonicalGameplayBindingInventoryEntry activeBinding,
      CanonicalGameplayBindingInventoryEntry pendingBinding,
      CanonicalGameplayBindingTransitionSnapshot transition,
      CanonicalIssuerPartitionReservation reservation,
      CanonicalGameplayBindingAccountIndexObligation accountObligation,
      CanonicalGameplayBindingIssuerIndexObligation issuerObligation,
      CanonicalGameplayBindingRegionBridgeObligation regionObligation) {}
}
