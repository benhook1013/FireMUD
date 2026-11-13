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
class CanonicalGameplayRegionRuntimeStatusRepositoryIntegrationTest
    extends CanonicalGameplayRegionRuntimeStatusRepositoryIntegrationTestSupport {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @Override
  PostgreSQLContainer<?> postgresContainer() {
    return POSTGRES;
  }

  @Override
  GenericContainer<?> redisContainer() {
    return REDIS;
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
}

abstract class CanonicalGameplayRegionRuntimeStatusRepositoryIntegrationTestSupport {
  abstract PostgreSQLContainer<?> postgresContainer();

  abstract GenericContainer<?> redisContainer();

  static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  static final UUID OTHER_GAME_INSTANCE_ID =
      UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  static final UUID CANONICAL_REGION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  static final UUID OPERATIONAL_REGION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  static final UUID CAPTURE_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  static final String GRAPH_DIGEST = "a".repeat(64);
  static final String PREPARATION_DIGEST = "sha256:" + "b".repeat(64);
  static final String FIRST_LEASE_CORRELATION = "c".repeat(64);
  static final String SECOND_LEASE_CORRELATION = "d".repeat(64);
  static final int TEST_CAPACITY_EVIDENCE_MAXIMUM_RECORDS = 128;
  static final long TEST_CAPACITY_EVIDENCE_MAXIMUM_BINDING_BYTES = 128L * 1_048_576L;
  static final int TEST_TRANSFER_HISTORY_MAXIMUM_TRANSFERS = 256;
  static final int TEST_TRANSFER_HISTORY_MAXIMUM_ITEMS = 1024;

  @TempDir Path directory;

  DSLContext dsl;
  CanonicalGameplayRegionRuntimeStatusRepository repository;
  CanonicalGameplayRegionRuntimeStatusRepository.Source source;

  @BeforeAll
  void setUpRepository() {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(postgresContainer());

    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgresContainer().getDriverClassName());
    dataSource.setUrl(postgresContainer().getJdbcUrl());
    dataSource.setUsername(postgresContainer().getUsername());
    dataSource.setPassword(postgresContainer().getPassword());

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

  static UUID beginAfterBarrier(
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

  static void assertDatabaseWaitsFor(DSLContext sql, int blockerPid) throws Exception {
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

  static void awaitGate(CountDownLatch gate, Future<?> worker, String message) throws Exception {
    if (gate.await(3, TimeUnit.SECONDS)) return;
    if (worker.isDone()) worker.get();
    throw new AssertionError(message);
  }

  static void assertSqlGuard(Runnable action) {
    assertThatThrownBy(action::run)
        .rootCause()
        .isInstanceOfSatisfying(
            org.postgresql.util.PSQLException.class,
            failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
  }

  static void assertTransferHistoryGuard(Runnable action, String message) {
    assertThatThrownBy(action::run)
        .rootCause()
        .isInstanceOfSatisfying(
            org.postgresql.util.PSQLException.class,
            failure -> {
              assertThat(failure.getSQLState()).isEqualTo("23514");
              assertThat(failure.getMessage()).contains(message);
            });
  }

  static String sqlStateOf(Runnable action) {
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

  static long transferDeadline() {
    return System.currentTimeMillis() + 5_000;
  }

  /** Holds the actual owner's SELECT FOR UPDATE after PostgreSQL has granted its row lock. */
  static final class RegionRowLockGate extends DefaultExecuteListener implements AutoCloseable {
    final AtomicBoolean first = new AtomicBoolean(true);
    final AtomicInteger pid = new AtomicInteger();
    final CountDownLatch locked = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);

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
  static final class InventoryRowLockGate extends DefaultExecuteListener implements AutoCloseable {
    final AtomicBoolean armed = new AtomicBoolean();
    final AtomicInteger pid = new AtomicInteger();
    final CountDownLatch locked = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);

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

  TransferSqlProof withCapacityBudget(int maximumRecords, long maximumBindingBytes)
      throws Exception {
    return new TransferSqlProof(maximumRecords, maximumBindingBytes, maximumRecords);
  }

  TransferSqlProof withTransferHistoryBudget(int maximumTransfers, int maximumItems)
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
  final class TransferSqlProof implements AutoCloseable {
    final Clock clock = Clock.systemUTC();
    final RedisClient redis;
    final RedisURI coordinationUri;
    final CanonicalGameplayAdmissionAbortCompositionFixture fixture;
    final CanonicalGameplayOriginalNewFinalAdmissionTestFixture.ControlledRegionSources upstream;
    final DSLContext sql;
    final CanonicalGameplayTenantGameplayTagRepository tags;
    final RedisScriptCatalog scripts;
    final CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource admission;
    final CanonicalGameplayRegionRuntimeStatusRepository.Source scope;
    final CanonicalGameplayRegionRuntimeStatus prior;
    final OriginalNewCapacityExecution execution;
    final CanonicalGameplayRegionOwnerCompositionFixture.ExistingBinding region;
    CanonicalGameplayRegionOwnerTransferRepository transfers;
    CanonicalGameplayProtectedIncarnationReceiver receiver;
    CanonicalGameplayRegionOwnerTransferCoordinator coordinator;

    TransferSqlProof() throws Exception {
      this(
          new DefaultExecuteListener(),
          TEST_CAPACITY_EVIDENCE_MAXIMUM_RECORDS,
          TEST_CAPACITY_EVIDENCE_MAXIMUM_BINDING_BYTES);
    }

    TransferSqlProof(int maximumRecords, long maximumBindingBytes, int maximumRetainedRecords)
        throws Exception {
      this(
          new DefaultExecuteListener(),
          maximumRecords,
          maximumBindingBytes,
          maximumRetainedRecords);
    }

    TransferSqlProof(DefaultExecuteListener listener) throws Exception {
      this(
          listener,
          TEST_CAPACITY_EVIDENCE_MAXIMUM_RECORDS,
          TEST_CAPACITY_EVIDENCE_MAXIMUM_BINDING_BYTES);
    }

    TransferSqlProof(DefaultExecuteListener listener, int maximumRecords, long maximumBindingBytes)
        throws Exception {
      this(listener, maximumRecords, maximumBindingBytes, maximumRecords);
    }

    TransferSqlProof(
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

    TransferSqlProof(
        DefaultExecuteListener listener,
        int maximumRecords,
        long maximumBindingBytes,
        int maximumRetainedRecords,
        int maximumTransfers,
        int maximumItems)
        throws Exception {
      String password = UUID.randomUUID().toString();
      try (var administrator =
              RedisClient.create(
                  RedisURI.create(
                      redisContainer().getHost(), redisContainer().getMappedPort(6379)));
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
          RedisURI.Builder.redis(redisContainer().getHost(), redisContainer().getMappedPort(6379))
              .withAuthentication("gamesession_coord_app", password)
              .withTimeout(Duration.ofSeconds(5))
              .build();
      redis = RedisClient.create(coordinationUri);
      redis.setOptions(ClientOptions.builder().autoReconnect(false).build());
      var dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName(postgresContainer().getDriverClassName());
      String url = postgresContainer().getJdbcUrl();
      dataSource.setUrl(url + (url.contains("?") ? "&" : "?") + "currentSchema=public");
      dataSource.setUsername(postgresContainer().getUsername());
      dataSource.setPassword(postgresContainer().getPassword());
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
              + " (tenant_id,maximum_records,maximum_binding_bytes,maximum_retained_records) VALUES"
              + " (?,?,?,?) ON CONFLICT DO NOTHING",
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

    void replayBridge() {
      var replayed = region.owner().bridge(region.installation());
      assertThat(replayed.obligation()).isEqualTo(region.acknowledgement().obligation());
    }

    OriginalNewCapacityExecution newControlledExecution() {
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

    CanonicalGameplayRegionOwnerTransferRepository transfers(DSLContext context) {
      return new CanonicalGameplayRegionOwnerTransferRepository(context, clock, tags);
    }

    CanonicalGameplayOriginalNewCapacityExecutionOwner capacity(DSLContext context) {
      return new CanonicalGameplayOriginalNewCapacityExecutionOwner(
          context, redis, scripts, clock, tags);
    }

    void recreateTransferOwners() throws Exception {
      transfers = transfers(sql);
      receiver =
          new CanonicalGameplayControlledRegionTransferObservationFixture(clock)
              .receiver(transfers, tags);
      coordinator =
          new CanonicalGameplayRegionOwnerTransferCoordinator(
              sql, clock, transfers, capacity(sql), receiver);
    }

    UUID begin() {
      return transfers.within(transferDeadline(), ignored -> transfers.begin(scope, prior));
    }

    void seedUndeliveredDispatch(OriginalNewCapacityExecution original) {
      // Controlled crash-window storage seed only. No guard is disabled, no AUTHORIZED result is
      // invented, and this is not the real authorize path exercised by the two row-lock races.
      precharge(original);
      insertExecution(original, "INSTALL_DISPATCHED", null);
    }

    void settle(OriginalNewCapacityExecution original, UUID denial) {
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

    void precharge(OriginalNewCapacityExecution original) {
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
    void retainExactChargeForControlledSeed(OriginalNewCapacityExecution original) {
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

    void insertExecution(
        OriginalNewCapacityExecution original, String status, byte[] terminalReadback) {
      insertExecution(sql, original, status, terminalReadback);
    }

    void insertExecution(
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

    UUID insertRawFrozenTransfer(DSLContext context) {
      return insertRawFrozenTransfer(context, scope, prior);
    }

    UUID insertRawFrozenTransfer(
        DSLContext context,
        CanonicalGameplayRegionRuntimeStatusRepository.Source source,
        CanonicalGameplayRegionRuntimeStatus owner) {
      UUID transferId = UUID.randomUUID();
      context.execute(
          "INSERT INTO game_session_region_owner_transfer"
              + " (transfer_id,tenant_id,game_instance_id,region_id,region_epoch,old_owner_id,old_owner_fence,"
              + " old_lease_correlation,source_digest,intent_digest,state) VALUES"
              + " (?,?,?,?,?,?,?,?,?,?,'FROZEN')",
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

    void insertRawTransferItem(DSLContext context, UUID transferId, long ordinal) {
      var assignment = tags.assignmentForTransition(execution.transitionId());
      context.execute(
          "INSERT INTO game_session_region_owner_transfer_item"
              + " (transfer_id,target_ordinal,execution_digest,canonical_execution,candidate_digest,"
              + " tenant_gameplay_tag,workload_namespace,allocation_version) VALUES"
              + " (?,?,?,?,?,?,?,?)",
          transferId,
          ordinal,
          execution.digest(),
          execution.canonicalBytes(),
          execution.candidateDigest(),
          assignment.tenantGameplayTag(),
          assignment.workloadNamespace(),
          assignment.allocationVersion());
    }

    List<CanonicalGameplayRegionOwnerTransferRepository.Item> captureAndFence(UUID id) {
      var page = transfers.within(transferDeadline(), ignored -> transfers.capture(id));
      assertThat(page.size()).isLessThanOrEqualTo(32);
      for (var item : page) fence(item);
      return page;
    }

    void fence(CanonicalGameplayRegionOwnerTransferRepository.Item item) {
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

    void verifyPage(UUID id) {
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

    CanonicalGameplayRegionRuntimeStatus install(UUID id) {
      return coordinator.install(
          id,
          scope,
          prior,
          "e".repeat(64),
          "game-session-service",
          "sql-proof-successor",
          transferDeadline());
    }

    CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback issuerOpen() {
      var repository =
          new CanonicalGameplayIssuerAdmissionBarrierRepository(
              sql, tags, fixture.currentIssuerSource());
      return new CanonicalGameplayIssuerAdmissionBarrierRedisOwner(
              redis, scripts, repository, fixture.currentIssuerSource())
          .initializeAndRead(fixture.originalOperationId());
    }

    CanonicalGameplayAccountInitialOpenProof.CurrentOpen controlledAccountOpen() {
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

    CanonicalGameplayRegionRuntimeStatus currentOwner() {
      return new CanonicalGameplayRegionRuntimeStatusRepository(sql).find(scope).orElseThrow();
    }

    String executionState(OriginalNewCapacityExecution original) {
      Record row =
          sql.fetchOne(
              "SELECT status FROM game_session_original_new_capacity_execution WHERE"
                  + " execution_digest=?",
              original.digest());
      return row == null ? null : row.get(0, String.class);
    }

    boolean physicalExecutionExists(OriginalNewCapacityExecution original) {
      String tag = tags.assignmentForTransition(original.transitionId()).tenantGameplayTag();
      return physicalExecutionExists(original, tag);
    }

    boolean physicalExecutionExists(OriginalNewCapacityExecution original, String tag) {
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

    void losePhysicalExecution(OriginalNewCapacityExecution original) {
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

    void rawReplaceOwner(String owner, String correlation) {
      rawReplaceOwner(sql, owner, correlation);
    }

    void rawReplaceOwner(DSLContext context, String owner, String correlation) {
      context.execute(
          "UPDATE game_session_region_runtime_status SET"
              + " owner_instance_id=?,executor_fence=nextval('game_session_region_executor_fence_seq'),lease_token_correlation=?"
              + " WHERE tenant_id=? AND game_instance_id=? AND region_id=?",
          owner,
          correlation,
          scope.tenantId(),
          scope.gameInstanceId(),
          scope.regionId());
    }

    CanonicalGameplayRegionRuntimeStatus rawCompleteAndInstall(
        UUID transferId, String owner, String correlation) {
      return rawCompleteAndInstall(transferId, owner, correlation, false);
    }

    CanonicalGameplayRegionRuntimeStatus rawCompleteAndInstall(
        UUID transferId, String owner, String correlation, boolean claimCompleteVerification) {
      String schema = sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      try (var connection =
          java.sql.DriverManager.getConnection(
              postgresContainer().getJdbcUrl(),
              postgresContainer().getUsername(),
              postgresContainer().getPassword())) {
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
                "UPDATE game_session_region_owner_transfer SET"
                    + " state='COMPLETED',new_owner_id=?,new_owner_fence=?,new_lease_correlation=?"
                    + " WHERE transfer_id=? AND state='FROZEN'",
                owner,
                fence,
                correlation,
                transferId)
            != 1) throw new IllegalStateException("Expected one frozen transfer");
        Record installed =
            transaction.fetchOne(
                "UPDATE game_session_region_runtime_status SET"
                    + " executor_fence=?,lease_token_correlation=?,owner_service=?,owner_instance_id=?,"
                    + " updated_at=CURRENT_TIMESTAMP::timestamp WHERE tenant_id=? AND"
                    + " game_instance_id=? AND region_id=? AND tenant_region_tag=? AND"
                    + " canonical_region_id=? AND lifecycle_epoch=? AND lifecycle_row_version=? AND"
                    + " capture_id=? AND lifecycle_request_bytes=? AND graph_sha256=? AND"
                    + " preparation_input_digest=? AND region_epoch=? AND executor_fence=? AND"
                    + " lease_token_correlation=? AND owner_service=? AND owner_instance_id=? AND"
                    + " paused=? AND last_committed_tick_id=? AND last_committed_tick_batch_id IS"
                    + " NOT DISTINCT FROM ? RETURNING *",
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

    String originalImage() {
      return sql.fetch(
              "SELECT to_jsonb(e)::text FROM game_session_original_new_capacity_execution e ORDER"
                  + " BY execution_digest")
          .getValues(0, String.class)
          .toString();
    }

    String transferImage(UUID id) {
      return sql.fetchSingle(
              "SELECT to_jsonb(t)::text FROM game_session_region_owner_transfer t WHERE"
                  + " transfer_id=?",
              id)
          .get(0, String.class);
    }

    String itemImage(UUID id) {
      return sql.fetch(
              "SELECT to_jsonb(i)::text FROM game_session_region_owner_transfer_item i WHERE"
                  + " transfer_id=? ORDER BY target_ordinal",
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

  static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  CanonicalGameplayRegionRuntimeStatus installAfterBarrier(
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

  long fenceSequenceValue() {
    Record row = dsl.fetchOne("SELECT last_value FROM game_session_region_executor_fence_seq");
    assertThat(row).isNotNull();
    Long value = row.get("last_value", Long.class);
    assertThat(value).isPositive();
    return value;
  }

  static CanonicalGameplayRegionRuntimeStatusRepository.Source source(
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

  static CanonicalGameplayRegionRuntimeStatusRepository.Source source(
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

  static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest(
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
