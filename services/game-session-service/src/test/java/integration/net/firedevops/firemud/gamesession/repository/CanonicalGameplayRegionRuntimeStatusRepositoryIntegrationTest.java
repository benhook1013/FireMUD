package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CanonicalGameplayRegionRuntimeStatusRepositoryIntegrationTest
    extends CanonicalGameplayRegionRuntimeStatusRepositoryIntegrationTestSupport {
  @Test
  void initializesOnlyAnAbsentRegionAtItsFirstEpochAndFirstTick() {
    assertThatThrownBy(
            () ->
                repository.installOwner(
                    source,
                    2L,
                    null,
                    FIRST_LEASE_CORRELATION,
                    "game-session-service",
                    "instance-a",
                    Instant.parse("2026-10-26T00:00:00Z")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(repository.find(source)).isEmpty();

    var installed =
        repository.installOwner(
            source,
            1L,
            null,
            FIRST_LEASE_CORRELATION,
            "game-session-service",
            "instance-a",
            Instant.parse("2026-10-26T00:00:01Z"));

    assertThat(installed.tenantRegionTag())
        .isEqualTo(
            CanonicalGameplayRegionRedisKeys.tenantRegionTag(
                TENANT_ID, source.gameInstanceId(), OPERATIONAL_REGION_ID));
    assertThat(installed.regionEpoch()).isEqualTo(1L);
    assertThat(installed.lifecycleEpoch()).isEqualTo(4L);
    assertThat(installed.lastCommittedTickId()).isEqualTo(-1L);
    assertThat(installed.executorFence()).isPositive();
    assertThat(repository.find(source)).contains(installed);
  }

  @Test
  void ownerReadRejectsAnAmbientSynchronizationContext() {
    try {
      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> repository.find(source)).isInstanceOf(IllegalStateException.class);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void exactOwnerRetryKeepsFenceAndReplacementIsHeld() {
    var initial =
        repository.installOwner(
            source,
            1L,
            null,
            FIRST_LEASE_CORRELATION,
            "game-session-service",
            "instance-a",
            Instant.parse("2026-10-26T00:00:01Z"));
    long sequenceAfterInstall = fenceSequenceValue();
    var sameGrantRetry =
        repository.installOwner(
            source,
            1L,
            initial,
            FIRST_LEASE_CORRELATION,
            "game-session-service",
            "instance-a",
            Instant.parse("2026-10-26T00:00:01Z"));

    assertThat(sameGrantRetry).isEqualTo(initial);
    assertThat(fenceSequenceValue()).isEqualTo(sequenceAfterInstall);

    dsl.execute(
        "UPDATE game_session_region_runtime_status"
            + " SET last_committed_tick_batch_id = 'batch-7', last_committed_tick_id = 7"
            + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?",
        TENANT_ID,
        source.gameInstanceId(),
        OPERATIONAL_REGION_ID);
    var current = repository.find(source).orElseThrow();

    assertThatThrownBy(
            () ->
                repository.installOwner(
                    source,
                    1L,
                    current,
                    SECOND_LEASE_CORRELATION,
                    "game-session-service",
                    "instance-b",
                    Instant.parse("2026-10-26T00:00:02Z")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(repository.find(source)).contains(current);
    assertThat(fenceSequenceValue()).isEqualTo(sequenceAfterInstall);
  }

  @Test
  void sameNaturalScopeRejectsChangedWorldCaptureEvidence() {
    repository.installOwner(
        source,
        1L,
        null,
        FIRST_LEASE_CORRELATION,
        "game-session-service",
        "instance-a",
        Instant.parse("2026-10-26T00:00:01Z"));

    assertThatThrownBy(
            () ->
                repository.find(
                    source(
                        source.gameInstanceId(),
                        new UUID(0x6666666666666666L, 0x8666666666666666L))))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void ownerGrantAcceptsOnlyChangedLifecycleReadCorrelationAndKeepsInitialRequestBytes() {
    var initial =
        repository.installOwner(
            source,
            1L,
            null,
            FIRST_LEASE_CORRELATION,
            "game-session-service",
            "instance-a",
            Instant.parse("2026-10-26T00:00:01Z"));
    var laterRequest =
        lifecycleRequest(
            source.lifecycleRequest(),
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            source.lifecycleRequest().controlPlaneRequestId());
    var laterSource = source(source, laterRequest);

    var later =
        repository.installOwner(
            laterSource,
            1L,
            initial,
            FIRST_LEASE_CORRELATION,
            "game-session-service",
            "instance-a",
            Instant.parse("2026-10-26T00:00:02Z"));

    assertThat(later).isEqualTo(initial);
    assertThat(later.regionEpoch()).isEqualTo(1L);
    assertThat(later.lastCommittedTickId()).isEqualTo(-1L);
    assertThat(
            dsl.fetchSingle(
                    "SELECT lifecycle_request_bytes FROM game_session_region_runtime_status"
                        + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?",
                    TENANT_ID,
                    source.gameInstanceId(),
                    OPERATIONAL_REGION_ID)
                .get("lifecycle_request_bytes", byte[].class))
        .containsExactly(source.lifecycleRequest().canonicalBytes());
    assertThat(repository.find(laterSource)).contains(later);

    var changedTargetSelector =
        source(
            source,
            lifecycleRequest(
                laterRequest, laterRequest.readRequestId(), "different-control-plane-request"));
    assertThatThrownBy(() -> repository.find(changedTargetSelector))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void sameRegionInDifferentGameInstancesHasDistinctPersistedOwnerTags() {
    var first =
        repository.installOwner(
            source,
            1L,
            null,
            FIRST_LEASE_CORRELATION,
            "game-session-service",
            "instance-a",
            Instant.parse("2026-10-26T00:00:01Z"));
    var otherGameSource = source(OTHER_GAME_INSTANCE_ID, CAPTURE_ID);
    var otherGame =
        repository.installOwner(
            otherGameSource,
            1L,
            null,
            SECOND_LEASE_CORRELATION,
            "game-session-service",
            "instance-a",
            Instant.parse("2026-10-26T00:00:02Z"));

    assertThat(first.regionId()).isEqualTo(otherGame.regionId());
    assertThat(first.tenantRegionTag()).isNotEqualTo(otherGame.tenantRegionTag());
    assertThat(repository.find(source)).contains(first);
    assertThat(repository.find(otherGameSource)).contains(otherGame);
  }

  @Test
  void concurrentReplacementRequestsLeaveTheOriginalFence() throws Exception {
    var prior =
        repository.installOwner(
            source,
            1L,
            null,
            FIRST_LEASE_CORRELATION,
            "game-session-service",
            "instance-prior",
            Instant.parse("2026-10-26T00:00:01Z"));
    long sequenceBefore = fenceSequenceValue();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus> first =
          executor.submit(
              () -> installAfterBarrier(prior, "e".repeat(64), "instance-a", ready, start));
      Future<net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus>
          second =
              executor.submit(
                  () -> installAfterBarrier(prior, "f".repeat(64), "instance-b", ready, start));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var firstResult = first.get(10, TimeUnit.SECONDS);
      var secondResult = second.get(10, TimeUnit.SECONDS);
      assertThat(firstResult).isNull();
      assertThat(secondResult).isNull();
      assertThat(repository.find(source)).contains(prior);
      assertThat(fenceSequenceValue()).isEqualTo(sequenceBefore);
    }
  }
}
