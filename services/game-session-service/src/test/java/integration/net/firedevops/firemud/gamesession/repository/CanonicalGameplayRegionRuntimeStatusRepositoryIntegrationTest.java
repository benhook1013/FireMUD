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
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayProtectedIncarnationReceiver.ObservationUnavailableException;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionLeaseRedisScriptContribution;
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
  private static final int TEST_CAPACITY_EVIDENCE_MAXIMUM_RECORDS = 128;
  private static final long TEST_CAPACITY_EVIDENCE_MAXIMUM_BINDING_BYTES = 128L * 1_048_576L;
  private static final int TEST_TRANSFER_HISTORY_MAXIMUM_TRANSFERS = 256;
  private static final int TEST_TRANSFER_HISTORY_MAXIMUM_ITEMS = 1024;

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
  void ownerInstallUsesFreshReadCommittedSnapshotAfterWaitingForAdvisoryLock() throws Exception {
    try (var proof = new TransferSqlProof();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var blocker =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      var dispatched = proof.newControlledExecution();
      String tag =
          CanonicalGameplayRegionRedisKeys.tenantRegionTag(
              proof.scope.tenantId(), proof.scope.gameInstanceId(), proof.scope.regionId());
      blocker.setAutoCommit(false);
      try (var lock =
          blocker.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
        lock.setString(1, "game-session-region-owner-install:v1:" + tag);
        lock.execute();
      }
      int blockerPid;
      try (var statement = blocker.createStatement();
          var result = statement.executeQuery("SELECT pg_backend_pid()")) {
        assertThat(result.next()).isTrue();
        blockerPid = result.getInt(1);
      }

      var repeatableReadSource =
          new DriverManagerDataSource() {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
              java.sql.Connection connection = super.getConnection();
              connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
              return connection;
            }
          };
      repeatableReadSource.setDriverClassName(POSTGRES.getDriverClassName());
      String url = POSTGRES.getJdbcUrl();
      String schema = proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      repeatableReadSource.setUrl(
          url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
      repeatableReadSource.setUsername(POSTGRES.getUsername());
      repeatableReadSource.setPassword(POSTGRES.getPassword());
      var repeatableReadRepository =
          new CanonicalGameplayRegionRuntimeStatusRepository(
              DSL.using(repeatableReadSource, SQLDialect.POSTGRES));
      assertThat(repeatableReadRepository.find(proof.scope)).contains(proof.prior);
      Future<?> install =
          executor.submit(
              () ->
                  repeatableReadRepository.installOwner(
                      proof.scope,
                      1L,
                      proof.prior,
                      "e".repeat(64),
                      "game-session-service",
                      "repeatable-read-successor",
                      Instant.parse("2026-10-26T00:00:03Z")));

      try {
        assertDatabaseWaitsFor(proof.sql, blockerPid);
        proof.seedUndeliveredDispatch(dispatched);
      } finally {
        blocker.commit();
      }

      assertThatThrownBy(install::get)
          .rootCause()
          .isInstanceOfSatisfying(
              IllegalStateException.class,
              failure ->
                  assertThat(failure)
                      .hasMessage("Canonical Game Session region ownership is unavailable"));
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      assertThat(proof.executionState(dispatched)).isEqualTo("INSTALL_DISPATCHED");
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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"1,134217728", "128,1048576"})
  void chargedAbsentExecutionCanStillBeFencedWhenEitherBudgetDimensionIsFull(
      int maximumRecords, long maximumBindingBytes) throws Exception {
    try (var proof = withCapacityBudget(maximumRecords, maximumBindingBytes)) {
      proof.seedUndeliveredDispatch(proof.execution);
      assertThat(proof.physicalExecutionExists(proof.execution)).isFalse();
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_capacity_evidence_charge WHERE tenant_id=?",
                      proof.admission.candidate().bindingIdentity().tenantId())
                  .get(0, Long.class))
          .isEqualTo(1L);

      UUID transferId = proof.begin();
      assertThat(proof.captureAndFence(transferId)).hasSize(1);
      assertThat(proof.physicalExecutionExists(proof.execution)).isTrue();
      assertThat(proof.executionState(proof.execution)).isEqualTo("INSTALL_DISPATCHED");
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_capacity_evidence_charge WHERE tenant_id=?",
                      proof.admission.candidate().bindingIdentity().tenantId())
                  .get(0, Long.class))
          .isEqualTo(1L);
    }
  }

  @Test
  void retainedDenialReleasesTheLastSlotAndCannotRaceAStoredDispatch() throws Exception {
    try (var proof = new TransferSqlProof(1, 1_048_576L, 3)) {
      var original = proof.execution;
      var later = proof.newControlledExecution();
      assertThatThrownBy(() -> proof.precharge(later)).isInstanceOf(IllegalStateException.class);
      var denial = UUID.randomUUID();
      proof.settle(original, denial);
      proof.settle(original, denial);
      assertThatThrownBy(() -> proof.settle(original, UUID.randomUUID()))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> proof.insertExecution(original, "INSTALL_DISPATCHED", null))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("Exact retained capacity evidence charge required");

      proof.seedUndeliveredDispatch(later);
      assertThatThrownBy(() -> proof.settle(later, UUID.randomUUID()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("Execution custody prevents capacity evidence settlement");
      assertThat(proof.executionState(later)).isEqualTo("INSTALL_DISPATCHED");
      assertThat(proof.executionState(original)).isNull();
      assertThat(proof.physicalExecutionExists(original)).isFalse();
    }
  }

  @Test
  void retainedDenialHistoryRemainsBoundedIndependentlyOfActiveSlots() throws Exception {
    try (var proof = new TransferSqlProof(1, 1_048_576L, 2)) {
      proof.settle(proof.execution, UUID.randomUUID());
      var next = proof.newControlledExecution();
      proof.precharge(next);
      proof.settle(next, UUID.randomUUID());
      assertThatThrownBy(() -> proof.precharge(proof.newControlledExecution()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Original NEW capacity evidence budget is unavailable");
    }
  }

  @Test
  void postgresSerializesTheLastEvidenceChargeAcrossConcurrentAttempts() throws Exception {
    try (var proof = withCapacityBudget(2, 2_097_152L);
        var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var first = proof.newControlledExecution();
      var second = proof.newControlledExecution();
      var ready = new java.util.concurrent.CountDownLatch(2);
      var start = new java.util.concurrent.CountDownLatch(1);
      var assignment =
          proof.tags.assignmentForTransition(proof.admission.candidate().bindingDecisionId());
      var selected =
          new CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner(
              proof.execution.selectedOwnerId(), proof.execution.selectedOwnerFence());
      java.util.function.Function<
              OriginalNewCapacityExecution, java.util.concurrent.Future<Boolean>>
          compete =
              attempt ->
                  callers.submit(
                      () -> {
                        ready.countDown();
                        if (!start.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                          throw new IllegalStateException("Concurrent proof start unavailable");
                        }
                        try {
                          proof.sql.transaction(
                              configuration ->
                                  CanonicalGameplayCapacityEvidenceBudget.precharge(
                                      DSL.using(configuration),
                                      attempt.request(),
                                      attempt.candidateDigest(),
                                      selected,
                                      assignment.tenantGameplayTag()));
                          return true;
                        } catch (IllegalStateException refused) {
                          assertThat(refused)
                              .hasMessage("Original NEW capacity evidence budget is unavailable");
                          return false;
                        }
                      });
      var firstResult = compete.apply(first);
      var secondResult = compete.apply(second);
      assertThat(ready.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(
              List.of(
                  firstResult.get(5, java.util.concurrent.TimeUnit.SECONDS),
                  secondResult.get(5, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_capacity_evidence_charge WHERE tenant_id=?",
                      assignment.tenantId())
                  .get(0, Long.class))
          .isEqualTo(2L);
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
  void rawFrozenTransferInsertLocksExactCurrentRegionBeforeLateCapacityDispatch() throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), Clock.systemUTC());
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution);
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      var inserted = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var blockerPid = new AtomicInteger();
      String schema = proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      Future<UUID> freeze =
          executor.submit(
              () -> {
                try (var connection =
                    java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                  connection.setSchema(schema);
                  connection.setAutoCommit(false);
                  DSLContext rawSql = DSL.using(connection, SQLDialect.POSTGRES);
                  rawSql.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
                  UUID transferId = proof.insertRawFrozenTransfer(rawSql);
                  blockerPid.set(
                      rawSql.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                  inserted.countDown();
                  if (!release.await(4, TimeUnit.SECONDS))
                    throw new AssertionError("Raw frozen-insert gate was not released");
                  connection.commit();
                  return transferId;
                }
              });
      awaitGate(inserted, freeze, "Raw frozen insert did not reach its transaction gate");
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
        assertDatabaseWaitsFor(proof.sql, blockerPid.get());
        assertThat(refused.isDone()).isFalse();
      } finally {
        release.countDown();
      }
      UUID transferId = freeze.get(10, TimeUnit.SECONDS);
      assertThat(refused.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(proof.executionState(proof.execution)).isNull();
      assertThat(proof.physicalExecutionExists(proof.execution)).isFalse();
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT state FROM game_session_region_owner_transfer WHERE transfer_id=?",
                      transferId)
                  .get(0, String.class))
          .isEqualTo("FROZEN");
    }
  }

  @Test
  void rawFrozenTransferInsertWaitsForActualDispatchAndRetainsIt() throws Exception {
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
      String schema = proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      Future<UUID> freeze =
          executor.submit(
              () -> {
                try (var connection =
                    java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                  connection.setSchema(schema);
                  connection.setAutoCommit(false);
                  DSLContext rawSql = DSL.using(connection, SQLDialect.POSTGRES);
                  rawSql.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
                  UUID transferId = proof.insertRawFrozenTransfer(rawSql);
                  connection.commit();
                  return transferId;
                }
              });
      try {
        assertDatabaseWaitsFor(proof.sql, gate.backendPid());
        assertThat(freeze.isDone()).isFalse();
      } finally {
        gate.release();
      }
      authorization.get(10, TimeUnit.SECONDS);
      UUID transferId = freeze.get(10, TimeUnit.SECONDS);
      assertThat(proof.executionState(proof.execution)).isEqualTo("AUTHORIZED");
      var captured =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.capture(transferId));
      assertThat(captured)
          .extracting(item -> item.execution().digest())
          .containsExactly(proof.execution.digest());
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
    }
  }

  @Test
  void rawFrozenInsertAndCapacityDispatchRejectRepeatableRead() throws Exception {
    try (var proof = new TransferSqlProof()) {
      assertThatThrownBy(
              () ->
                  proof.sql.transaction(
                      configuration -> {
                        DSLContext tx = DSL.using(configuration);
                        tx.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                        proof.insertRawFrozenTransfer(tx);
                      }))
          .rootCause()
          .isInstanceOfSatisfying(
              org.postgresql.util.PSQLException.class,
              failure -> {
                assertThat(failure.getSQLState()).isEqualTo("23514");
                assertThat(failure.getMessage())
                    .contains("Region transfer freeze requires READ COMMITTED owner fencing");
              });
      assertThatThrownBy(
              () ->
                  proof.sql.transaction(
                      configuration -> {
                        DSLContext tx = DSL.using(configuration);
                        tx.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                        proof.insertExecution(tx, proof.execution, "INSTALL_DISPATCHED", null);
                      }))
          .rootCause()
          .isInstanceOfSatisfying(
              org.postgresql.util.PSQLException.class,
              failure -> {
                assertThat(failure.getSQLState()).isEqualTo("23514");
                assertThat(failure.getMessage())
                    .contains("Region dispatch requires READ COMMITTED owner fencing");
              });
      assertThat(proof.executionState(proof.execution)).isNull();
      assertThat(
              proof
                  .sql
                  .fetchSingle("SELECT count(*) FROM game_session_region_owner_transfer")
                  .get(0, Long.class))
          .isZero();
    }
  }

  @Test
  void rawOwnerReplacementRejectsSnapshotThatPredatesCommittedDispatch() throws Exception {
    try (var proof = new TransferSqlProof()) {
      String schema = proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      try (var connection =
          java.sql.DriverManager.getConnection(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
        connection.setSchema(schema);
        connection.setAutoCommit(false);
        DSLContext tx = DSL.using(connection, SQLDialect.POSTGRES);
        tx.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
        assertThat(
                tx.fetchSingle("SELECT count(*) FROM game_session_original_new_capacity_execution")
                    .get(0, Long.class))
            .isZero();

        proof.seedUndeliveredDispatch(proof.execution);
        assertThat(proof.executionState(proof.execution)).isEqualTo("INSTALL_DISPATCHED");
        assertThatThrownBy(
                () -> proof.rawReplaceOwner(tx, "stale-snapshot-successor", "e".repeat(64)))
            .rootCause()
            .isInstanceOfSatisfying(
                org.postgresql.util.PSQLException.class,
                failure -> {
                  assertThat(failure.getSQLState()).isEqualTo("23514");
                  assertThat(failure.getMessage())
                      .contains("Region owner replacement requires READ COMMITTED owner fencing");
                });
        connection.rollback();
      }
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      assertThat(proof.executionState(proof.execution)).isEqualTo("INSTALL_DISPATCHED");
    }
  }

  @Test
  void rawTransferChildInsertSerializesWithCompletionAcrossCurrentAndOlderSnapshots()
      throws Exception {
    try (var proof = new TransferSqlProof();
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID transferId = proof.begin();
      var inserted = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var blockerPid = new AtomicInteger();
      String schema = proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      Future<?> itemInsert =
          executor.submit(
              () -> {
                try (var connection =
                    java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                  connection.setSchema(schema);
                  connection.setAutoCommit(false);
                  DSLContext rawSql = DSL.using(connection, SQLDialect.POSTGRES);
                  rawSql.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
                  proof.insertRawTransferItem(rawSql, transferId, 0);
                  blockerPid.set(
                      rawSql.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                  inserted.countDown();
                  if (!release.await(4, TimeUnit.SECONDS))
                    throw new AssertionError("Raw transfer-item gate was not released");
                  connection.commit();
                }
                return null;
              });
      awaitGate(
          inserted, itemInsert, "Raw transfer-item insert did not reach its transaction gate");
      Future<String> oldSnapshotCompletion =
          executor.submit(
              () ->
                  sqlStateOf(
                      () ->
                          proof.sql.transaction(
                              configuration -> {
                                DSLContext tx = DSL.using(configuration);
                                tx.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                                tx.fetchSingle(
                                    "SELECT state FROM game_session_region_owner_transfer WHERE transfer_id=?",
                                    transferId);
                                tx.execute(
                                    "UPDATE game_session_region_owner_transfer SET state='COMPLETED',"
                                        + "new_owner_id='sql-proof-successor',new_owner_fence=?,new_lease_correlation=?"
                                        + " WHERE transfer_id=?",
                                    proof.prior.executorFence().longValueExact() + 1,
                                    "e".repeat(64),
                                    transferId);
                              })));
      try {
        assertDatabaseWaitsFor(proof.sql, blockerPid.get());
        assertThat(oldSnapshotCompletion.isDone()).isFalse();
      } finally {
        release.countDown();
      }
      itemInsert.get(10, TimeUnit.SECONDS);
      assertThat(oldSnapshotCompletion.get(10, TimeUnit.SECONDS)).isEqualTo("40001");
      assertSqlGuard(
          () ->
              proof.sql.execute(
                  "UPDATE game_session_region_owner_transfer SET state='COMPLETED',"
                      + "new_owner_id='sql-proof-successor',new_owner_fence=?,new_lease_correlation=?"
                      + " WHERE transfer_id=?",
                  proof.prior.executorFence().longValueExact() + 1,
                  "e".repeat(64),
                  transferId));
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT state FROM game_session_region_owner_transfer WHERE transfer_id=?",
                      transferId)
                  .get(0, String.class))
          .isEqualTo("FROZEN");
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_region_owner_transfer_item WHERE transfer_id=?",
                      transferId)
                  .get(0, Long.class))
          .isEqualTo(1L);
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
      var captured =
          proof.transfers.within(transferDeadline(), ignored -> proof.transfers.capture(id));
      assertThat(captured).hasSize(1);
      var original =
          proof.transfers.within(
              transferDeadline(),
              ignored -> proof.transfers.frozenOriginal(proof.execution.transitionId()));
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
  void postgresSerializesTheLastTransferParentAcrossRegionsOfTheSameTenant() throws Exception {
    try (var proof = withTransferHistoryBudget(1, 2);
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      proof.seedUndeliveredDispatch(proof.execution);
      var otherScope =
          new CanonicalGameplayRegionRuntimeStatusRepository.Source(
              proof.scope.tenantId(),
              proof.scope.gameInstanceId(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              proof.scope.lifecycleEpoch(),
              proof.scope.lifecycleRowVersion(),
              proof.scope.lifecycleRequest(),
              proof.scope.captureId(),
              proof.scope.graphSha256(),
              proof.scope.preparationInputDigest());
      var status = new CanonicalGameplayRegionRuntimeStatusRepository(proof.sql);
      var otherPrior =
          status.installOwner(
              otherScope,
              1L,
              null,
              "f".repeat(64),
              "game-session-service",
              "sql-proof-other-region",
              proof.clock.instant());
      var inserted = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var blockerPid = new AtomicInteger();
      String schema = proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      Future<UUID> first =
          executor.submit(
              () -> {
                try (var connection =
                    java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                  connection.setSchema(schema);
                  connection.setAutoCommit(false);
                  DSLContext rawSql = DSL.using(connection, SQLDialect.POSTGRES);
                  rawSql.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
                  UUID transferId = proof.insertRawFrozenTransfer(rawSql);
                  blockerPid.set(
                      rawSql.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                  inserted.countDown();
                  if (!release.await(4, TimeUnit.SECONDS))
                    throw new AssertionError("Transfer history reservation gate was not released");
                  connection.commit();
                  return transferId;
                }
              });
      awaitGate(inserted, first, "First transfer did not reserve the tenant history slot");
      var second =
          executor.submit(
              () ->
                  assertTransferHistoryGuard(
                      () -> proof.insertRawFrozenTransfer(proof.sql, otherScope, otherPrior),
                      "Transfer history is exhausted"));
      try {
        assertDatabaseWaitsFor(proof.sql, blockerPid.get());
        assertThat(second.isDone()).isFalse();
      } finally {
        release.countDown();
      }
      UUID winner = first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
      var retained =
          proof.sql.fetchSingle(
              "SELECT transfer_id,region_id,state,reserved_execution_items"
                  + " FROM game_session_region_owner_transfer WHERE tenant_id=?",
              proof.scope.tenantId());
      assertThat(retained.get("transfer_id", UUID.class)).isEqualTo(winner);
      assertThat(retained.get("region_id", UUID.class)).isEqualTo(proof.scope.regionId());
      assertThat(retained.get("state", String.class)).isEqualTo("FROZEN");
      assertThat(retained.get("reserved_execution_items", Integer.class)).isEqualTo(1);
      assertThat(proof.itemImage(winner)).isEqualTo("[]");
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      assertThat(status.find(otherScope)).contains(otherPrior);
    }
  }

  @Test
  void fullTransferItemBudgetAllowsExistingIntentToCompleteButRefusesTheNextFreeze()
      throws Exception {
    try (var proof = withTransferHistoryBudget(2, 1)) {
      proof.seedUndeliveredDispatch(proof.execution);
      String originalExecution = proof.originalImage();
      UUID id = proof.begin();
      assertThat(id).isNotNull();
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT reserved_execution_items FROM game_session_region_owner_transfer WHERE transfer_id=?",
                      id)
                  .get(0, Integer.class))
          .isEqualTo(1);
      assertThat(proof.itemImage(id)).isEqualTo("[]");
      assertTransferHistoryGuard(
          () -> proof.insertRawTransferItem(proof.sql, id, 1),
          "Transfer item is outside its reserved inventory");
      assertThat(proof.itemImage(id)).isEqualTo("[]");
      String frozen = proof.transferImage(id);
      proof.recreateTransferOwners();
      assertThat(proof.begin()).isEqualTo(id);
      assertThat(proof.transferImage(id)).isEqualTo(frozen);
      assertThat(proof.captureAndFence(id))
          .extracting(item -> item.execution().digest())
          .containsExactly(proof.execution.digest());
      proof.verifyPage(id);
      boolean verified =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      assertThat(verified).isTrue();
      var installed = proof.install(id);
      String completed = proof.transferImage(id);
      String items = proof.itemImage(id);
      proof.recreateTransferOwners();
      assertThat(
              proof.coordinator.recoverCompleted(
                  id,
                  installed.ownerInstanceId(),
                  installed.leaseTokenCorrelation(),
                  transferDeadline()))
          .isEqualTo(installed);

      assertTransferHistoryGuard(
          () -> proof.coordinator.advance(proof.scope, installed, transferDeadline()),
          "Transfer history cannot reserve its full execution inventory");
      assertThat(
              proof
                  .sql
                  .fetch("SELECT transfer_id FROM game_session_region_owner_transfer")
                  .getValues(0, UUID.class))
          .containsExactly(id);
      assertThat(proof.transferImage(id)).isEqualTo(completed);
      assertThat(proof.itemImage(id)).isEqualTo(items);
      assertThat(proof.originalImage()).isEqualTo(originalExecution);
      assertThat(proof.currentOwner()).isEqualTo(installed);
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
  void terminalOnlyTransferRecoversExactCompletedSuccessorAfterOriginalFinalReceipt()
      throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), proof.clock);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      proof
          .capacity(proof.sql)
          .authorize(
              proof.admission, template, account, issuer, proof.execution, transferDeadline());
      try (var readback =
          new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                  proof.redis, proof.scripts, proof.clock, proof.tags)
              .finalizeOriginalNewOnce(
                  proof.admission,
                  template,
                  account,
                  issuer,
                  proof.execution,
                  transferDeadline())) {
        UUID id = proof.begin();
        assertThatThrownBy(
                () ->
                    proof.coordinator.recoverCompleted(
                        id, "sql-proof-successor", "e".repeat(64), transferDeadline()))
            .isInstanceOf(IllegalStateException.class);

        new CanonicalGameplayBindingInventoryRepository(proof.sql)
            .commitOriginalNewFinalAdmission(readback);
        assertThat(proof.captureAndFence(id)).isEmpty();
        proof.verifyPage(id);
        boolean verified =
            proof.transfers.within(
                transferDeadline(), ignored -> proof.transfers.finishVerification(id));
        assertThat(verified).isTrue();
        var installed = proof.install(id);
        String completion = proof.transferImage(id);
        String retained = proof.originalImage();
        assertThat(proof.executionState(proof.execution)).isEqualTo("COMMITTED");
        assertThat(
                proof.coordinator.recoverCompleted(
                    id,
                    installed.ownerInstanceId(),
                    installed.leaseTokenCorrelation(),
                    transferDeadline()))
            .isEqualTo(installed);
        assertThatThrownBy(
                () ->
                    proof.coordinator.recoverCompleted(
                        id, installed.ownerInstanceId(), "f".repeat(64), transferDeadline()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(proof.currentOwner()).isEqualTo(installed);
        assertThat(proof.transferImage(id)).isEqualTo(completion);
        assertThat(proof.originalImage()).isEqualTo(retained);
      }
    }
  }

  @Test
  void installationResumesFrozenTransferWhenTheOriginalAbortedBeforeCapture() throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.begin();
      assertThat(id).isNotNull();
      assertThat(proof.itemImage(id)).isEqualTo("[]");
      assertThat(proof.physicalExecutionExists(proof.execution)).isFalse();
      String tag =
          proof.tags.assignmentForTransition(proof.execution.transitionId()).tenantGameplayTag();
      assertThat(proof.fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
      String originalExecution = proof.originalImage();
      var original = proof.region.installation();
      try (var connection = proof.redis.connect(ByteArrayCodec.INSTANCE)) {
        assertThat(connection.sync().pexpire(ascii(original.grant.key()), 0)).isTrue();
      }
      proof.recreateTransferOwners();

      var installed =
          CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
              proof.sql,
              proof.redis,
              proof.coordinationUri,
              RedisScriptCatalog.fromContributions(
                  List.of(new CanonicalGameplayRegionLeaseRedisScriptContribution())),
              original,
              proof.upstream.resolver,
              proof.coordinator,
              transferDeadline());

      assertThat(installed.status.executorFence()).isGreaterThan(proof.prior.executorFence());
      assertThat(installed.status).isEqualTo(proof.currentOwner());
      assertThat(installed.evidence).isNull();
      assertThat(proof.transfers.readCurrent(id, 0).controlState()).isEqualTo("COMPLETED");
      assertThat(proof.itemImage(id)).contains(proof.execution.digest());
      assertThat(proof.physicalExecutionExists(proof.execution, tag)).isTrue();
      assertThat(proof.originalImage()).isEqualTo(originalExecution);
    }
  }

  @Test
  void installationRefusesSignedEvidenceThatExpiresWhileAcquiringTheRegionLock() throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.newControlledExecution());
      var wall = new AtomicLong(proof.clock.millis());
      Clock clock = org.mockito.Mockito.mock(Clock.class);
      org.mockito.Mockito.when(clock.millis()).thenAnswer(ignored -> wall.get());
      org.mockito.Mockito.when(clock.instant())
          .thenAnswer(ignored -> Instant.ofEpochMilli(wall.get()));
      var expireAfterLock = new AtomicBoolean();
      var listener =
          new DefaultExecuteListener() {
            @Override
            public void executeEnd(ExecuteContext context) {
              String sql = context.sql();
              if (sql != null
                  && sql.contains("pg_advisory_xact_lock")
                  && expireAfterLock.compareAndSet(true, false)) wall.addAndGet(1_100);
            }
          };
      DSLContext installingSql = DSL.using(proof.sql.configuration().deriveAppending(listener));
      var transfers =
          new CanonicalGameplayRegionOwnerTransferRepository(installingSql, clock, proof.tags);
      var observations =
          new CanonicalGameplayControlledRegionTransferObservationFixture(
                  clock, Duration.ofSeconds(1))
              .receiver(transfers, proof.tags);
      var coordinator =
          new CanonicalGameplayRegionOwnerTransferCoordinator(
              installingSql, clock, transfers, proof.capacity(installingSql), observations);
      UUID id = coordinator.advance(proof.scope, proof.prior, wall.get() + 5_000);
      assertThat(id).isNotNull();
      String before = proof.transferImage(id);
      expireAfterLock.set(true);
      long deadline = wall.get() + 5_000;
      assertThatThrownBy(
              () ->
                  coordinator.install(
                      id,
                      proof.scope,
                      proof.prior,
                      "e".repeat(64),
                      "game-session-service",
                      "sql-proof-successor",
                      deadline))
          .isInstanceOf(IllegalStateException.class);
      assertThat(expireAfterLock).isFalse();
      assertThat(wall.get()).isLessThan(deadline);
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      assertThat(proof.transferImage(id)).isEqualTo(before);
    }
  }

  @Test
  void capturedOriginalThatBecomesTerminalStillRequiresQualifiedTransferCompletion()
      throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), proof.clock);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      proof
          .capacity(proof.sql)
          .authorize(
              proof.admission, template, account, issuer, proof.execution, transferDeadline());
      try (var readback =
          new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                  proof.redis, proof.scripts, proof.clock, proof.tags)
              .finalizeOriginalNewOnce(
                  proof.admission,
                  template,
                  account,
                  issuer,
                  proof.execution,
                  transferDeadline())) {
        UUID id = proof.begin();
        var captured =
            proof.transfers.within(transferDeadline(), ignored -> proof.transfers.capture(id));
        assertThat(captured)
            .extracting(item -> item.execution().digest())
            .containsExactly(proof.execution.digest());
        new CanonicalGameplayBindingInventoryRepository(proof.sql)
            .commitOriginalNewFinalAdmission(readback);
        assertThat(proof.executionState(proof.execution)).isEqualTo("COMMITTED");
        String frozen = proof.transferImage(id);
        String inventory = proof.itemImage(id);
        assertThatThrownBy(
                () -> proof.rawCompleteAndInstall(id, "sql-proof-successor", "e".repeat(64), true))
            .rootCause()
            .isInstanceOfSatisfying(
                org.postgresql.util.PSQLException.class,
                failure -> {
                  assertThat(failure.getSQLState()).isEqualTo("23514");
                  assertThat(failure.getMessage())
                      .contains(
                          "Captured region transfer item lacks its full exact current receipt");
                });
        assertThat(proof.currentOwner()).isEqualTo(proof.prior);
        assertThat(proof.transferImage(id)).isEqualTo(frozen);
        assertThat(proof.itemImage(id)).isEqualTo(inventory);
        var status = new CanonicalGameplayRegionRuntimeStatusRepository(proof.sql);

        assertThatThrownBy(
                () ->
                    status.installOwner(
                        proof.scope,
                        proof.prior.regionEpoch(),
                        proof.prior,
                        "e".repeat(64),
                        "game-session-service",
                        "sql-proof-successor",
                        proof.clock.instant()))
            .isInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("Normal region owner transfer is held");
        assertThat(proof.currentOwner()).isEqualTo(proof.prior);
        assertThat(proof.transferImage(id)).isEqualTo(frozen);
        assertThat(proof.itemImage(id)).isEqualTo(inventory);

        proof.fence(captured.getFirst());
        proof.verifyPage(id);
        boolean verified =
            proof.transfers.within(
                transferDeadline(), ignored -> proof.transfers.finishVerification(id));
        assertThat(verified).isTrue();
        assertThat(
                proof
                    .rawCompleteAndInstall(id, "sql-proof-successor", "e".repeat(64))
                    .ownerInstanceId())
            .isEqualTo("sql-proof-successor");
        assertThat(proof.executionState(proof.execution)).isEqualTo("COMMITTED");
        assertThat(proof.transfers.readCurrent(id, captured.getFirst().ordinal()).controlState())
            .isEqualTo("COMPLETED");
      }
    }
  }

  @Test
  void standaloneTransferObservationCancelsBlockedSqlAtItsExplicitDeadline() throws Exception {
    try (var proof = new TransferSqlProof();
        var lock =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.begin();
      var captured =
          proof.transfers.within(transferDeadline(), ignored -> proof.transfers.capture(id));
      assertThat(captured).hasSize(1);
      lock.setSchema(proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class));
      lock.setAutoCommit(false);
      try (var statement = lock.createStatement()) {
        statement.execute("LOCK TABLE game_session_region_owner_transfer IN ACCESS EXCLUSIVE MODE");
      }
      try {
        long start = System.nanoTime();
        assertThatThrownBy(
                () ->
                    proof.receiver.observeForRegionOwnerTransfer(
                        id, captured.getFirst().ordinal(), proof.clock.millis() + 500L))
            .isInstanceOf(ObservationUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
      } finally {
        lock.rollback();
      }
      assertThat(
              proof
                  .receiver
                  .observeForRegionOwnerTransfer(
                      id, captured.getFirst().ordinal(), transferDeadline())
                  .regionOwnerTransferContext()
                  .transferId())
          .isEqualTo(id);
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
      var lateExecution = proof.newControlledExecution();
      proof.retainExactChargeForControlledSeed(lateExecution);
      assertThatThrownBy(() -> proof.seedUndeliveredDispatch(lateExecution))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("Region dispatch requires the exact current selected owner");
      assertThat(proof.currentOwner()).isEqualTo(installed);
      assertThat(proof.transferImage(id)).isEqualTo(completed);
    }
  }

  @Test
  void lostFenceDispatchReplyRecoversOnlyItsPhysicalWitnessAndCannotRecreateIt() throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.begin();
      var item =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.capture(id).getFirst());
      long deadline = transferDeadline();
      proof.transfers.within(
          deadline,
          budget -> {
            var before = proof.receiver.observeForRegionOwnerTransfer(id, item.ordinal(), deadline);
            var dispatch = proof.transfers.fenceDispatch(item, before);
            var competing = proof.transfers.fenceDispatch(item, before);
            assertThat(competing.takeSubmission(item)).isNull();
            var owner = proof.capacity(proof.sql).forTransferBudget(budget);
            owner.fenceForTransfer(item, dispatch, deadline);
            return null;
          });
      assertThat(proof.itemImage(id)).contains("\"fence_readback\": null");
      proof.recreateTransferOwners();
      proof.fence(item);
      proof.verifyPage(id);
      proof.transfers.within(transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      var installed = proof.install(id);
      String receipt = proof.itemImage(id);
      String completion = proof.transferImage(id);
      proof.losePhysicalExecution(item.execution());
      assertThatThrownBy(() -> proof.fence(item)).isInstanceOf(IllegalStateException.class);
      assertThat(proof.physicalExecutionExists(item.execution())).isFalse();
      assertThat(proof.currentOwner()).isEqualTo(installed);
      assertThat(proof.transferImage(id)).isEqualTo(completion);
      assertThat(proof.itemImage(id)).isEqualTo(receipt);
    }
  }

  @Test
  void interruptedFenceRetentionRecoversExactTerminalProofWithoutDispatchingAgain()
      throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), proof.clock);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      proof
          .capacity(proof.sql)
          .authorize(
              proof.admission, template, account, issuer, proof.execution, transferDeadline());
      try (var physicalCommit =
          new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                  proof.redis, proof.scripts, proof.clock, proof.tags)
              .finalizeOriginalNewOnce(
                  proof.admission,
                  template,
                  account,
                  issuer,
                  proof.execution,
                  transferDeadline())) {
        UUID id = proof.begin();
        long deadline = transferDeadline();
        var item =
            proof.transfers.within(deadline, ignored -> proof.transfers.capture(id).getFirst());
        proof.transfers.within(
            deadline,
            budget -> {
              var before =
                  proof.receiver.observeForRegionOwnerTransfer(id, item.ordinal(), deadline);
              var dispatch = proof.transfers.fenceDispatch(item, before);
              proof
                  .capacity(proof.sql)
                  .forTransferBudget(budget)
                  .fenceForTransfer(item, dispatch, deadline);
              return null;
            });
        assertThat(proof.executionState(proof.execution)).isEqualTo("COMMITTED");
        assertThat(proof.itemImage(id)).contains("\"fence_readback\": null");
        UUID retainedDispatch =
            proof
                .sql
                .fetchSingle(
                    "SELECT fence_dispatch_id FROM game_session_region_owner_transfer_item WHERE transfer_id=?",
                    id)
                .get("fence_dispatch_id", UUID.class);
        assertThat(retainedDispatch).isNotNull();
        String original = proof.originalImage();
        proof.recreateTransferOwners();
        proof.fence(item);
        assertThat(proof.itemImage(id))
            .contains(retainedDispatch.toString())
            .doesNotContain("\"fence_readback\": null");
        assertThat(proof.originalImage()).isEqualTo(original);
        proof.verifyPage(id);
        boolean verified =
            proof.transfers.within(
                transferDeadline(), ignored -> proof.transfers.finishVerification(id));
        assertThat(verified).isTrue();
        assertThat(proof.install(id).executorFence()).isGreaterThan(proof.prior.executorFence());
      }
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
    throw new AssertionError("The competing transaction did not wait on the held PostgreSQL row");
  }

  private static void awaitGate(CountDownLatch gate, Future<?> worker, String message)
      throws Exception {
    if (gate.await(3, TimeUnit.SECONDS)) return;
    if (worker.isDone()) worker.get();
    throw new AssertionError(message);
  }

  private static void assertSqlGuard(Runnable action) {
    assertThatThrownBy(action::run)
        .rootCause()
        .isInstanceOfSatisfying(
            org.postgresql.util.PSQLException.class,
            failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
  }

  private static void assertTransferHistoryGuard(Runnable action, String message) {
    assertThatThrownBy(action::run)
        .rootCause()
        .isInstanceOfSatisfying(
            org.postgresql.util.PSQLException.class,
            failure -> {
              assertThat(failure.getSQLState()).isEqualTo("23514");
              assertThat(failure.getMessage()).contains(message);
            });
  }

  private static String sqlStateOf(Runnable action) {
    try {
      action.run();
      return "COMMITTED";
    } catch (RuntimeException failure) {
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof org.postgresql.util.PSQLException postgresFailure)
          return postgresFailure.getSQLState();
      }
      throw failure;
    }
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

  private TransferSqlProof withCapacityBudget(int maximumRecords, long maximumBindingBytes)
      throws Exception {
    return new TransferSqlProof(maximumRecords, maximumBindingBytes, maximumRecords);
  }

  private TransferSqlProof withTransferHistoryBudget(int maximumTransfers, int maximumItems)
      throws Exception {
    return new TransferSqlProof(
        new DefaultExecuteListener(),
        TEST_CAPACITY_EVIDENCE_MAXIMUM_RECORDS,
        TEST_CAPACITY_EVIDENCE_MAXIMUM_BINDING_BYTES,
        TEST_CAPACITY_EVIDENCE_MAXIMUM_RECORDS,
        maximumTransfers,
        maximumItems);
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
    private final RedisURI coordinationUri;
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
      this(
          new DefaultExecuteListener(),
          TEST_CAPACITY_EVIDENCE_MAXIMUM_RECORDS,
          TEST_CAPACITY_EVIDENCE_MAXIMUM_BINDING_BYTES);
    }

    private TransferSqlProof(
        int maximumRecords, long maximumBindingBytes, int maximumRetainedRecords) throws Exception {
      this(
          new DefaultExecuteListener(),
          maximumRecords,
          maximumBindingBytes,
          maximumRetainedRecords);
    }

    private TransferSqlProof(DefaultExecuteListener listener) throws Exception {
      this(
          listener,
          TEST_CAPACITY_EVIDENCE_MAXIMUM_RECORDS,
          TEST_CAPACITY_EVIDENCE_MAXIMUM_BINDING_BYTES);
    }

    private TransferSqlProof(
        DefaultExecuteListener listener, int maximumRecords, long maximumBindingBytes)
        throws Exception {
      this(listener, maximumRecords, maximumBindingBytes, maximumRecords);
    }

    private TransferSqlProof(
        DefaultExecuteListener listener,
        int maximumRecords,
        long maximumBindingBytes,
        int maximumRetainedRecords)
        throws Exception {
      this(
          listener,
          maximumRecords,
          maximumBindingBytes,
          maximumRetainedRecords,
          TEST_TRANSFER_HISTORY_MAXIMUM_TRANSFERS,
          TEST_TRANSFER_HISTORY_MAXIMUM_ITEMS);
    }

    private TransferSqlProof(
        DefaultExecuteListener listener,
        int maximumRecords,
        long maximumBindingBytes,
        int maximumRetainedRecords,
        int maximumTransfers,
        int maximumItems)
        throws Exception {
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
      coordinationUri =
          RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(6379))
              .withAuthentication("gamesession_coord_app", password)
              .withTimeout(Duration.ofSeconds(5))
              .build();
      redis = RedisClient.create(coordinationUri);
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
              coordinationUri,
              fixture.transitionId(),
              upstream.request,
              upstream.resolver,
              upstream.entity);
      admission = inventory.readOriginalNewFinalAdmissionSource(decision);
      scope = region.installation().source;
      prior = new CanonicalGameplayRegionRuntimeStatusRepository(sql).find(scope).orElseThrow();
      var assignment = tags.assignmentForTransition(admission.candidate().bindingDecisionId());
      sql.execute(
          "INSERT INTO game_session_capacity_evidence_budget"
              + " (tenant_id,maximum_records,maximum_binding_bytes,maximum_retained_records) VALUES (?,?,?,?)"
              + " ON CONFLICT DO NOTHING",
          assignment.tenantId(),
          maximumRecords,
          maximumBindingBytes,
          maximumRetainedRecords);
      sql.execute(
          "INSERT INTO game_session_region_transfer_history_budget"
              + " (tenant_id,maximum_transfers,maximum_items) VALUES (?,?,?)",
          assignment.tenantId(),
          maximumTransfers,
          maximumItems);
      execution = newControlledExecution();
      precharge(execution);
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
      precharge(original);
      insertExecution(original, "INSTALL_DISPATCHED", null);
    }

    private void settle(OriginalNewCapacityExecution original, UUID denial) {
      var assignment = tags.assignmentForTransition(original.transitionId());
      sql.transaction(
          configuration ->
              CanonicalGameplayCapacityEvidenceBudget.settleDenied(
                  DSL.using(configuration),
                  original.request(),
                  original.candidateDigest(),
                  new CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner(
                      original.selectedOwnerId(), original.selectedOwnerFence()),
                  assignment.tenantGameplayTag(),
                  denial));
    }

    private void precharge(OriginalNewCapacityExecution original) {
      if (sql.fetchOne(
              "SELECT 1 FROM game_session_capacity_evidence_charge WHERE admission_attempt_id=?",
              original.request().admissionAttemptId())
          != null) return;
      capacity(sql)
          .precharge(
              admission,
              original.request(),
              new CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner(
                  original.selectedOwnerId(), original.selectedOwnerFence()),
              transferDeadline());
    }

    /**
     * Exact test charge for the post-transfer raw INSERT refusal case, after source is retained.
     */
    private void retainExactChargeForControlledSeed(OriginalNewCapacityExecution original) {
      if (!admission.candidateDigest().equals(original.candidateDigest())
          || !admission.candidate().bindingDecisionId().equals(original.transitionId()))
        throw new IllegalStateException("Exact candidate is required for a controlled charge");
      if (sql.fetchOne(
              "SELECT 1 FROM game_session_capacity_evidence_charge WHERE admission_attempt_id=?",
              original.request().admissionAttemptId())
          != null) return;
      var request = original.request();
      var identity = request.identity();
      sql.execute(
          "INSERT INTO game_session_capacity_evidence_charge"
              + " (admission_attempt_id,tenant_id,request_id,admission_id,capacity_rearm_fence,"
              + " mutation_digest,candidate_digest,selected_owner_id,selected_owner_fence,reserved_binding_bytes)"
              + " VALUES (?,?,?,?,?,?,?,?,?,1048576)",
          request.admissionAttemptId(),
          identity.tenantId(),
          identity.requestId(),
          identity.admissionId(),
          request.capacityRearmFence(),
          request.mutationDigest(),
          original.candidateDigest(),
          original.selectedOwnerId(),
          original.selectedOwnerFence());
    }

    private void insertExecution(
        OriginalNewCapacityExecution original, String status, byte[] terminalReadback) {
      insertExecution(sql, original, status, terminalReadback);
    }

    private void insertExecution(
        DSLContext context,
        OriginalNewCapacityExecution original,
        String status,
        byte[] terminalReadback) {
      context.execute(
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

    private UUID insertRawFrozenTransfer(DSLContext context) {
      return insertRawFrozenTransfer(context, scope, prior);
    }

    private UUID insertRawFrozenTransfer(
        DSLContext context,
        CanonicalGameplayRegionRuntimeStatusRepository.Source source,
        CanonicalGameplayRegionRuntimeStatus owner) {
      UUID transferId = UUID.randomUUID();
      context.execute(
          "INSERT INTO game_session_region_owner_transfer"
              + " (transfer_id,tenant_id,game_instance_id,region_id,region_epoch,old_owner_id,old_owner_fence,"
              + " old_lease_correlation,source_digest,intent_digest,state)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?,'FROZEN')",
          transferId,
          source.tenantId(),
          source.gameInstanceId(),
          source.regionId(),
          owner.regionEpoch(),
          owner.ownerInstanceId(),
          owner.executorFence().longValueExact(),
          owner.leaseTokenCorrelation(),
          CanonicalGameplayRegionOwnerTransferRepository.sourceDigest(source, owner),
          "sha256:" + "c".repeat(64));
      return transferId;
    }

    private void insertRawTransferItem(DSLContext context, UUID transferId, long ordinal) {
      var assignment = tags.assignmentForTransition(execution.transitionId());
      context.execute(
          "INSERT INTO game_session_region_owner_transfer_item"
              + " (transfer_id,target_ordinal,execution_digest,canonical_execution,candidate_digest,"
              + " tenant_gameplay_tag,workload_namespace,allocation_version) VALUES (?,?,?,?,?,?,?,?)",
          transferId,
          ordinal,
          execution.digest(),
          execution.canonicalBytes(),
          execution.candidateDigest(),
          assignment.tenantGameplayTag(),
          assignment.workloadNamespace(),
          assignment.allocationVersion());
    }

    private List<CanonicalGameplayRegionOwnerTransferRepository.Item> captureAndFence(UUID id) {
      var page = transfers.within(transferDeadline(), ignored -> transfers.capture(id));
      assertThat(page.size()).isLessThanOrEqualTo(32);
      for (var item : page) fence(item);
      return page;
    }

    private void fence(CanonicalGameplayRegionOwnerTransferRepository.Item item) {
      long deadline = transferDeadline();
      transfers.within(
          deadline,
          budget -> {
            var before =
                receiver.observeForRegionOwnerTransfer(item.transferId(), item.ordinal(), deadline);
            var dispatch = transfers.fenceDispatch(item, before);
            var actual =
                capacity(sql).forTransferBudget(budget).fenceForTransfer(item, dispatch, deadline);
            var after =
                receiver.observeForRegionOwnerTransfer(item.transferId(), item.ordinal(), deadline);
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
      return physicalExecutionExists(original, tag);
    }

    private boolean physicalExecutionExists(OriginalNewCapacityExecution original, String tag) {
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
      rawReplaceOwner(sql, owner, correlation);
    }

    private void rawReplaceOwner(DSLContext context, String owner, String correlation) {
      context.execute(
          "UPDATE game_session_region_runtime_status SET owner_instance_id=?,executor_fence=nextval('game_session_region_executor_fence_seq'),lease_token_correlation=? WHERE tenant_id=? AND game_instance_id=? AND region_id=?",
          owner,
          correlation,
          scope.tenantId(),
          scope.gameInstanceId(),
          scope.regionId());
    }

    private CanonicalGameplayRegionRuntimeStatus rawCompleteAndInstall(
        UUID transferId, String owner, String correlation) {
      return rawCompleteAndInstall(transferId, owner, correlation, false);
    }

    private CanonicalGameplayRegionRuntimeStatus rawCompleteAndInstall(
        UUID transferId, String owner, String correlation, boolean claimCompleteVerification) {
      String schema = sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      try (var connection =
          java.sql.DriverManager.getConnection(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
        connection.setSchema(schema);
        connection.setAutoCommit(false);
        DSLContext transaction = DSL.using(connection, SQLDialect.POSTGRES);
        transaction.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
        long fence =
            transaction
                .fetchSingle("SELECT nextval('game_session_region_executor_fence_seq') AS fence")
                .get("fence", Long.class);
        if (claimCompleteVerification) {
          transaction.execute(
              "UPDATE game_session_region_owner_transfer"
                  + " SET incarnation_digest=?,verification_round=?,verification_complete=true"
                  + " WHERE transfer_id=? AND state='FROZEN'",
              "sha256:" + "f".repeat(64),
              UUID.randomUUID(),
              transferId);
        }
        if (transaction.execute(
                "UPDATE game_session_region_owner_transfer"
                    + " SET state='COMPLETED',new_owner_id=?,new_owner_fence=?,new_lease_correlation=?"
                    + " WHERE transfer_id=? AND state='FROZEN'",
                owner,
                fence,
                correlation,
                transferId)
            != 1) throw new IllegalStateException("Expected one frozen transfer");
        Record installed =
            transaction.fetchOne(
                "UPDATE game_session_region_runtime_status"
                    + " SET executor_fence=?,lease_token_correlation=?,owner_service=?,owner_instance_id=?,"
                    + " updated_at=CURRENT_TIMESTAMP::timestamp"
                    + " WHERE tenant_id=? AND game_instance_id=? AND region_id=?"
                    + " AND tenant_region_tag=? AND canonical_region_id=? AND lifecycle_epoch=?"
                    + " AND lifecycle_row_version=? AND capture_id=? AND lifecycle_request_bytes=?"
                    + " AND graph_sha256=? AND preparation_input_digest=? AND region_epoch=?"
                    + " AND executor_fence=? AND lease_token_correlation=? AND owner_service=?"
                    + " AND owner_instance_id=? AND paused=? AND last_committed_tick_id=?"
                    + " AND last_committed_tick_batch_id IS NOT DISTINCT FROM ? RETURNING *",
                fence,
                correlation,
                "game-session-service",
                owner,
                prior.tenantId(),
                prior.gameInstanceId(),
                prior.regionId(),
                prior.tenantRegionTag(),
                prior.canonicalRegionId(),
                prior.lifecycleEpoch(),
                prior.lifecycleRowVersion(),
                prior.captureId(),
                prior.lifecycleRequest().canonicalBytes(),
                prior.graphSha256(),
                prior.preparationInputDigest(),
                prior.regionEpoch(),
                prior.executorFence().longValueExact(),
                prior.leaseTokenCorrelation(),
                prior.ownerService(),
                prior.ownerInstanceId(),
                prior.paused(),
                prior.lastCommittedTickId(),
                prior.lastCommittedTickBatchId());
        if (installed == null) throw new IllegalStateException("Expected exact prior region owner");
        var result = CanonicalGameplayRegionRuntimeStatusRepository.toStatus(installed);
        connection.commit();
        return result;
      } catch (java.sql.SQLException failure) {
        throw new IllegalStateException(failure);
      }
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
