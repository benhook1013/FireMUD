package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CanonicalGameplayRegionRuntimeStatusRepositoryIntegrationTest {
  private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID OTHER_GAME_INSTANCE_ID =
      UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID CANONICAL_REGION_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID OPERATIONAL_REGION_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID CAPTURE_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final String GRAPH_DIGEST = "a".repeat(64);
  private static final String PREPARATION_DIGEST = "sha256:" + "b".repeat(64);
  private static final String FIRST_LEASE_CORRELATION = "c".repeat(64);
  private static final String SECOND_LEASE_CORRELATION = "d".repeat(64);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @TempDir Path directory;

  private DSLContext dsl;
  private CanonicalGameplayRegionRuntimeStatusRepository repository;
  private CanonicalGameplayRegionRuntimeStatusRepository.Source source;

  @BeforeAll
  void setUpRepository() {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(POSTGRES);

    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(POSTGRES.getDriverClassName());
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());

    CanonicalGameplayIssuerTestFixtures.migrationConfiguration(
            dataSource,
            "public",
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize())
        .load()
        .migrate();

    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    repository = new CanonicalGameplayRegionRuntimeStatusRepository(dsl);
  }

  @BeforeEach
  void selectFreshOwnerScopeWithoutDiscardingRetainedCustody() {
    source = source(UUID.randomUUID(), CAPTURE_ID);
  }

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
  void exactOwnerRetryReplacesFenceWithoutResettingRegionTimeline() {
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

    var retry =
        repository.installOwner(
            source,
            1L,
            current,
            SECOND_LEASE_CORRELATION,
            "game-session-service",
            "instance-b",
            Instant.parse("2026-10-26T00:00:02Z"));

    assertThat(retry.regionEpoch()).isEqualTo(1L);
    assertThat(retry.executorFence()).isGreaterThan(initial.executorFence());
    assertThat(retry.leaseTokenCorrelation()).isEqualTo(SECOND_LEASE_CORRELATION);
    assertThat(retry.ownerInstanceId()).isEqualTo("instance-b");
    assertThat(retry.lastCommittedTickId()).isEqualTo(7L);
    assertThat(retry.lastCommittedTickBatchId()).isEqualTo("batch-7");
    assertThatThrownBy(
            () ->
                repository.installOwner(
                    source,
                    1L,
                    initial,
                    FIRST_LEASE_CORRELATION,
                    "game-session-service",
                    "instance-a",
                    Instant.parse("2026-10-26T00:00:03Z")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(repository.find(source)).contains(retry);
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
            SECOND_LEASE_CORRELATION,
            "game-session-service",
            "instance-b",
            Instant.parse("2026-10-26T00:00:02Z"));

    assertThat(later.executorFence()).isGreaterThan(initial.executorFence());
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
  void concurrentNewGrantsFromOnePriorOwnerInstallOnlyOneNewFence() throws Exception {
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
      assertThat((firstResult == null) ^ (secondResult == null)).isTrue();

      var committed = repository.find(source).orElseThrow();
      assertThat(committed.executorFence()).isGreaterThan(prior.executorFence());
      assertThat(committed.regionEpoch()).isEqualTo(prior.regionEpoch());
      assertThat(fenceSequenceValue()).isEqualTo(sequenceBefore + 1L);
      assertThat(committed).isEqualTo(firstResult == null ? secondResult : firstResult);
    }
  }

  @Test
  void actualAuthorizationHoldingRegionRowMakesFreezeWaitAndRetainsThatOriginal() throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), Clock.systemUTC());
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution);
        var gate = new RegionRowLockGate();
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      Future<?> authorization =
          executor.submit(
              () ->
                  proof
                      .capacity(gate.attach(proof.sql))
                      .authorize(
                          proof.admission,
                          template,
                          account,
                          issuer,
                          proof.execution,
                          transferDeadline()));
      gate.awaitLock();
      Future<UUID> freeze =
          executor.submit(
              () ->
                  proof.transfers.within(
                      transferDeadline(),
                      ignored -> proof.transfers.begin(proof.scope, proof.prior)));
      try {
        assertDatabaseWaitsFor(proof.sql, gate.backendPid());
        assertThat(freeze.isDone()).isFalse();
      } finally {
        gate.release();
      }
      authorization.get(10, TimeUnit.SECONDS);
      UUID id = freeze.get(10, TimeUnit.SECONDS);
      assertThat(id).isNotNull();
      assertThat(proof.executionState(proof.execution)).isEqualTo("AUTHORIZED");
      List<CanonicalGameplayRegionOwnerTransferRepository.Item> captured =
          proof.transfers.within(transferDeadline(), ignored -> proof.transfers.capture(id));
      assertThat(captured)
          .extracting(item -> item.execution().digest())
          .containsExactly(proof.execution.digest());
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
    }
  }

  @Test
  void freezeHoldingRegionRowMakesActualAuthorizationWaitThenRefuseBeforeDispatch()
      throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), Clock.systemUTC());
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution);
        var gate = new RegionRowLockGate();
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      var seeded = proof.newControlledExecution();
      proof.seedUndeliveredDispatch(seeded);
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      var freezing = proof.transfers(gate.attach(proof.sql));
      Future<UUID> freeze =
          executor.submit(
              () ->
                  freezing.within(
                      transferDeadline(), ignored -> freezing.begin(proof.scope, proof.prior)));
      gate.awaitLock();
      Future<Boolean> refused =
          executor.submit(
              () -> {
                try {
                  proof
                      .capacity(proof.sql)
                      .authorize(
                          proof.admission,
                          template,
                          account,
                          issuer,
                          proof.execution,
                          transferDeadline());
                  return false;
                } catch (IllegalStateException frozen) {
                  return true;
                }
              });
      try {
        assertDatabaseWaitsFor(proof.sql, gate.backendPid());
        assertThat(refused.isDone()).isFalse();
      } finally {
        gate.release();
      }
      UUID id = freeze.get(10, TimeUnit.SECONDS);
      assertThat(refused.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(proof.executionState(proof.execution)).isNull();
      assertThat(proof.executionState(seeded)).isEqualTo("INSTALL_DISPATCHED");
      assertThat(proof.physicalExecutionExists(proof.execution)).isFalse();
      // The INSERT trigger independently takes the same row lock and rejects raw late dispatch.
      assertSqlGuard(() -> proof.seedUndeliveredDispatch(proof.execution));
      List<CanonicalGameplayRegionOwnerTransferRepository.Item> captured =
          proof.transfers.within(transferDeadline(), ignored -> proof.transfers.capture(id));
      assertThat(captured)
          .extracting(item -> item.execution().digest())
          .containsExactly(seeded.digest());
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
    }
  }

  @Test
  void acknowledgedBridgeRetryWaitsBeforeTransitionWhileAuthorizationOwnsRegion() throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), Clock.systemUTC());
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution);
        var gate = new RegionRowLockGate();
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      Future<?> authorization =
          executor.submit(
              () ->
                  proof
                      .capacity(gate.attach(proof.sql))
                      .authorize(
                          proof.admission,
                          template,
                          account,
                          issuer,
                          proof.execution,
                          transferDeadline()));
      gate.awaitLock();
      Future<?> replay = executor.submit(proof::replayBridge);
      try {
        assertDatabaseWaitsFor(proof.sql, gate.backendPid());
        assertThat(replay.isDone()).isFalse();
      } finally {
        gate.release();
      }
      authorization.get(10, TimeUnit.SECONDS);
      replay.get(10, TimeUnit.SECONDS);
      assertThat(proof.executionState(proof.execution)).isEqualTo("AUTHORIZED");
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
    }
  }

  @Test
  void frozenContinuationWaitsBeforeRegionWhileAcknowledgedBridgeOwnsInventory() throws Exception {
    try (var gate = new InventoryRowLockGate();
        var proof = new TransferSqlProof(gate);
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.begin();
      var original =
          proof
              .transfers
              .within(transferDeadline(), ignored -> proof.transfers.capture(id))
              .getFirst();
      String before = proof.transferImage(id);
      gate.arm();
      Future<?> replay = executor.submit(proof::replayBridge);
      gate.awaitLock();
      Future<?> continuation =
          executor.submit(
              () ->
                  proof.transfers.within(
                      transferDeadline(),
                      ignored -> {
                        proof.transfers.requireFrozenOriginal(original, proof.scope, proof.prior);
                        return null;
                      }));
      try {
        assertDatabaseWaitsFor(proof.sql, gate.backendPid());
        assertThat(continuation.isDone()).isFalse();
      } finally {
        gate.release();
      }
      replay.get(10, TimeUnit.SECONDS);
      continuation.get(10, TimeUnit.SECONDS);
      assertThat(proof.transferImage(id)).isEqualTo(before);
      assertThat(proof.executionState(proof.execution)).isEqualTo("INSTALL_DISPATCHED");
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
    }
  }

  @Test
  void competingTransferCreatorsAndLostBeginReplyKeepOneOriginalIntent() throws Exception {
    try (var proof = new TransferSqlProof();
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      proof.seedUndeliveredDispatch(proof.execution);
      var ready = new CountDownLatch(2);
      var start = new CountDownLatch(1);
      var other = proof.transfers(proof.sql);
      Future<UUID> first =
          executor.submit(() -> beginAfterBarrier(proof, proof.transfers, ready, start));
      Future<UUID> second = executor.submit(() -> beginAfterBarrier(proof, other, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      UUID retained = first.get(10, TimeUnit.SECONDS);
      assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(retained);
      String original = proof.transferImage(retained);
      assertThatThrownBy(
              () -> {
                other.within(transferDeadline(), ignored -> other.begin(proof.scope, proof.prior));
                throw new IllegalStateException(
                    "Controlled lost begin reply after committed readback");
              })
          .hasMessage("Controlled lost begin reply after committed readback");
      var recreated = proof.transfers(proof.sql);
      UUID replayed =
          recreated.within(
              transferDeadline(), ignored -> recreated.begin(proof.scope, proof.prior));
      assertThat(replayed).isEqualTo(retained);
      assertThat(proof.transferImage(retained)).isEqualTo(original);
      assertThat(
              proof
                  .sql
                  .fetchSingle("SELECT count(*) FROM game_session_region_owner_transfer")
                  .get(0, Long.class))
          .isEqualTo(1L);
    }
  }

  @Test
  void boundedCensusAndVerificationResumeBeyondThirtyTwoWithoutLosingAnyOriginal()
      throws Exception {
    try (var proof = new TransferSqlProof()) {
      // Controlled reservation envelopes seed SQL custody, not Account-authorized multiattempt
      // behavior. Every candidate, bridge, mapping and owner source is retained by its real owner.
      List<OriginalNewCapacityExecution> originals = new ArrayList<>();
      for (int index = 0; index < 65; index++) {
        var original = proof.newControlledExecution();
        originals.add(original);
        proof.seedUndeliveredDispatch(original);
      }
      UUID id = proof.begin();
      var first = proof.captureAndFence(id);
      assertThat(first).hasSize(32);
      assertThat(first)
          .allMatch(item -> item.execution().transitionId().equals(proof.execution.transitionId()));
      assertThatThrownBy(
              () ->
                  proof.transfers.within(
                      transferDeadline(),
                      ignored -> proof.transfers.frozenOriginal(proof.execution.transitionId())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Normal region owner transfer is held");
      assertThatThrownBy(
              () ->
                  proof.transfers.within(
                      transferDeadline(), ignored -> proof.transfers.verificationCursor(id)))
          .isInstanceOf(IllegalStateException.class);
      proof.recreateTransferOwners();
      assertThat(proof.begin()).isEqualTo(id);
      assertThat(proof.captureAndFence(id)).hasSize(32);
      assertThatThrownBy(
              () ->
                  proof.transfers.within(
                      transferDeadline(), ignored -> proof.transfers.verificationCursor(id)))
          .isInstanceOf(IllegalStateException.class);
      proof.recreateTransferOwners();
      assertThat(proof.captureAndFence(id)).hasSize(1);
      assertThat(proof.captureAndFence(id)).isEmpty();

      List<String> captured = new ArrayList<>();
      long cursor = -1;
      for (int size : List.of(32, 32, 1)) {
        long after = cursor;
        var page =
            proof.transfers.within(transferDeadline(), ignored -> proof.transfers.page(id, after));
        assertThat(page).hasSize(size);
        captured.addAll(page.stream().map(item -> item.execution().digest()).toList());
        cursor = page.getLast().ordinal();
      }
      assertThat(captured)
          .containsExactlyInAnyOrderElementsOf(
              originals.stream().map(OriginalNewCapacityExecution::digest).toList());
      assertThat(cursor).isEqualTo(64L);
      long initialVerificationCursor =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.verificationCursor(id));
      assertThat(initialVerificationCursor).isEqualTo(-1L);
      proof.verifyPage(id);
      boolean firstPageComplete =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      assertThat(firstPageComplete).isFalse();
      assertThatThrownBy(() -> proof.install(id)).isInstanceOf(IllegalStateException.class);
      proof.recreateTransferOwners();
      long resumedVerificationCursor =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.verificationCursor(id));
      assertThat(resumedVerificationCursor).isEqualTo(31L);
      proof.verifyPage(id);
      boolean secondPageComplete =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      assertThat(secondPageComplete).isFalse();
      proof.recreateTransferOwners();
      proof.verifyPage(id);
      boolean allPagesComplete =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      assertThat(allPagesComplete).isTrue();
      var installed = proof.install(id);
      assertThat(installed.executorFence()).isGreaterThan(proof.prior.executorFence());
      assertThat(proof.currentOwner()).isEqualTo(installed);
      assertThat(proof.transfers.readCurrent(id, 64).controlState()).isEqualTo("COMPLETED");
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_region_owner_transfer_item"
                          + " WHERE transfer_id=? AND fence_readback IS NOT NULL AND verified_round IS NOT NULL",
                      id)
                  .get(0, Long.class))
          .isEqualTo(65L);
      assertThat(
              proof
                  .sql
                  .fetch("SELECT status FROM game_session_original_new_capacity_execution")
                  .getValues("status", String.class))
          .containsOnly("INSTALL_DISPATCHED");
    }
  }

  @Test
  void finalQualificationRechecksLostFenceBeyondTheFirstPageWithoutRecreatingIt() throws Exception {
    try (var proof = new TransferSqlProof()) {
      for (int index = 0; index < 33; index++) {
        proof.seedUndeliveredDispatch(proof.newControlledExecution());
      }
      UUID id = proof.begin();
      assertThat(proof.captureAndFence(id)).hasSize(32);
      assertThat(proof.captureAndFence(id)).hasSize(1);
      proof.verifyPage(id);
      proof.recreateTransferOwners();
      proof.verifyPage(id);
      boolean allPagesComplete =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      assertThat(allPagesComplete).isTrue();
      var last =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.page(id, 31).getFirst());
      assertThat(last.ordinal()).isEqualTo(32L);
      String receipt = proof.itemImage(id);
      proof.losePhysicalExecution(last.execution());
      proof.recreateTransferOwners();
      assertThatThrownBy(() -> proof.install(id)).isInstanceOf(IllegalStateException.class);
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      assertThat(proof.transfers.readCurrent(id, 32).controlState()).isEqualTo("FROZEN");
      assertThat(proof.physicalExecutionExists(last.execution())).isFalse();
      assertThat(proof.itemImage(id)).isEqualTo(receipt);
      assertThat(proof.begin()).isEqualTo(id);
      assertThat(proof.captureAndFence(id)).isEmpty();
      assertThat(proof.physicalExecutionExists(last.execution())).isFalse();
    }
  }

  @Test
  void originalCapacityExecutionRefusesForgedInitialAuthorizationAndTerminalEvidence()
      throws Exception {
    try (var proof = new TransferSqlProof()) {
      byte[] forgedReadback = {1};
      for (String status : List.of("AUTHORIZED", "COMMITTED", "CLOSED", "INSTALL_DISPATCHED")) {
        byte[] readback = "AUTHORIZED".equals(status) ? null : forgedReadback;
        assertThatThrownBy(() -> proof.insertExecution(proof.execution, status, readback))
            .isInstanceOf(org.jooq.exception.DataAccessException.class)
            .hasMessageContaining(
                "Original NEW capacity execution cannot start with an authorization or terminal proof");
      }
      assertThat(proof.executionState(proof.execution)).isNull();
      assertThat(proof.physicalExecutionExists(proof.execution)).isFalse();
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);

      proof.seedUndeliveredDispatch(proof.execution);
      assertThat(proof.executionState(proof.execution)).isEqualTo("INSTALL_DISPATCHED");
      assertThat(proof.physicalExecutionExists(proof.execution)).isFalse();
    }
  }

  @Test
  void rawOwnerReplacementCannotSkipFrozenIncompleteOrChangedExactTransfer() throws Exception {
    try (var proof = new TransferSqlProof()) {
      assertThatThrownBy(
              () ->
                  proof.sql.transaction(
                      configuration ->
                          CanonicalGameplayRegionOwnerTransferRepository.completeTerminalOnly(
                              DSL.using(configuration),
                              proof.prior,
                              "unretained-terminal-successor",
                              proof.prior.executorFence().longValueExact() + 1,
                              "e".repeat(64))))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Normal region owner transfer is held");
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      proof.seedUndeliveredDispatch(proof.execution);
      String original = proof.originalImage();
      assertSqlGuard(() -> proof.rawReplaceOwner("unretained-successor", "e".repeat(64)));
      String forgedOwner = "forged-successor";
      String forgedLease = "e".repeat(64);
      long forgedFence =
          proof
              .sql
              .fetchSingle("SELECT nextval('game_session_region_executor_fence_seq')")
              .get(0, Long.class);
      for (String state : List.of("COMPLETED", "FROZEN")) {
        boolean completed = "COMPLETED".equals(state);
        assertThatThrownBy(
                () ->
                    proof.sql.execute(
                        "INSERT INTO game_session_region_owner_transfer"
                            + " (transfer_id,tenant_id,game_instance_id,region_id,region_epoch,old_owner_id,old_owner_fence,"
                            + " old_lease_correlation,source_digest,intent_digest,state,incarnation_digest,verification_round,"
                            + " verification_cursor,verification_complete,new_owner_id,new_owner_fence,new_lease_correlation)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,true,?,?,?)",
                        UUID.randomUUID(),
                        proof.scope.tenantId(),
                        proof.scope.gameInstanceId(),
                        proof.scope.regionId(),
                        proof.prior.regionEpoch(),
                        proof.prior.ownerInstanceId(),
                        proof.prior.executorFence().longValueExact(),
                        proof.prior.leaseTokenCorrelation(),
                        "sha256:" + "b".repeat(64),
                        "sha256:" + "c".repeat(64),
                        state,
                        "sha256:" + "a".repeat(64),
                        UUID.randomUUID(),
                        0L,
                        completed ? forgedOwner : null,
                        completed ? forgedFence : null,
                        completed ? forgedLease : null))
            .isInstanceOf(org.jooq.exception.DataAccessException.class)
            .hasMessageContaining("Region transfer must start as a clean frozen intent");
      }
      assertSqlGuard(
          () ->
              proof.sql.execute(
                  "UPDATE game_session_region_runtime_status SET owner_instance_id=?,executor_fence=?,lease_token_correlation=?"
                      + " WHERE tenant_id=? AND game_instance_id=? AND region_id=?",
                  forgedOwner,
                  forgedFence,
                  forgedLease,
                  proof.scope.tenantId(),
                  proof.scope.gameInstanceId(),
                  proof.scope.regionId()));
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      UUID id = proof.begin();
      assertSqlGuard(() -> proof.rawReplaceOwner("frozen-successor", "e".repeat(64)));
      proof.transfers.within(transferDeadline(), ignored -> proof.transfers.capture(id));
      assertSqlGuard(
          () ->
              proof.sql.execute(
                  "UPDATE game_session_region_owner_transfer SET state='COMPLETED',"
                      + " new_owner_id='forged-successor',new_owner_fence=?,new_lease_correlation=?,"
                      + " incarnation_digest=?,verification_round=?,verification_complete=true WHERE transfer_id=?",
                  proof.prior.executorFence().longValueExact() + 1,
                  "e".repeat(64),
                  "sha256:" + "a".repeat(64),
                  UUID.randomUUID(),
                  id));
      assertThat(proof.originalImage()).isEqualTo(original);
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      proof.captureAndFence(id);
      proof.verifyPage(id);
      proof.transfers.within(transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      var installed = proof.install(id);
      String completed = proof.transferImage(id);
      assertSqlGuard(() -> proof.rawReplaceOwner("different-successor", "f".repeat(64)));
      assertThatThrownBy(() -> proof.seedUndeliveredDispatch(proof.newControlledExecution()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("Region dispatch requires the exact current selected owner");
      assertThat(proof.currentOwner()).isEqualTo(installed);
      assertThat(proof.transferImage(id)).isEqualTo(completed);
    }
  }

  @Test
  void recreatedRepositoryRetainsLostFenceReplyAndImmutableOriginalReceiptsAndCompletion()
      throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.begin();
      var item =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.capture(id).getFirst());
      String original = proof.originalImage();
      assertThatThrownBy(
              () -> {
                proof.fence(item);
                // The SQL receipt has committed, but the caller did not receive its result.
                throw new IllegalStateException("Controlled lost retained fence reply");
              })
          .hasMessage("Controlled lost retained fence reply");
      String receipt = proof.itemImage(id);
      proof.recreateTransferOwners();
      assertThat(proof.begin()).isEqualTo(id);
      var replay =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.page(id, -1).getFirst());
      assertThat(replay.execution().canonicalBytes())
          .containsExactly(item.execution().canonicalBytes());
      proof.fence(replay);
      assertThat(proof.itemImage(id)).isEqualTo(receipt);
      assertSqlGuard(
          () ->
              proof.sql.execute(
                  "UPDATE game_session_original_new_capacity_execution SET selected_owner_id='changed' WHERE execution_digest=?",
                  proof.execution.digest()));
      assertSqlGuard(
          () ->
              proof.sql.execute(
                  "UPDATE game_session_region_owner_transfer SET intent_digest=? WHERE transfer_id=?",
                  "sha256:" + "f".repeat(64),
                  id));
      assertSqlGuard(
          () ->
              proof.sql.execute(
                  "UPDATE game_session_region_owner_transfer_item SET canonical_execution=? WHERE transfer_id=?",
                  "changed-original".getBytes(StandardCharsets.UTF_8),
                  id));
      assertSqlGuard(
          () ->
              proof.sql.execute(
                  "UPDATE game_session_region_owner_transfer_item SET fence_readback=? WHERE transfer_id=?",
                  "changed-readback".getBytes(StandardCharsets.UTF_8),
                  id));
      for (String table :
          List.of(
              "game_session_original_new_capacity_execution",
              "game_session_region_owner_transfer",
              "game_session_region_owner_transfer_item")) {
        assertSqlGuard(() -> proof.sql.execute("DELETE FROM " + table));
        assertSqlGuard(() -> proof.sql.execute("TRUNCATE TABLE " + table + " CASCADE"));
      }
      assertThat(proof.originalImage()).isEqualTo(original);
      assertThat(proof.itemImage(id)).isEqualTo(receipt);
      proof.verifyPage(id);
      proof.transfers.within(transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      var installed = proof.install(id);
      String completion = proof.transferImage(id);
      proof.recreateTransferOwners();
      assertThat(proof.transfers.readCurrent(id, 0).controlState()).isEqualTo("COMPLETED");
      assertThat(proof.transfers.readCurrent(id, 0).completionDigest()).isNotBlank();
      assertThatThrownBy(
              () ->
                  proof.transfers.within(
                      transferDeadline(), ignored -> proof.transfers.capture(id)))
          .isInstanceOf(IllegalStateException.class);
      assertSqlGuard(
          () ->
              proof.sql.execute(
                  "UPDATE game_session_region_owner_transfer SET new_owner_id='changed-successor' WHERE transfer_id=?",
                  id));
      assertSqlGuard(
          () ->
              proof.sql.execute(
                  "UPDATE game_session_region_owner_transfer_item SET verified_round=? WHERE transfer_id=?",
                  UUID.randomUUID(),
                  id));
      assertThat(proof.transferImage(id)).isEqualTo(completion);
      assertThat(proof.originalImage()).isEqualTo(original);
      assertThat(proof.currentOwner()).isEqualTo(installed);
    }
  }

  private static UUID beginAfterBarrier(
      TransferSqlProof proof,
      CanonicalGameplayRegionOwnerTransferRepository transfers,
      CountDownLatch ready,
      CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
    return transfers.within(
        transferDeadline(), ignored -> transfers.begin(proof.scope, proof.prior));
  }

  private static void assertDatabaseWaitsFor(DSLContext sql, int blockerPid) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    do {
      if (Boolean.TRUE.equals(
          sql.fetchSingle(
                  "SELECT EXISTS (SELECT 1 FROM pg_stat_activity"
                      + " WHERE datname=current_database() AND wait_event_type='Lock'"
                      + " AND ? = ANY(pg_blocking_pids(pid)))",
                  blockerPid)
              .get(0, Boolean.class))) return;
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("The competing owner did not physically wait on the held region row");
  }

  private static void assertSqlGuard(Runnable action) {
    assertThatThrownBy(action::run)
        .rootCause()
        .isInstanceOfSatisfying(
            org.postgresql.util.PSQLException.class,
            failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
  }

  private static long transferDeadline() {
    return System.currentTimeMillis() + 5_000;
  }

  /** Holds the actual owner's SELECT FOR UPDATE after PostgreSQL has granted its row lock. */
  private static final class RegionRowLockGate extends DefaultExecuteListener
      implements AutoCloseable {
    private final AtomicBoolean first = new AtomicBoolean(true);
    private final AtomicInteger pid = new AtomicInteger();
    private final CountDownLatch locked = new CountDownLatch(1);
    private final CountDownLatch resume = new CountDownLatch(1);

    DSLContext attach(DSLContext sql) {
      return DSL.using(sql.configuration().derive(new DefaultExecuteListenerProvider(this)));
    }

    @Override
    public void executeEnd(ExecuteContext context) {
      String statement = context.sql();
      if (statement == null
          || !statement.contains("game_session_region_runtime_status")
          || !statement.contains("FOR UPDATE")
          || !first.compareAndSet(true, false)) return;
      try (var query = context.connection().createStatement();
          var result = query.executeQuery("SELECT pg_backend_pid()")) {
        assertThat(result.next()).isTrue();
        pid.set(result.getInt(1));
        locked.countDown();
        if (!resume.await(4, TimeUnit.SECONDS)) {
          throw new AssertionError("Region-row test gate was not released");
        }
      } catch (java.sql.SQLException failure) {
        throw new IllegalStateException("Could not observe actual PostgreSQL lock owner", failure);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Region-row test gate was interrupted", interrupted);
      }
    }

    void awaitLock() throws InterruptedException {
      assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
    }

    int backendPid() {
      return pid.get();
    }

    void release() {
      resume.countDown();
    }

    @Override
    public void close() {
      release();
    }
  }

  /** Stops a real bridge transaction only after its inventory-clock lock was granted. */
  private static final class InventoryRowLockGate extends DefaultExecuteListener
      implements AutoCloseable {
    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicInteger pid = new AtomicInteger();
    private final CountDownLatch locked = new CountDownLatch(1);
    private final CountDownLatch resume = new CountDownLatch(1);

    void arm() {
      armed.set(true);
    }

    @Override
    public void executeEnd(ExecuteContext context) {
      String statement = context.sql();
      if (statement == null
          || !statement.contains("game_session_canonical_binding_inventory_clock")
          || !statement.contains("FOR UPDATE")
          || !armed.compareAndSet(true, false)) return;
      try (var query = context.connection().createStatement();
          var result = query.executeQuery("SELECT pg_backend_pid()")) {
        assertThat(result.next()).isTrue();
        pid.set(result.getInt(1));
        locked.countDown();
        if (!resume.await(4, TimeUnit.SECONDS))
          throw new AssertionError("Inventory-row test gate was not released");
      } catch (java.sql.SQLException failure) {
        throw new IllegalStateException("Could not observe actual inventory lock owner", failure);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Inventory-row test gate was interrupted", interrupted);
      }
    }

    void awaitLock() throws InterruptedException {
      assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
    }

    int backendPid() {
      return pid.get();
    }

    void release() {
      resume.countDown();
    }

    @Override
    public void close() {
      release();
    }
  }

  /**
   * Actual SQL, candidate/issuer/region and capacity/transfer owners over physical PostgreSQL and
   * Redis. Account lease/reservation/OPEN, World/Entity RPC content, grant and attestor custody are
   * controlled inputs. This proves SQL serialization/retention, not Account issuance or activation.
   * Owners use the required named Redis principal; ACL qualification belongs to separate proof.
   */
  private final class TransferSqlProof implements AutoCloseable {
    private final Clock clock = Clock.systemUTC();
    private final RedisClient redis;
    private final CanonicalGameplayAdmissionAbortCompositionFixture fixture;
    private final CanonicalGameplayOriginalNewFinalAdmissionTestFixture.ControlledRegionSources
        upstream;
    private final DSLContext sql;
    private final CanonicalGameplayTenantGameplayTagRepository tags;
    private final RedisScriptCatalog scripts;
    private final CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource
        admission;
    private final CanonicalGameplayRegionRuntimeStatusRepository.Source scope;
    private final CanonicalGameplayRegionRuntimeStatus prior;
    private final OriginalNewCapacityExecution execution;
    private final CanonicalGameplayRegionOwnerCompositionFixture.ExistingBinding region;
    private CanonicalGameplayRegionOwnerTransferRepository transfers;
    private CanonicalGameplayProtectedIncarnationReceiver receiver;
    private CanonicalGameplayRegionOwnerTransferCoordinator coordinator;

    private TransferSqlProof() throws Exception {
      this(new DefaultExecuteListener());
    }

    private TransferSqlProof(DefaultExecuteListener listener) throws Exception {
      String password = UUID.randomUUID().toString();
      try (var administrator =
              RedisClient.create(RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
          var connection = administrator.connect(ByteArrayCodec.INSTANCE)) {
        connection.sync().flushdb();
        connection
            .sync()
            .aclSetuser(
                "gamesession_coord_app",
                new AclSetuserArgs()
                    .reset()
                    .on()
                    .addPassword(password)
                    .keyPattern("session:game:*")
                    .keyPattern("tick:*")
                    .keyPattern("tick-executor-lease:*")
                    .addCommand(CommandType.HELLO)
                    .addCommand(CommandType.PING)
                    .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
                    .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
                    .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
                    .addCommand(CommandType.EVALSHA)
                    .addCommand(CommandType.TYPE)
                    .addCommand(CommandType.PTTL)
                    .addCommand(CommandType.GET)
                    .addCommand(CommandType.SET)
                    .addCommand(CommandType.SADD)
                    .addCommand(CommandType.SISMEMBER)
                    .addCommand(CommandType.HGET)
                    .addCommand(CommandType.HGETALL)
                    .addCommand(CommandType.HSET)
                    .addCommand(CommandType.HLEN)
                    .addCommand(CommandType.HDEL)
                    .addCommand(CommandType.PEXPIRE)
                    .addCommand(CommandType.EXISTS)
                    .addCommand(CommandType.DEL)
                    .addCommand(CommandType.TIME));
      }
      redis =
          RedisClient.create(
              RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(6379))
                  .withAuthentication("gamesession_coord_app", password)
                  .withTimeout(Duration.ofSeconds(5))
                  .build());
      redis.setOptions(ClientOptions.builder().autoReconnect(false).build());
      var dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName(POSTGRES.getDriverClassName());
      String url = POSTGRES.getJdbcUrl();
      dataSource.setUrl(url + (url.contains("?") ? "&" : "?") + "currentSchema=public");
      dataSource.setUsername(POSTGRES.getUsername());
      dataSource.setPassword(POSTGRES.getPassword());
      var candidate =
          new Candidate(
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              "SHARED",
              UUID.randomUUID(),
              1L,
              UUID.randomUUID(),
              UUID.randomUUID(),
              BigInteger.ONE,
              GameSessionAccountDelegationProfile.ISSUER,
              BigInteger.ONE,
              BigInteger.ONE,
              BigInteger.ONE,
              BigInteger.valueOf(64));
      fixture = new CanonicalGameplayAdmissionAbortCompositionFixture(dataSource, candidate, redis);
      sql =
          DSL.using(
              fixture
                  .dsl()
                  .configuration()
                  .deriveAppending(
                      listener,
                      new DefaultExecuteListener() {
                        @Override
                        public void resultEnd(ExecuteContext context) {
                          String query = context.sql();
                          var result = context.result();
                          if (query != null
                              && query.contains("JOIN game_session_region_runtime_status r")
                              && result != null) {
                            assertThat(result.fields())
                                .extracting(org.jooq.Field::getName)
                                .as("current transfer observation has unambiguous field names")
                                .doesNotHaveDuplicates();
                          }
                        }
                      }));
      tags = new CanonicalGameplayTenantGameplayTagRepository(sql, "test", 1);
      scripts =
          RedisScriptCatalog.fromContributions(
              List.of(
                  new net.firedevops.firemud.gamesession.service
                      .CanonicalGameplayOriginalNewCandidateRedisScriptContribution(),
                  new net.firedevops.firemud.gamesession.service
                      .CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution(),
                  new net.firedevops.firemud.gamesession.service
                      .CanonicalGameplayAccountMemberRepairRedisScriptContribution(),
                  new net.firedevops.firemud.gamesession.service
                      .CanonicalGameplayIssuerAdmissionBarrierRedisScriptContribution()));
      upstream =
          new CanonicalGameplayOriginalNewFinalAdmissionTestFixture.ControlledRegionSources(
              sql, fixture.originalPreparedDecisionWithControlledAccountLease(), directory);
      var decision = fixture.originalPreparedDecisionWithControlledAccountLease();
      var inventory = new CanonicalGameplayBindingInventoryRepository(sql);
      CanonicalGameplayOriginalNewCandidateRedisOwnerFactory.create(redis, scripts, inventory, tags)
          .installOnce(decision);
      fixture.acknowledgeOriginalIssuer();
      new CanonicalGameplayAccountMemberRepairOwner(sql, redis, scripts)
          .repair(fixture.transitionId());
      region =
          CanonicalGameplayRegionOwnerCompositionFixture.installExistingOriginalCandidate(
              sql,
              redis,
              fixture.transitionId(),
              upstream.request,
              upstream.resolver,
              upstream.entity);
      admission = inventory.readOriginalNewFinalAdmissionSource(decision);
      scope = region.installation().source;
      prior = new CanonicalGameplayRegionRuntimeStatusRepository(sql).find(scope).orElseThrow();
      execution = newControlledExecution();
      recreateTransferOwners();
    }

    private void replayBridge() {
      var replayed = region.owner().bridge(region.installation());
      assertThat(replayed.obligation()).isEqualTo(region.acknowledgement().obligation());
    }

    private OriginalNewCapacityExecution newControlledExecution() {
      var controlled =
          CanonicalGameplayOriginalNewFinalAdmissionTestFixture.controlledCapacity(
              admission, clock);
      return new OriginalNewCapacityExecution(
          controlled.request(),
          controlled.reservation(),
          controlled.transitionId(),
          controlled.candidateDigest(),
          prior.ownerInstanceId(),
          prior.executorFence().longValueExact());
    }

    private CanonicalGameplayRegionOwnerTransferRepository transfers(DSLContext context) {
      return new CanonicalGameplayRegionOwnerTransferRepository(context, clock, tags);
    }

    private CanonicalGameplayOriginalNewCapacityExecutionOwner capacity(DSLContext context) {
      return new CanonicalGameplayOriginalNewCapacityExecutionOwner(
          context, redis, scripts, clock, tags);
    }

    private void recreateTransferOwners() throws Exception {
      transfers = transfers(sql);
      receiver =
          new CanonicalGameplayControlledRegionTransferObservationFixture(clock)
              .receiver(transfers, tags);
      coordinator =
          new CanonicalGameplayRegionOwnerTransferCoordinator(
              sql, clock, transfers, capacity(sql), receiver);
    }

    private UUID begin() {
      return transfers.within(transferDeadline(), ignored -> transfers.begin(scope, prior));
    }

    private void seedUndeliveredDispatch(OriginalNewCapacityExecution original) {
      // Controlled crash-window storage seed only. No guard is disabled, no AUTHORIZED result is
      // invented, and this is not the real authorize path exercised by the two row-lock races.
      insertExecution(original, "INSTALL_DISPATCHED", null);
    }

    private void insertExecution(
        OriginalNewCapacityExecution original, String status, byte[] terminalReadback) {
      sql.execute(
          "INSERT INTO game_session_original_new_capacity_execution"
              + " (tenant_id,request_id,admission_id,admission_attempt_id,transition_id,capacity_rearm_fence,"
              + " reservation_id,reservation_fence,mutation_digest,execution_digest,canonical_execution,"
              + " selected_owner_id,selected_owner_fence,tenant_gameplay_tag,status,terminal_readback)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          original.request().identity().tenantId(),
          original.request().identity().requestId(),
          original.request().identity().admissionId(),
          original.request().admissionAttemptId(),
          original.transitionId(),
          original.request().capacityRearmFence(),
          original.reservation().reservationId(),
          original.reservation().reservationFence(),
          original.request().mutationDigest(),
          original.digest(),
          original.canonicalBytes(),
          original.selectedOwnerId(),
          original.selectedOwnerFence(),
          tags.assignmentForTransition(original.transitionId()).tenantGameplayTag(),
          status,
          terminalReadback);
    }

    private List<CanonicalGameplayRegionOwnerTransferRepository.Item> captureAndFence(UUID id) {
      var page = transfers.within(transferDeadline(), ignored -> transfers.capture(id));
      assertThat(page.size()).isLessThanOrEqualTo(32);
      for (var item : page) fence(item);
      return page;
    }

    private void fence(CanonicalGameplayRegionOwnerTransferRepository.Item item) {
      var before =
          receiver.observeForRegionOwnerTransfer(
              item.transferId(), item.ordinal(), transferDeadline());
      var actual = capacity(sql).fenceForTransfer(item, transferDeadline());
      var after =
          receiver.observeForRegionOwnerTransfer(
              item.transferId(), item.ordinal(), transferDeadline());
      transfers.within(
          transferDeadline(),
          ignored -> {
            transfers.retainFence(item, actual, before, after);
            return null;
          });
    }

    private void verifyPage(UUID id) {
      long cursor =
          transfers.within(transferDeadline(), ignored -> transfers.verificationCursor(id));
      var page = transfers.within(transferDeadline(), ignored -> transfers.page(id, cursor));
      assertThat(page.size()).isLessThanOrEqualTo(32);
      for (var item : page) {
        var before = receiver.observeForRegionOwnerTransfer(id, item.ordinal(), transferDeadline());
        var actual = capacity(sql).readTransferFence(item, transferDeadline());
        var after = receiver.observeForRegionOwnerTransfer(id, item.ordinal(), transferDeadline());
        transfers.within(
            transferDeadline(),
            ignored -> {
              transfers.retainVerification(item, actual, before, after);
              return null;
            });
      }
    }

    private CanonicalGameplayRegionRuntimeStatus install(UUID id) {
      return coordinator.install(
          id,
          scope,
          prior,
          "e".repeat(64),
          "game-session-service",
          "sql-proof-successor",
          transferDeadline());
    }

    private CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback issuerOpen() {
      var repository =
          new CanonicalGameplayIssuerAdmissionBarrierRepository(
              sql, tags, fixture.currentIssuerSource());
      return new CanonicalGameplayIssuerAdmissionBarrierRedisOwner(
              redis, scripts, repository, fixture.currentIssuerSource())
          .initializeAndRead(fixture.originalOperationId());
    }

    private CanonicalGameplayAccountInitialOpenProof.CurrentOpen controlledAccountOpen() {
      var binding = admission.candidate().bindingIdentity();
      var assignment = tags.assignmentForTransition(fixture.transitionId());
      UUID initializer = UUID.randomUUID();
      String incarnation = "sha256:" + "a".repeat(64);
      String key =
          "session:game:account-admission:{"
              + assignment.tenantGameplayTag()
              + "}:"
              + binding.accountId()
              + ":"
              + binding.playableStateNamespaceId();
      Map<String, String> fields =
          Map.of(
              "schema",
              CanonicalGameplayAccountInitialBarrierValue.SCHEMA,
              "state",
              "OPEN",
              "accountId",
              binding.accountId().toString(),
              "tenantId",
              binding.tenantId().toString(),
              "playableStateNamespaceId",
              binding.playableStateNamespaceId().toString(),
              "tenantGameplayTag",
              assignment.tenantGameplayTag(),
              "ownerOperationId",
              initializer.toString(),
              "ownerFenceEpoch",
              "1",
              "incarnationDigest",
              incarnation);
      try (var connection = redis.connect(ByteArrayCodec.INSTANCE)) {
        fields.forEach(
            (field, value) -> connection.sync().hset(ascii(key), ascii(field), ascii(value)));
      }
      long now = clock.millis();
      return new CanonicalGameplayAccountInitialOpenProof.CurrentOpen(
          initializer,
          UUID.randomUUID(),
          UUID.randomUUID(),
          1L,
          binding.accountId(),
          binding.tenantId(),
          binding.playableStateNamespaceId(),
          key,
          BigInteger.ONE,
          BigInteger.ONE,
          incarnation,
          incarnation,
          incarnation,
          now,
          now + 60_000L,
          fields);
    }

    private CanonicalGameplayRegionRuntimeStatus currentOwner() {
      return new CanonicalGameplayRegionRuntimeStatusRepository(sql).find(scope).orElseThrow();
    }

    private String executionState(OriginalNewCapacityExecution original) {
      Record row =
          sql.fetchOne(
              "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
              original.digest());
      return row == null ? null : row.get(0, String.class);
    }

    private boolean physicalExecutionExists(OriginalNewCapacityExecution original) {
      String tag = tags.assignmentForTransition(original.transitionId()).tenantGameplayTag();
      try (var connection = redis.connect(ByteArrayCodec.INSTANCE)) {
        return connection
                .sync()
                .exists(
                    ascii(
                        "session:game:capacity-execution:{"
                            + tag
                            + "}:"
                            + original.request().admissionAttemptId()))
            != 0;
      }
    }

    private void losePhysicalExecution(OriginalNewCapacityExecution original) {
      String tag = tags.assignmentForTransition(original.transitionId()).tenantGameplayTag();
      try (var connection = redis.connect(ByteArrayCodec.INSTANCE)) {
        assertThat(
                connection
                    .sync()
                    .del(
                        ascii(
                            "session:game:capacity-execution:{"
                                + tag
                                + "}:"
                                + original.request().admissionAttemptId())))
            .isEqualTo(1L);
      }
    }

    private void rawReplaceOwner(String owner, String correlation) {
      sql.execute(
          "UPDATE game_session_region_runtime_status SET owner_instance_id=?,executor_fence=nextval('game_session_region_executor_fence_seq'),lease_token_correlation=? WHERE tenant_id=? AND game_instance_id=? AND region_id=?",
          owner,
          correlation,
          scope.tenantId(),
          scope.gameInstanceId(),
          scope.regionId());
    }

    private String originalImage() {
      return sql.fetch(
              "SELECT to_jsonb(e)::text FROM game_session_original_new_capacity_execution e ORDER BY execution_digest")
          .getValues(0, String.class)
          .toString();
    }

    private String transferImage(UUID id) {
      return sql.fetchSingle(
              "SELECT to_jsonb(t)::text FROM game_session_region_owner_transfer t WHERE transfer_id=?",
              id)
          .get(0, String.class);
    }

    private String itemImage(UUID id) {
      return sql.fetch(
              "SELECT to_jsonb(i)::text FROM game_session_region_owner_transfer_item i WHERE transfer_id=? ORDER BY target_ordinal",
              id)
          .getValues(0, String.class)
          .toString();
    }

    @Override
    public void close() throws Exception {
      try {
        upstream.close();
      } finally {
        try {
          fixture.close();
        } finally {
          redis.shutdown(Duration.ZERO, Duration.ofSeconds(2));
        }
      }
    }
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private CanonicalGameplayRegionRuntimeStatus installAfterBarrier(
      CanonicalGameplayRegionRuntimeStatus prior,
      String correlation,
      String ownerInstanceId,
      CountDownLatch ready,
      CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
    try {
      return repository.installOwner(
          source,
          1L,
          prior,
          correlation,
          "game-session-service",
          ownerInstanceId,
          Instant.parse("2026-10-26T00:00:02Z"));
    } catch (IllegalStateException staleGrant) {
      return null;
    }
  }

  private long fenceSequenceValue() {
    Record row = dsl.fetchOne("SELECT last_value FROM game_session_region_executor_fence_seq");
    assertThat(row).isNotNull();
    Long value = row.get("last_value", Long.class);
    assertThat(value).isPositive();
    return value;
  }

  private static CanonicalGameplayRegionRuntimeStatusRepository.Source source(
      UUID gameInstanceId, UUID captureId) {
    var request =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            "firemud",
            TENANT_ID,
            "test-world",
            gameInstanceId,
            UUID.fromString("88888888-8888-4888-8888-888888888888"),
            "SHARED",
            true,
            "control-plane-request",
            UUID.fromString("99999999-9999-4999-8999-999999999999"),
            "sha256:" + "1".repeat(64),
            "sha256:" + "2".repeat(64),
            "sha256:" + "3".repeat(64));
    return new CanonicalGameplayRegionRuntimeStatusRepository.Source(
        TENANT_ID,
        gameInstanceId,
        CANONICAL_REGION_ID,
        OPERATIONAL_REGION_ID,
        4L,
        12L,
        request,
        captureId,
        GRAPH_DIGEST,
        PREPARATION_DIGEST);
  }

  private static CanonicalGameplayRegionRuntimeStatusRepository.Source source(
      CanonicalGameplayRegionRuntimeStatusRepository.Source original,
      WorldCanonicalInstanceLifecycleEvidence.Request request) {
    return new CanonicalGameplayRegionRuntimeStatusRepository.Source(
        original.tenantId(),
        original.gameInstanceId(),
        original.canonicalRegionId(),
        original.regionId(),
        original.lifecycleEpoch(),
        original.lifecycleRowVersion(),
        request,
        original.captureId(),
        original.graphSha256(),
        original.preparationInputDigest());
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest(
      WorldCanonicalInstanceLifecycleEvidence.Request original,
      UUID readRequestId,
      String controlPlaneRequestId) {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        original.schemaVersion(),
        readRequestId,
        original.targetNamespace(),
        original.canonicalTenantId(),
        original.worldSlug(),
        original.canonicalGameInstanceId(),
        original.playableStateNamespaceId(),
        original.playableStateScope(),
        original.publicProduction(),
        controlPlaneRequestId,
        original.canonicalVersionId(),
        original.expectedDescriptorRequestDigest(),
        original.expectedDescriptorResultDigest(),
        original.expectedReleaseAttestationDigest());
  }
}
