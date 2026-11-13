package net.firedevops.firemud.gamesession.repository;

import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayCapacityOriginCustodyRedisScriptContribution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.RestoreArgs;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountIndexMember;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIssuerIndexObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionLeaseRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayOriginalNewRegionHandoffIntegrationTest
    extends CanonicalGameplayOriginalNewFinalAdmissionOwnerIntegrationTestSupport {
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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "false,false",
    "false,true",
    "true,false",
    "true,true"
  })
  void exactAbortedOriginalCompletesSameTransferBeforeAndAfterCapture(
      boolean abortAfterCapture, boolean originalReservationExpired) throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    Clock transferClock =
        originalReservationExpired ? Clock.offset(CLOCK, Duration.ofHours(1)) : CLOCK;
    if (originalReservationExpired)
      assertThat(controlledCapacity.reservation().expiresAt()).isBefore(transferClock.instant());
    var repository =
        new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), transferClock, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture(transferClock)
            .receiver(repository, tags);
    var capacity =
        new CanonicalGameplayOriginalNewCapacityExecutionOwner(
            fixture.dsl(), ownerClient, catalog, transferClock, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), transferClock, repository, capacity, receiver);
    UUID transferId = null;
    if (abortAfterCapture) {
      transferId =
          repository.within(
              transferClock.millis() + 5000L,
              ignored -> repository.begin(original.source, original.status));
      UUID capturedId = transferId;
      var captured =
          repository.within(
              transferClock.millis() + 5000L, ignored -> repository.capture(capturedId));
      assertThat(captured).hasSize(1);
    }
    assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
    byte[] candidate = admin.get(ascii(installation.sessionKey()));
    UUID resumed =
        transfer.advance(original.source, original.status, transferClock.millis() + 5000L);
    if (transferId != null) assertThat(resumed).isEqualTo(transferId);
    String intent =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT intent_digest FROM game_session_region_owner_transfer WHERE transfer_id=?",
                resumed)
            .get(0, String.class);
    // Real expiry of the incumbent Redis lease, followed by actual grant acquisition and protected
    // installation.
    admin.pexpire(ascii(original.grant.key()), 0);
    var installed =
        abortAfterCapture && !originalReservationExpired
            ? resumeAbortedOriginalWithLeaseOwner(transfer, original)
            : installAbortedTransferSuccessor(transfer, original, resumed, transferClock);
    assertThat(installed.ownerInstanceId())
        .isEqualTo(
            abortAfterCapture && !originalReservationExpired
                ? "final-owner-handoff"
                : "aborted-original-successor");
    assertThat(installed.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(installed);
    var completed =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT transfer_id,intent_digest,state FROM game_session_region_owner_transfer");
    assertThat(completed.get("transfer_id", UUID.class)).isEqualTo(resumed);
    assertThat(completed.get("intent_digest", String.class)).isEqualTo(intent);
    assertThat(completed.get("state", String.class)).isEqualTo("COMPLETED");
    assertThat(
            receiver
                .observeForRegionOwnerTransfer(resumed, 0, transferClock.millis() + 5000L)
                .regionOwnerTransferContext()
                .controlState())
        .isEqualTo("COMPLETED");
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(candidate);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_canonical_binding_transition WHERE transition_id=?",
                    fixture.transitionId())
                .get(0, String.class))
        .isEqualTo("ABORTED");
    assertThatThrownBy(() -> tags.assignmentForTransition(fixture.transitionId()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThatThrownBy(
            () ->
                capacity.readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    String terminalRows = sqlImage();
    assertThatThrownBy(
            () ->
                transfer.advance(original.source, original.status, transferClock.millis() + 5000L))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(terminalRows);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "binding", "ttl"})
  void abortedTransferCannotInstallWithMissingOrChangedRetainedFence(String fault)
      throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    UUID id = transfer.advance(original.source, original.status, deadline());
    byte[] key = ascii(capacityKey(assignment));
    if (fault.equals("missing")) admin.del(java.util.List.of(key).toArray(byte[][]::new));
    else if (fault.equals("binding")) admin.hset(key, ascii("binding"), ascii("changed"));
    else admin.pexpire(key, 60_000L);
    byte[] altered =
        CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key);
    String retained = sqlImage();
    admin.pexpire(ascii(original.grant.key()), 0);
    assertThatThrownBy(() -> installAbortedTransferSuccessor(transfer, original, id, CLOCK))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(original.status);
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT state FROM game_session_region_owner_transfer")
                .get(0, String.class))
        .isEqualTo("FROZEN");
    assertThat(sqlImage()).isEqualTo(retained);
    assertThat(CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key))
        .containsExactly(altered);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void abortedCapturedOriginalRetainsPhysicalClosureBeforeSuccessorInstallation(
      boolean closeBeforeAbort) throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    UUID id =
        repository.within(
            deadline(), ignored -> repository.begin(original.source, original.status));
    var captured = repository.within(deadline(), ignored -> repository.capture(id));
    assertThat(captured).hasSize(1);
    if (closeBeforeAbort)
      capacityOwner()
          .closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
    if (!closeBeforeAbort)
      capacityOwner()
          .closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
    var proof =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(proof.disposition()).isEqualTo("NOT_EXECUTED_AFTER_AUTHORIZATION");
    byte[] tombstone =
        CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(
            admin,
            ascii(
                capacityKey(tags.assignmentForRetainedCapacityExecution(fixture.transitionId()))));
    assertThat(transfer.advance(original.source, original.status, deadline())).isEqualTo(id);
    admin.pexpire(ascii(original.grant.key()), 0);
    var installed = installAbortedTransferSuccessor(transfer, original, id, CLOCK);
    assertThat(installed.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(
            receiver
                .observeForRegionOwnerTransfer(id, 0, deadline())
                .regionOwnerTransferContext()
                .controlState())
        .isEqualTo("COMPLETED");
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .canonicalReadback())
        .containsExactly(proof.canonicalReadback());
    assertThat(
            CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(
                admin,
                ascii(
                    capacityKey(
                        tags.assignmentForRetainedCapacityExecution(fixture.transitionId())))))
        .containsExactly(tombstone);
  }

  @Test
  void lostDispatchCommitReplyBeforeRedisSendFencesUnknownAndActuallyHandsOff() throws Exception {
    repairAccountMember();
    bridge(false);
    String schema = fixture.dsl().fetchSingle("SELECT current_schema()").get(0, String.class);
    DriverManagerDataSource raw = new DriverManagerDataSource();
    raw.setDriverClassName("org.postgresql.Driver");
    String jdbcUrl = POSTGRES.getJdbcUrl();
    raw.setUrl(jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema);
    raw.setUsername(POSTGRES.getUsername());
    raw.setPassword(POSTGRES.getPassword());
    var stoppedAfterCommit = new java.util.concurrent.atomic.AtomicBoolean();
    var stoppingDataSource =
        new org.springframework.jdbc.datasource.DelegatingDataSource(raw) {
          @Override
          public java.sql.Connection getConnection() throws java.sql.SQLException {
            return failDispatchCommitReplyAfterMarker(
                super.getConnection(), raw, controlledCapacity.digest(), stoppedAfterCommit);
          }
        };
    var stoppingDsl = org.jooq.impl.DSL.using(stoppingDataSource, org.jooq.SQLDialect.POSTGRES);
    var stoppingOwner =
        new CanonicalGameplayOriginalNewCapacityExecutionOwner(
            stoppingDsl, ownerClient, catalog, CLOCK, tags);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var rawTemplate = credential.template();
        var template = rawTemplate.bindCapacityExecution(controlledCapacity)) {
      assertThatThrownBy(
              () ->
                  stoppingOwner.authorize(
                      source, template, accountOpen, issuerOpen, controlledCapacity, deadline()))
          .isInstanceOf(RuntimeException.class);
    }
    assertThat(stoppedAfterCommit).isTrue();
    String originalKey = capacityKey(tags.assignmentForTransition(fixture.transitionId()));
    assertThat(admin.type(ascii(originalKey))).isEqualTo("none");
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                    controlledCapacity.digest())
                .get(0, String.class))
        .isEqualTo("INSTALL_DISPATCHED");
    var original = regionBinding.installation();
    admin.pexpire(ascii(original.grant.key()), 0);
    var successor = actualTransferHandoff();
    assertThat(successor.status.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(
            admin.hget(
                ascii(capacityKey(tags.assignmentForTransition(fixture.transitionId()))),
                ascii("state")))
        .containsExactly(ascii("FENCED_INDETERMINATE"));
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                    controlledCapacity.digest())
                .get(0, String.class))
        .isEqualTo("INSTALL_DISPATCHED");
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .closeOriginalAttempt(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void retainedTransferObservationDoesNotWaitOnInventoryClock() throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    UUID transferId = transfer.advance(original.source, original.status, deadline());
    String schema = fixture.dsl().fetchSingle("SELECT current_schema()").get(0, String.class);
    try (var blocker =
        java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      blocker.setSchema(schema);
      blocker.setAutoCommit(false);
      try (var statement = blocker.createStatement()) {
        statement
            .executeQuery(
                "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock"
                    + " WHERE singleton_id=1 FOR UPDATE")
            .close();
      }
      try {
        var observation = receiver.observeForRegionOwnerTransfer(transferId, 0, deadline());
        assertThat(observation.regionOwnerTransferContext().controlState()).isEqualTo("FROZEN");
      } finally {
        blocker.rollback();
      }
    }
  }

  @Test
  void contenderAcquireUsesBoundedInitialTtlWhileOrdinaryRenewKeepsConfiguredTtl()
      throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var leases = new CanonicalGameplayRegionLeaseRedisOwner(ownerClient, ownerUri, catalog);
    var identity =
        new net.firedevops.firemud.common.runtime.RuntimeIdentity(
            "game-session-service",
            "bounded-contender",
            "localhost",
            CLOCK.instant(),
            "test",
            "test",
            "controlled-test-image");
    byte[] key = ascii(original.grant.key());
    assertThat(admin.pexpire(key, 0)).isTrue();
    byte[] acquireScript;
    try (InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                CanonicalGameplayRegionLeaseRedisScriptContribution.ACQUIRE_PATH)) {
      assertThat(input).isNotNull();
      acquireScript = input.readAllBytes();
    }
    byte[][] keys = {
      key,
      ascii(
          CanonicalGameplayRegionRedisKeys.meta(
              original.source.tenantId(),
              original.source.gameInstanceId(),
              original.source.regionId()))
    };
    byte[] token = ascii(UUID.randomUUID().toString());
    byte[][] arguments = {
      ascii("gs_region_lease/v2"),
      token,
      ascii(Long.toString(original.status.regionEpoch())),
      ascii("30000"),
      ascii("1")
    };
    Long refused = admin.eval(acquireScript, ScriptOutputType.INTEGER, keys, arguments);
    assertThat(refused).isZero();
    assertThat(admin.type(key)).isEqualTo("none");

    // Bypass the Java client's own TTL cap to exercise the Redis deadline independently.
    long redisNow =
        admin.eval(
            "local now = redis.call('TIME'); return now[1] * 1000 + math.floor(now[2] / 1000)",
            ScriptOutputType.INTEGER);
    long deadline = redisNow + 5_000L;
    arguments[4] = ascii(Long.toString(deadline));
    Long acquired = admin.eval(acquireScript, ScriptOutputType.INTEGER, keys, arguments);
    assertThat(acquired).isEqualTo(1L);
    long expiry = admin.pexpiretime(key);
    assertThat(expiry).isGreaterThan(redisNow).isLessThanOrEqualTo(deadline);

    arguments[4] = ascii("1");
    Long replayed = admin.eval(acquireScript, ScriptOutputType.INTEGER, keys, arguments);
    assertThat(replayed).isEqualTo(2L);
    assertThat(admin.get(key)).containsExactly(token);
    assertThat(admin.pexpiretime(key)).isEqualTo(expiry);
    assertThat(admin.pexpire(key, 0)).isTrue();

    var grant =
        leases
            .forHandoffDeadline(CLOCK, CLOCK.millis() + 5_000L)
            .acquire(
                original.source, original.status.regionEpoch(), Duration.ofSeconds(30), identity);
    assertThat(admin.pttl(key)).isPositive().isLessThanOrEqualTo(5_000L);
    leases.renew(grant, Duration.ofSeconds(30));
    assertThat(admin.pttl(key)).isGreaterThan(5_000L).isLessThanOrEqualTo(30_000L);
  }

  @Test
  void contenderCannotFreezeRenewingOwnerAndExpiredLeaseStillTransfers() throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var leases = new CanonicalGameplayRegionLeaseRedisOwner(ownerClient, ownerUri, catalog);
    String rows = sqlImage();
    Map<String, String> physical = physicalImage();
    String key = capacityKey(tags.assignmentForTransition(fixture.transitionId()));
    for (int attempt = 0; attempt < 2; attempt++) {
      leases.renew(original.grant, Duration.ofSeconds(30));
      assertThatThrownBy(this::actualTransferHandoff).isInstanceOf(IllegalStateException.class);
      leases.requireCurrent(original.grant);
      assertThat(sqlImage()).isEqualTo(rows);
      assertThat(physicalImage()).isEqualTo(physical);
      assertThat(admin.hget(ascii(key), ascii("state"))).containsExactly(ascii("AUTHORIZED"));
      assertThat(
              fixture
                  .dsl()
                  .fetchCount(org.jooq.impl.DSL.table("game_session_region_owner_transfer")))
          .isZero();
    }
    admin.pexpire(ascii(original.grant.key()), 0);
    var successor = actualTransferHandoff();
    assertThat(successor.status.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(admin.hget(ascii(key), ascii("state")))
        .containsExactly(ascii("FENCED_INDETERMINATE"));
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT state FROM game_session_region_owner_transfer")
                .get(0, String.class))
        .isEqualTo("COMPLETED");
  }

  @Test
  void blockedTransferPreinstallRegionStatusReadKeepsCurrentOwner() throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    String schema = fixture.dsl().fetchSingle("SELECT current_schema()").get(0, String.class);
    java.util.concurrent.CompletableFuture<Boolean> handoff = null;
    try (var lock =
        java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      lock.setSchema(schema);
      lock.setAutoCommit(false);
      int blockerPid;
      try (var statement = lock.createStatement()) {
        statement.execute("LOCK TABLE game_session_region_runtime_status IN ACCESS EXCLUSIVE MODE");
        try (var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
          rows.next();
          blockerPid = rows.getInt(1);
        }
      }

      handoff =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try {
                  actualTransferHandoff();
                  return false;
                } catch (IllegalStateException unavailable) {
                  return true;
                } catch (Exception failure) {
                  throw new IllegalStateException(failure);
                }
              });
      long blockedDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      boolean statusReadBlocked = false;
      while (System.nanoTime() < blockedDeadline && !statusReadBlocked && !handoff.isDone()) {
        statusReadBlocked =
            fixture
                    .dsl()
                    .fetchOne(
                        "SELECT pid FROM pg_stat_activity WHERE pid <> pg_backend_pid()"
                            + " AND ? = ANY(pg_blocking_pids(pid))"
                            + " AND query LIKE '%game_session_region_runtime_status%' LIMIT 1",
                        blockerPid)
                != null;
        if (!statusReadBlocked) Thread.sleep(20L);
      }
      boolean failedClosed = handoff.get(7, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(statusReadBlocked).as("handoff reached the blocked region status read").isTrue();
      assertThat(failedClosed).as("bounded status read fails closed").isTrue();
    } finally {
      if (handoff != null && !handoff.isDone()) {
        assertThat(handoff.get(7, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      }
    }

    var current =
        new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
            .find(original.source)
            .orElseThrow();
    assertThat(current.executorFence()).isEqualTo(original.status.executorFence());
    assertThat(current.ownerInstanceId()).isEqualTo(original.status.ownerInstanceId());
    assertThat(
            fixture.dsl().fetchCount(org.jooq.impl.DSL.table("game_session_region_owner_transfer")))
        .isZero();
  }

  @Test
  void authorizedUnknownHandoffKeepsOriginalCandidateAndRefusesCompletedMutation()
      throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    byte[] candidate = admin.get(ascii(installation.sessionKey()));
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    assertThatThrownBy(() -> replaceRegionOwner("unproved-successor"))
        .isInstanceOf(IllegalStateException.class);
    admin.pexpire(ascii(original.grant.key()), 0);
    var successor =
        CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            original,
            sources.resolver,
            transfer,
            deadline());
    UUID transferId =
        fixture
            .dsl()
            .fetchSingle("SELECT transfer_id FROM game_session_region_owner_transfer")
            .get(0, UUID.class);
    var completed = receiver.observeForRegionOwnerTransfer(transferId, 0, deadline());
    assertThat(completed.regionOwnerTransferContext().controlState()).isEqualTo("COMPLETED");
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(candidate);
    assertThatThrownBy(
            () -> repository.within(deadline(), ignored -> repository.capture(transferId)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(successor.status.ownerInstanceId()).isEqualTo("final-owner-handoff");
  }

  @Test
  void completedTransferResumesOnlyWithTheSameLivePrivateGrant() throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var failAfterCommit = new java.util.concurrent.atomic.AtomicBoolean(true);
    var observationSource =
        new CanonicalGameplayRegionOwnerTransferObservationSource() {
          @Override
          public Context readCurrent(UUID transferId, long targetOrdinal) {
            var context = repository.readCurrent(transferId, targetOrdinal);
            if ("COMPLETED".equals(context.controlState()) && failAfterCommit.getAndSet(false)) {
              throw new IllegalStateException("Controlled completed observation failure");
            }
            return context;
          }

          @Override
          public CanonicalGameplayTenantGameplayTagRepository.Assignment readAssignment(
              UUID transitionId, CanonicalGameplayTenantGameplayTagRepository sourceTags) {
            return repository.readAssignment(transitionId, sourceTags);
          }
        };
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(observationSource, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    var identity =
        new net.firedevops.firemud.common.runtime.RuntimeIdentity(
            "game-session-service",
            "final-owner-handoff",
            "localhost",
            CLOCK.instant(),
            "test",
            "test",
            "controlled-test-image");
    CanonicalGameplayRegionIncarnationSource incarnation =
        scope -> {
          assertThat(scope).isEqualTo(original.incarnation.scope());
          return original.incarnation;
        };
    var successor =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            incarnation,
            sources.resolver,
            identity,
            java.time.Duration.ofSeconds(30),
            CLOCK);
    admin.pexpire(ascii(original.grant.key()), 0);
    var failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.TransferCompletionUnconfirmed.class,
            () ->
                successor.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()));
    assertThat(failAfterCommit).isFalse();
    var durable =
        new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
            .find(original.source)
            .orElseThrow();
    assertThat(durable.executorFence()).isGreaterThan(original.status.executorFence());
    var completed =
        fixture
            .dsl()
            .fetchSingle("SELECT state, transfer_id FROM game_session_region_owner_transfer");
    assertThat(completed.get("state", String.class)).isEqualTo("COMPLETED");
    assertThatThrownBy(
            () ->
                successor.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()))
        .isInstanceOf(IllegalStateException.class);
    String beforeWorldTimeout = sqlImage();
    Map<String, String> beforeWorldTimeoutRedis = physicalImage();
    sources.withholdWorldRead =
        CanonicalGameplayOriginalNewFinalAdmissionTestFixture.ControlledRegionSources.WorldRead
            .PLACEMENT;
    long started = System.nanoTime();
    assertThatThrownBy(() -> successor.resumeCompletedTransfer(failure, CLOCK.millis() + 1500))
        .isInstanceOfSatisfying(
            io.grpc.StatusRuntimeException.class,
            timeout ->
                assertThat(timeout.getStatus().getCode())
                    .isEqualTo(io.grpc.Status.Code.DEADLINE_EXCEEDED));
    assertThat(sources.withheldReadEntered.getCount()).isZero();
    assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        .isLessThan(3500);
    assertThat(sqlImage()).isEqualTo(beforeWorldTimeout);
    assertThat(physicalImage()).isEqualTo(beforeWorldTimeoutRedis);
    sources.withholdWorldRead = null;
    var recovered = successor.resumeCompletedTransfer(failure, deadline());
    assertThat(recovered.status).isEqualTo(durable);
    assertThat(recovered.grant.tokenCorrelation()).isEqualTo(durable.leaseTokenCorrelation());
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT state, transfer_id FROM game_session_region_owner_transfer"))
        .isEqualTo(completed);
    var completedSql = sqlImage();
    assertThat(successor.resumeCompletedTransfer(failure, deadline()).status).isEqualTo(durable);
    assertThat(sqlImage()).isEqualTo(completedSql);
    var restarted =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            incarnation,
            sources.resolver,
            new net.firedevops.firemud.common.runtime.RuntimeIdentity(
                "game-session-service",
                identity.serviceInstanceId(),
                "localhost",
                CLOCK.instant(),
                "test",
                "test",
                "controlled-test-image"),
            java.time.Duration.ofSeconds(30),
            CLOCK);
    assertThatThrownBy(() -> restarted.resumeCompletedTransfer(failure, deadline()))
        .isInstanceOf(IllegalStateException.class);
    byte[] leaseKey = ascii(recovered.grant.key());
    assertThatThrownBy(
            () ->
                restarted.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(completedSql);
    admin.pexpire(leaseKey, 0);
    assertThatThrownBy(() -> successor.resumeCompletedTransfer(failure, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(durable);

    assertThat(
            admin.set(
                leaseKey,
                ascii(UUID.randomUUID().toString()),
                io.lettuce.core.SetArgs.Builder.nx().px(30_000)))
        .isEqualTo("OK");
    assertThatThrownBy(
            () ->
                restarted.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(completedSql);

    admin.pexpire(leaseKey, 0);
    var fresh =
        restarted.installForTransition(
            null, original.transitionId, original.worldRequest, transfer, deadline());
    assertThat(fresh.status.ownerInstanceId()).isEqualTo(durable.ownerInstanceId());
    assertThat(fresh.status.executorFence()).isGreaterThan(durable.executorFence());
    assertThat(fresh.grant.tokenCorrelation()).isNotEqualTo(recovered.grant.tokenCorrelation());
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT state, transfer_id FROM game_session_region_owner_transfer WHERE transfer_id = ?",
                    completed.get("transfer_id", UUID.class)))
        .isEqualTo(completed);
    assertThat(
            fixture.dsl().fetchCount(org.jooq.impl.DSL.table("game_session_region_owner_transfer")))
        .isEqualTo(2);
  }

  @Test
  void completedTransferRecoversExactAbortRetainedBeforeIndependentVerification() throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var pinnedEvidence = inventory.readProvisionalCasEvidence(original.transitionId);
    var pinnedCandidate =
        inventory.readRegionTransferOriginalCandidate(original.transitionId, pinnedEvidence);
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var accountAbortedAfterInstall = new java.util.concurrent.atomic.AtomicBoolean();
    var observationSource =
        new CanonicalGameplayRegionOwnerTransferObservationSource() {
          @Override
          public Context readCurrent(UUID transferId, long targetOrdinal) {
            var context = repository.readCurrent(transferId, targetOrdinal);
            if ("COMPLETED".equals(context.controlState())
                && accountAbortedAfterInstall.compareAndSet(false, true)) {
              assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
            }
            return context;
          }

          @Override
          public CanonicalGameplayTenantGameplayTagRepository.Assignment readAssignment(
              UUID transitionId, CanonicalGameplayTenantGameplayTagRepository sourceTags) {
            return repository.readAssignment(transitionId, sourceTags);
          }
        };
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(observationSource, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    var identity =
        new net.firedevops.firemud.common.runtime.RuntimeIdentity(
            "game-session-service",
            "final-owner-handoff",
            "localhost",
            CLOCK.instant(),
            "test",
            "test",
            "controlled-test-image");
    CanonicalGameplayRegionIncarnationSource incarnation =
        scope -> {
          assertThat(scope).isEqualTo(original.incarnation.scope());
          return original.incarnation;
        };
    var successor =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            incarnation,
            sources.resolver,
            identity,
            java.time.Duration.ofSeconds(30),
            CLOCK);
    admin.pexpire(ascii(original.grant.key()), 0);
    var interrupted =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.TransferCompletionUnconfirmed.class,
            () ->
                successor.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()));

    assertThat(accountAbortedAfterInstall).isTrue();
    var durable =
        new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
            .find(original.source)
            .orElseThrow();
    assertThat(durable.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT state FROM game_session_region_owner_transfer")
                .get(0, String.class))
        .isEqualTo("COMPLETED");
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_canonical_binding_transition WHERE transition_id=?",
                    original.transitionId)
                .get(0, String.class))
        .isEqualTo("ABORTED");

    var recovered = successor.resumeCompletedTransfer(interrupted, deadline());
    assertThat(recovered.status).isEqualTo(durable);
    assertThat(recovered.grant.tokenCorrelation()).isEqualTo(durable.leaseTokenCorrelation());
    assertThat(inventory.readRegionTransferTerminalSource(original.transitionId, pinnedCandidate))
        .hasValueSatisfying(
            terminal -> {
              assertThat(terminal)
                  .isInstanceOf(
                      CanonicalGameplayBindingInventoryRepository.AbortedRegionTransferSource
                          .class);
              assertThat(terminal.candidate()).isEqualTo(pinnedCandidate);
            });
    assertThatThrownBy(() -> inventory.readProvisionalCasEvidence(original.transitionId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a retryable provisional operation");
    assertThatThrownBy(() -> successor.revalidateBeforeBridge(recovered))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void retainedTransferFenceLossCannotBeReinstalledOrQualifyHandoff() throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    transfer.advance(original.source, original.status, deadline());
    String key = capacityKey(tags.assignmentForTransition(fixture.transitionId()));
    admin.del(ascii(key));
    String retained = sqlImage();
    admin.pexpire(ascii(original.grant.key()), 0);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
                    fixture.dsl(),
                    ownerClient,
                    ownerUri,
                    catalog,
                    original,
                    sources.resolver,
                    transfer,
                    deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(admin.type(ascii(key))).isEqualTo("none");
    assertThat(sqlImage()).isEqualTo(retained);
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
                .find(original.source)
                .orElseThrow()
                .executorFence())
        .isEqualTo(original.status.executorFence());
  }

  @Test
  void retainedTransferFenceWithTtlCannotQualifyNativeHandoff() throws Exception {
    retainedTransferTamperBlocks("ttl");
  }

  @Test
  void retainedTransferFenceWrongTypeCannotQualifyNativeHandoff() throws Exception {
    retainedTransferTamperBlocks("type");
  }

  @Test
  void retainedTransferFenceChangedEnvelopeCannotQualifyNativeHandoff() throws Exception {
    retainedTransferTamperBlocks("binding");
  }

  @Test
  void chargedTransferRefusesAnOlderRestoredRedisExecutionAtCapacity() throws Exception {
    retainedTransferTamperBlocks("restored-authorization");
  }

  @Test
  void correctlySignedDifferentStorageGenerationCannotAdoptRetainedTransferReceipts()
      throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var attestor = new CanonicalGameplayControlledRegionTransferObservationFixture();
    var originalTransfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), attestor.receiver(repository, tags));
    originalTransfer.advance(original.source, original.status, deadline());
    String retained = sqlImage();
    var changed =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(),
            CLOCK,
            repository,
            capacityOwner(),
            attestor.receiver(repository, tags, "storage-2"));
    admin.pexpire(ascii(original.grant.key()), 0);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
                    fixture.dsl(),
                    ownerClient,
                    ownerUri,
                    catalog,
                    original,
                    sources.resolver,
                    changed,
                    deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(retained);
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
                .find(original.source)
                .orElseThrow()
                .executorFence())
        .isEqualTo(original.status.executorFence());
  }

  @Test
  void ordinaryProvisionalHandoffWithoutCapacityExecutionNeedsNoFinalAcknowledgements()
      throws Exception {
    regionBinding =
        CanonicalGameplayRegionOwnerCompositionFixture.installExistingOriginalCandidate(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            fixture.transitionId(),
            sources.request,
            sources.resolver,
            sources.entity);
    var original = regionBinding.installation();
    var before = inventory.readProvisionalCasEvidence(fixture.transitionId());
    assertThat(before.candidate().accountIndexState())
        .isEqualTo(
            net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry
                .IndexState.REPAIR_REQUIRED);
    assertThatThrownBy(() -> inventory.readOriginalNewFinalAdmissionSource(decision))
        .isExactlyInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT count(*) FROM game_session_original_new_capacity_execution")
                .get(0, Long.class))
        .isZero();

    admin.pexpire(ascii(original.grant.key()), 0);
    var successor = actualTransferHandoff();

    assertThat(successor.status.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(inventory.readProvisionalCasEvidence(fixture.transitionId())).isEqualTo(before);
    assertThatThrownBy(() -> inventory.readOriginalNewFinalAdmissionSource(decision))
        .isExactlyInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT count(*) FROM game_session_region_owner_transfer")
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void committedOriginalStartsHandoffWithoutAnExistingTransfer() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var original = regionBinding.installation();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var readback =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      inventory.commitOriginalNewFinalAdmission(readback);
    }
    var retained = inventory.readRegionTransferOriginalSource(fixture.transitionId(), source);
    var terminal =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(terminal.disposition()).isEqualTo("COMMITTED");
    assertThat(
            fixture.dsl().fetchCount(org.jooq.impl.DSL.table("game_session_region_owner_transfer")))
        .isZero();
    byte[] session = admin.get(ascii(installation.sessionKey()));

    admin.pexpire(ascii(original.grant.key()), 0);
    var successor = actualTransferHandoff();

    assertThat(successor.evidence).isNull();
    assertThat(successor.status.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(session);
    var current = inventory.readRegionTransferOriginalSource(fixture.transitionId(), retained);
    assertThat(current.finalReceipt()).isEqualTo(retained.finalReceipt());
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .canonicalReadback())
        .containsExactly(terminal.canonicalReadback());
  }

  @Test
  void frozenTransferResumesExactTerminalOriginalThatCommittedBeforeCapture() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var original = regionBinding.installation();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    UUID transferId;
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var readback =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      transferId =
          repository.within(
              deadline(), ignored -> repository.begin(original.source, original.status));
      assertThat(transferId).isNotNull();
      inventory.commitOriginalNewFinalAdmission(readback);
    }
    var captured = repository.within(deadline(), ignored -> repository.capture(transferId));
    assertThat(captured).isEmpty();
    var retained = inventory.readRegionTransferOriginalSource(fixture.transitionId(), source);
    byte[] committed = admin.get(ascii(installation.sessionKey()));
    assertThat(retained.finalReceipt()).isPresent();
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(original.status);
    admin.pexpire(ascii(original.grant.key()), 0);
    var successor =
        CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            original,
            sources.resolver,
            transfer,
            deadline());
    assertThat(successor.evidence).isNull();
    assertThat(successor.status.executorFence()).isGreaterThan(original.status.executorFence());
    var completed = fixture.dsl().fetchSingle("SELECT * FROM game_session_region_owner_transfer");
    assertThat(completed.get("transfer_id", UUID.class)).isEqualTo(transferId);
    assertThat(completed.get("state", String.class)).isEqualTo("COMPLETED");
    assertThat(
            fixture
                .dsl()
                .fetchCount(org.jooq.impl.DSL.table("game_session_region_owner_transfer_item")))
        .isZero();
    assertThat(
            inventory
                .readRegionTransferOriginalSource(fixture.transitionId(), retained)
                .finalReceipt())
        .isEqualTo(retained.finalReceipt());
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(committed);
  }

  @Test
  void secondFrozenHandoffResumesOlderOriginalAbortedBeforeCapture() throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var originalCandidate = inventory.readOriginalNewFinalAdmissionSource(decision).candidate();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);

    UUID firstTransferId = transfer.advance(original.source, original.status, deadline());
    assertThat(firstTransferId).isNotNull();
    admin.pexpire(ascii(original.grant.key()), 0);
    var firstSuccessor =
        CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            original,
            sources.resolver,
            transfer,
            deadline());
    assertThat(firstSuccessor.status.executorFence())
        .isGreaterThan(original.status.executorFence());
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT state FROM game_session_region_owner_transfer WHERE transfer_id=?",
                    firstTransferId)
                .get(0, String.class))
        .isEqualTo("COMPLETED");

    UUID secondTransferId =
        repository.within(
            deadline(), ignored -> repository.begin(firstSuccessor.source, firstSuccessor.status));
    // Leave the committed second freeze without capture, as after an interrupted process.
    assertThat(secondTransferId).isNotNull().isNotEqualTo(firstTransferId);
    var secondFrozen =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT old_owner_id,old_owner_fence,state FROM game_session_region_owner_transfer WHERE transfer_id=?",
                secondTransferId);
    assertThat(secondFrozen.get("old_owner_id", String.class))
        .isEqualTo(firstSuccessor.status.ownerInstanceId())
        .isNotEqualTo(controlledCapacity.selectedOwnerId());
    assertThat(secondFrozen.get("old_owner_fence", Long.class))
        .isGreaterThan(controlledCapacity.selectedOwnerFence());
    assertThat(secondFrozen.get("state", String.class)).isEqualTo("FROZEN");
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM game_session_region_owner_transfer_item WHERE transfer_id=?",
                    secondTransferId)
                .get(0, Long.class))
        .isZero();
    assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();

    var frozenOriginal =
        repository.within(deadline(), ignored -> repository.frozenOriginal(fixture.transitionId()));
    assertThat(frozenOriginal).isNotNull();
    assertThat(frozenOriginal.transferId()).isEqualTo(secondTransferId);
    assertThat(frozenOriginal.executionDigest()).isEqualTo(controlledCapacity.digest());
    assertThat(frozenOriginal.candidate().canonicalBytes())
        .containsExactly(originalCandidate.canonicalBytes());

    byte[] candidateBytes = admin.get(ascii(installation.sessionKey()));
    var thirdIdentity =
        new net.firedevops.firemud.common.runtime.RuntimeIdentity(
            "game-session-service",
            "third-owner-handoff",
            "localhost",
            CLOCK.instant(),
            "test",
            "test",
            "controlled-test-image");
    CanonicalGameplayRegionIncarnationSource thirdIncarnation =
        scope -> {
          assertThat(scope).isEqualTo(firstSuccessor.incarnation.scope());
          return firstSuccessor.incarnation;
        };
    var thirdOwner =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            thirdIncarnation,
            sources.resolver,
            thirdIdentity,
            Duration.ofSeconds(30),
            CLOCK);
    admin.pexpire(ascii(firstSuccessor.grant.key()), 0);
    var secondSuccessor =
        thirdOwner.installForTransition(
            null, firstSuccessor.transitionId, firstSuccessor.worldRequest, transfer, deadline());
    assertThat(secondSuccessor.status.ownerInstanceId()).isEqualTo("third-owner-handoff");
    assertThat(secondSuccessor.status.executorFence())
        .isGreaterThan(firstSuccessor.status.executorFence());
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(candidateBytes);
    var retainedExecution =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT selected_owner_id,selected_owner_fence,status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                controlledCapacity.digest());
    assertThat(retainedExecution.get("selected_owner_id", String.class))
        .isEqualTo(controlledCapacity.selectedOwnerId());
    assertThat(retainedExecution.get("selected_owner_fence", Long.class))
        .isEqualTo(controlledCapacity.selectedOwnerFence());
    assertThat(retainedExecution.get("status", String.class)).isEqualTo("AUTHORIZED");
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT old_owner_id,old_owner_fence,new_owner_id,new_owner_fence,state FROM game_session_region_owner_transfer WHERE transfer_id=?",
                    secondTransferId)
                .get("state", String.class))
        .isEqualTo("COMPLETED");
    var completedSecondTransfer =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT old_owner_id,old_owner_fence,new_owner_id,new_owner_fence FROM game_session_region_owner_transfer WHERE transfer_id=?",
                secondTransferId);
    assertThat(completedSecondTransfer.get("old_owner_id", String.class))
        .isEqualTo(firstSuccessor.status.ownerInstanceId());
    assertThat(completedSecondTransfer.get("new_owner_id", String.class))
        .isEqualTo("third-owner-handoff");
    assertThat(completedSecondTransfer.get("new_owner_fence", Long.class))
        .isGreaterThan(completedSecondTransfer.get("old_owner_fence", Long.class));
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_canonical_binding_transition WHERE transition_id=?",
                    fixture.transitionId())
                .get(0, String.class))
        .isEqualTo("ABORTED");
  }

  @Test
  void frozenTransferResumesSameCommittedOriginalAfterFenceRetentionFailure() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    byte[] committed;
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var ignored =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      committed = admin.get(ascii(installation.sessionKey()));
    }
    var original = regionBinding.installation();
    var failRetention = new java.util.concurrent.atomic.AtomicBoolean(true);
    var failed = new java.util.concurrent.atomic.AtomicBoolean();
    var observer =
        new org.jooq.impl.DefaultExecuteListener() {
          @Override
          public void executeStart(org.jooq.ExecuteContext context) {
            String query = context.sql();
            if (query == null || !query.contains(" SET fence_readback=")) return;
            if (!failRetention.get()) return;
            assertThat(
                    fixture
                        .dsl()
                        .fetchSingle(
                            "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                            controlledCapacity.digest())
                        .get(0, String.class))
                .isEqualTo("COMMITTED");
            assertThat(
                    fixture
                        .dsl()
                        .fetchSingle(
                            "SELECT original_new_final_session_sha256 FROM game_session_canonical_binding_transition WHERE transition_id=?",
                            fixture.transitionId())
                        .get(0, String.class))
                .isNotBlank();
            failed.set(true);
            throw new IllegalStateException("Controlled failure after positive SQL retention");
          }
        };
    var observedSql =
        org.jooq.impl.DSL.using(fixture.dsl().configuration().deriveAppending(observer));
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(observedSql, CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            observedSql, CLOCK, repository, capacityOwner(), receiver);
    var identity =
        new net.firedevops.firemud.common.runtime.RuntimeIdentity(
            "game-session-service",
            "final-owner-handoff",
            "localhost",
            CLOCK.instant(),
            "test",
            "test",
            "controlled-test-image");
    CanonicalGameplayRegionIncarnationSource incarnation =
        scope -> {
          assertThat(scope).isEqualTo(original.incarnation.scope());
          return original.incarnation;
        };
    var successor =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            incarnation,
            sources.resolver,
            identity,
            Duration.ofSeconds(30),
            CLOCK);
    admin.pexpire(ascii(original.grant.key()), 0);
    var frozenFailure =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.FrozenTransferUnconfirmed.class,
            () ->
                successor.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()));
    assertThat(failed).isTrue();
    var frozen = fixture.dsl().fetchSingle("SELECT * FROM game_session_region_owner_transfer");
    UUID transferId = frozen.get("transfer_id", UUID.class);
    String intent = frozen.get("intent_digest", String.class);
    assertThat(frozen.get("state", String.class)).isEqualTo("FROZEN");
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(original.status);
    var retained = inventory.readRegionTransferOriginalSource(fixture.transitionId(), source);
    assertThat(retained.transitionStatus())
        .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED);
    assertThat(retained.finalReceipt()).isPresent();
    String executionImage =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT to_jsonb(e)::text FROM game_session_original_new_capacity_execution e WHERE execution_digest=?",
                controlledCapacity.digest())
            .get(0, String.class);
    long sequence =
        fixture
            .dsl()
            .fetchSingle("SELECT last_value FROM game_session_region_executor_fence_seq")
            .get(0, Long.class);
    failRetention.set(false);
    var recovered = successor.resumeFrozenTransfer(frozenFailure, deadline());
    assertThat(recovered.status.ownerInstanceId()).isEqualTo("final-owner-handoff");
    var completed = fixture.dsl().fetchSingle("SELECT * FROM game_session_region_owner_transfer");
    assertThat(completed.get("transfer_id", UUID.class)).isEqualTo(transferId);
    assertThat(completed.get("intent_digest", String.class)).isEqualTo(intent);
    assertThat(completed.get("state", String.class)).isEqualTo("COMPLETED");
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT last_value FROM game_session_region_executor_fence_seq")
                .get(0, Long.class))
        .isEqualTo(sequence + 1);
    assertThat(
            inventory
                .readRegionTransferOriginalSource(fixture.transitionId(), retained)
                .finalReceipt())
        .isEqualTo(retained.finalReceipt());
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT to_jsonb(e)::text FROM game_session_original_new_capacity_execution e WHERE execution_digest=?",
                    controlledCapacity.digest())
                .get(0, String.class))
        .isEqualTo(executionImage);
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(committed);
    assertThat(recovered.evidence)
        .as("committed continuation grants no provisional bridge authority")
        .isNull();
    // The old epoch cannot reacquire the lease after the successor advances the timeline.
    assertThatThrownBy(
            () ->
                CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
                    fixture.dsl(),
                    ownerClient,
                    ownerUri,
                    catalog,
                    original,
                    sources.resolver,
                    transfer,
                    deadline()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Canonical Game Session region lease is unavailable");
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(recovered.status);
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT last_value FROM game_session_region_executor_fence_seq")
                .get(0, Long.class))
        .isEqualTo(sequence + 1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      CanonicalGameplayOriginalNewFinalAdmissionTestFixture.ControlledRegionSources.WorldRead.class)
  void stalledWorldReadCannotOutliveHandoffDeadline(
      CanonicalGameplayOriginalNewFinalAdmissionTestFixture.ControlledRegionSources.WorldRead
          stalledRead)
      throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var transferRepository =
        new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(transferRepository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, transferRepository, capacityOwner(), receiver);
    admin.pexpire(ascii(original.grant.key()), 0);
    String beforeSql = sqlImage();
    Map<String, String> beforeRedis = physicalImage();
    sources.withholdWorldRead = stalledRead;
    long started = System.nanoTime();
    long handoffDeadline = CLOCK.millis() + 1500;
    assertThatThrownBy(
            () ->
                CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
                    fixture.dsl(),
                    ownerClient,
                    ownerUri,
                    catalog,
                    original,
                    sources.resolver,
                    transfer,
                    handoffDeadline))
        .isInstanceOfSatisfying(
            io.grpc.StatusRuntimeException.class,
            failure ->
                assertThat(failure.getStatus().getCode())
                    .isEqualTo(io.grpc.Status.Code.DEADLINE_EXCEEDED));
    assertThat(sources.withheldReadEntered.getCount()).isZero();
    assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        .isLessThan(3500);
    assertThat(sqlImage()).isEqualTo(beforeSql);
    assertThat(physicalImage()).isEqualTo(beforeRedis);
    assertThat(
            fixture.dsl().fetchCount(org.jooq.impl.DSL.table("game_session_region_owner_transfer")))
        .isZero();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2})
  void blockedCommittedSelectionOrPreInstallRevalidationCannotOutliveHandoffDeadline(
      int blockedRead) throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var ignored =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      // Leave positive SQL retention to the actual transfer, as in the crash-window test.
    }
    var original = regionBinding.installation();
    var transferRepository =
        new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(transferRepository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, transferRepository, capacityOwner(), receiver);
    String schema = fixture.dsl().fetchSingle("SELECT current_schema()").get(0, String.class);
    var blocked = new java.util.concurrent.atomic.AtomicBoolean();
    var completed = new java.util.concurrent.CountDownLatch(1);
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    try (var lock =
        java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      lock.setSchema(schema);
      lock.setAutoCommit(false);
      var observer =
          new org.jooq.impl.DefaultExecuteListener() {
            @Override
            public void executeStart(org.jooq.ExecuteContext context) {
              String sql = context.sql();
              if (sql == null
                  || !sql.contains("game_session_canonical_binding_inventory_clock")
                  || !sql.contains("FOR UPDATE")) return;
              // This derived observer belongs only to the successor inventory/status context.
              // The independently composed transfer has already retained the positive winner.
              String status =
                  fixture
                      .dsl()
                      .fetchSingle(
                          "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                          controlledCapacity.digest())
                      .get(0, String.class);
              if (!"COMMITTED".equals(status) || reads.incrementAndGet() != blockedRead) return;
              try (var statement = lock.createStatement()) {
                statement.setQueryTimeout(2);
                statement.execute(
                    "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock WHERE singleton_id=1 FOR UPDATE");
                blocked.set(true);
              } catch (java.sql.SQLException failure) {
                throw new IllegalStateException(failure);
              }
            }

            @Override
            public void exception(org.jooq.ExecuteContext context) {
              if (blocked.get()) completed.countDown();
            }

            @Override
            public void executeEnd(org.jooq.ExecuteContext context) {
              if (blocked.get()) completed.countDown();
            }
          };
      var successorSql =
          org.jooq.impl.DSL.using(
              fixture
                  .dsl()
                  .configuration()
                  .derive(new org.jooq.impl.DefaultExecuteListenerProvider(observer)));
      admin.pexpire(ascii(original.grant.key()), 0);
      long started = System.nanoTime();
      long handoffDeadline = CLOCK.millis() + 4000;
      try {
        assertThatThrownBy(
                () ->
                    CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
                        successorSql,
                        ownerClient,
                        ownerUri,
                        catalog,
                        original,
                        sources.resolver,
                        transfer,
                        handoffDeadline))
            .isInstanceOf(IllegalStateException.class);
        assertThat(blocked).as("exact committed inventory read reached").isTrue();
        assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
            .isLessThan(6500);
        assertThat(completed.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      } finally {
        lock.rollback();
      }
    }
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT executor_fence FROM game_session_region_runtime_status")
                .get(0, BigInteger.class))
        .isEqualTo(original.status.executorFence());
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT state FROM game_session_region_owner_transfer")
                .get(0, String.class))
        .isEqualTo("FROZEN");
  }

  @Test
  void committedHashWithoutCompletePositiveServingProofStillBlocksActualTransfer()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var ignored =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      admin.del(ascii(installation.sessionKey()));
    }
    var original = regionBinding.installation();
    admin.pexpire(ascii(original.grant.key()), 0);
    assertThatThrownBy(this::actualTransferHandoff).isInstanceOf(IllegalStateException.class);
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
                .find(original.source)
                .orElseThrow()
                .executorFence())
        .isEqualTo(original.status.executorFence());
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                    controlledCapacity.digest())
                .get(0, String.class))
        .isEqualTo("AUTHORIZED");
    assertThat(
            admin.hget(
                ascii(capacityKey(tags.assignmentForTransition(fixture.transitionId()))),
                ascii("state")))
        .containsExactly(ascii("COMMITTED"));
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void delayedActualPredecessorInstallRefusesAfterNativeHandoffWithinOriginalDeadline()
      throws Exception {
    delayedPredecessorAfterHandoff(true);
  }

  @Test
  void delayedActualPredecessorFinalRefusesAfterNativeHandoffWithinOriginalDeadline()
      throws Exception {
    delayedPredecessorAfterHandoff(false);
  }

  @Test
  void missingCommittedServingEvidenceCannotReleaseOriginalOwnerCustody() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      commitHistoricalSplitReceipt(actual);
    }
    assertThat(admin.del(ascii(installation.sessionKey()))).isEqualTo(1L);
    String rows = sqlImage();
    var physical = physicalImage();
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .closeOriginalAttempt(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .retainCommittedBeforeCleanup(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> replaceRegionOwner("successor-with-missing-committed-evidence"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(physicalImage()).isEqualTo(physical);
  }

  @Test
  void ownerMovementBeforeCapacityAuthorizationRefusesOriginalDispatchWithoutMutation()
      throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    replaceRegionOwner("successor-before-capacity-dispatch");
    var account = controlledAccountOpen();
    var issuer = issuerOpen();
    String rows = sqlImage();
    var physical = physicalImage();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var raw = credential.template();
        var bound = raw.bindCapacityExecution(controlledCapacity)) {
      assertThatThrownBy(
              () ->
                  capacityOwner()
                      .authorize(source, bound, account, issuer, controlledCapacity, deadline()))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(physicalImage()).isEqualTo(physical);
  }

  @Test
  void unresolvedOriginalExecutionBlocksOwnerMovementUntilExactClosure() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    admin.pexpire(ascii(regionBinding.installation().grant.key()), 0);
    String rows = sqlImage();
    var physical = physicalImage();
    assertThatThrownBy(
            () ->
                regionBinding
                    .owner()
                    .revalidateOriginalNewFinalBinding(regionBinding.installation(), source))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> replaceRegionOwner("successor-before-closure"))
        .isInstanceOf(IllegalStateException.class);
    // The positive continuation cannot turn an unexecuted/missing final result into proof.
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .retainCommittedBeforeCleanup(
                        controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(physicalImage()).isEqualTo(physical);
    capacityOwner()
        .closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
    var successor = replaceRegionOwner("successor-after-closure");
    assertThat(successor.executorFence().longValueExact())
        .isGreaterThan(controlledCapacity.selectedOwnerFence());
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .disposition())
        .isEqualTo("NOT_EXECUTED_AFTER_AUTHORIZATION");
  }

  private net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus
      resumeAbortedOriginalWithLeaseOwner(
          CanonicalGameplayRegionOwnerTransferCoordinator transfer,
          CanonicalGameplayRegionLeaseOwner.Installation original) {
    var resumed =
        CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            original,
            sources.resolver,
            transfer,
            deadline());
    assertThat(resumed.evidence).isNull();
    return resumed.status;
  }

  private net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus
      installAbortedTransferSuccessor(
          CanonicalGameplayRegionOwnerTransferCoordinator transfer,
          CanonicalGameplayRegionLeaseOwner.Installation original,
          UUID transferId,
          Clock transferClock) {
    var identity =
        new net.firedevops.firemud.common.runtime.RuntimeIdentity(
            "game-session-service",
            "aborted-original-successor",
            "localhost",
            transferClock.instant(),
            "test",
            "test",
            "controlled-test-image");
    var leases = new CanonicalGameplayRegionLeaseRedisOwner(ownerClient, ownerUri, catalog);
    var grant =
        leases.acquire(
            original.source, original.status.regionEpoch(), Duration.ofSeconds(30), identity);
    leases.requireGrantScope(grant, original.source, original.status.regionEpoch(), identity);
    var preparation =
        new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
            .findTimelinePreparation(original.source)
            .orElseThrow();
    leases.verifyInitializedTimeline(grant, preparation);
    leases.requireCurrent(grant);
    var installed =
        transfer.install(
            transferId,
            original.source,
            original.status,
            grant.tokenCorrelation(),
            identity.service(),
            identity.serviceInstanceId(),
            transferClock.millis() + 5000L);
    leases.requireCurrent(grant);
    return installed;
  }

  private void retainedTransferTamperBlocks(String fault) throws Exception {
    repairAccountMember();
    bridge(true, 1, 1_048_576L);
    var original = regionBinding.installation();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    byte[] key = ascii(capacityKey(tags.assignmentForTransition(fixture.transitionId())));
    byte[] beforeFence = admin.dump(key);
    transfer.advance(original.source, original.status, deadline());
    if (fault.equals("ttl")) admin.pexpire(key, 10000);
    else if (fault.equals("type")) {
      byte[][] keysToDelete = {key};
      admin.del(keysToDelete);
      admin.set(key, ascii("wrong-type"));
    } else if (fault.equals("restored-authorization")) {
      assertThat(beforeFence).isNotNull();
      assertThat(admin.hget(key, ascii("state"))).containsExactly(ascii("FENCED_INDETERMINATE"));
      admin.restore(key, beforeFence, RestoreArgs.Builder.ttl(0L).replace());
      assertThat(admin.hget(key, ascii("state"))).containsExactly(ascii("AUTHORIZED"));
    } else admin.hset(key, ascii("binding"), ascii("{}"));
    byte[] physical =
        CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key);
    String retained = sqlImage();
    admin.pexpire(ascii(original.grant.key()), 0);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
                    fixture.dsl(),
                    ownerClient,
                    ownerUri,
                    catalog,
                    original,
                    sources.resolver,
                    transfer,
                    deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key))
        .containsExactly(physical);
    assertThat(sqlImage()).isEqualTo(retained);
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
                .find(original.source)
                .orElseThrow()
                .executorFence())
        .isEqualTo(original.status.executorFence());
  }

  private static java.sql.Connection failDispatchCommitReplyAfterMarker(
      java.sql.Connection actual,
      javax.sql.DataSource independentDataSource,
      String executionDigest,
      java.util.concurrent.atomic.AtomicBoolean stopped)
      throws java.sql.SQLException {
    try {
      return (java.sql.Connection)
          java.lang.reflect.Proxy.newProxyInstance(
              java.sql.Connection.class.getClassLoader(),
              new Class<?>[] {java.sql.Connection.class},
              (proxy, method, values) -> {
                Object result;
                try {
                  result = method.invoke(actual, values);
                } catch (java.lang.reflect.InvocationTargetException failure) {
                  throw failure.getCause();
                }
                if (method.getName().equals("commit") && !stopped.get()) {
                  try (var independent = independentDataSource.getConnection();
                      var check =
                          independent.prepareStatement(
                              "SELECT status FROM game_session_original_new_capacity_execution WHERE execution_digest=?")) {
                    check.setString(1, executionDigest);
                    try (var rows = check.executeQuery()) {
                      if (rows.next()
                          && "INSTALL_DISPATCHED".equals(rows.getString(1))
                          && stopped.compareAndSet(false, true)) {
                        throw new java.sql.SQLException(
                            "Controlled lost INSTALL_DISPATCHED commit reply");
                      }
                    }
                  }
                }
                return result;
              });
    } catch (RuntimeException | Error failure) {
      try {
        actual.close();
      } catch (java.sql.SQLException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  private void delayedPredecessorAfterHandoff(boolean installAction) throws Exception {
    repairAccountMember();
    bridge(!installAction);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    String oldSha =
        admin.scriptLoad(
            CanonicalGameplayOriginalNewFinalAdmissionLuaIntegrationTest
                .predecessorLuaForOwnerProof());
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var redisReply = new java.util.concurrent.CompletableFuture<Long>();
    var dispatchedAt = new java.util.concurrent.atomic.AtomicLong();
    String action = installAction ? "INSTALL" : "FINAL";
    RedisClient delayed = mock(RedisClient.class);
    when(delayed.connect(ByteArrayCodec.INSTANCE))
        .thenAnswer(
            ignored ->
                delayedConnection(
                    ownerClient.connect(ByteArrayCodec.INSTANCE),
                    oldSha,
                    action,
                    entered,
                    release,
                    calls,
                    redisReply,
                    dispatchedAt));
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(controlledCapacity)) {
      var account = controlledAccountOpen();
      var issuer = issuerOpen();
      long frozenDeadline = CLOCK.millis() + 5000L;
      var old =
          java.util.concurrent.CompletableFuture.runAsync(
              () -> {
                if (installAction) {
                  new CanonicalGameplayOriginalNewCapacityExecutionOwner(
                          fixture.dsl(), delayed, catalog, CLOCK, tags)
                      .authorize(
                          source, template, account, issuer, controlledCapacity, frozenDeadline);
                } else {
                  var owner =
                      new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                          delayed,
                          catalog,
                          CLOCK,
                          tags,
                          () -> delayed.connect(ByteArrayCodec.INSTANCE),
                          () -> frozenDeadline - CLOCK.millis(),
                          java.util.concurrent.CompletableFuture::join);
                  try (var ignored =
                      owner.finalizeOriginalNewOnce(
                          source, template, account, issuer, controlledCapacity, frozenDeadline)) {
                    throw new AssertionError(
                        "Delayed old FINAL must not produce an admission receipt");
                  }
                }
              });
      try {
        assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var original = regionBinding.installation();
        admin.pexpire(ascii(original.grant.key()), 0);
        var successor =
            CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
                fixture.dsl(),
                ownerClient,
                ownerUri,
                catalog,
                original,
                sources.resolver,
                transfer,
                frozenDeadline);
        assertThat(successor.status.executorFence()).isGreaterThan(original.status.executorFence());
        assertThat(CLOCK.millis()).isLessThan(frozenDeadline);
        var fencedPhysical = physicalImage();
        release.countDown();
        assertThat(redisReply.get(2, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(-1L);
        assertThat(dispatchedAt.get()).isPositive().isLessThan(frozenDeadline);
        assertThat(CLOCK.millis()).isLessThan(frozenDeadline);
        assertThatThrownBy(() -> old.get(2, java.util.concurrent.TimeUnit.SECONDS))
            .isInstanceOf(java.util.concurrent.ExecutionException.class)
            .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(calls.get()).isOne();
        assertThat(physicalImage()).isEqualTo(fencedPhysical);
        assertThat(admin.get(ascii(installation.sessionKey())))
            .containsExactly(source.candidate().canonicalBytes());
        assertThat(
                admin.hget(
                    ascii(capacityKey(tags.assignmentForTransition(fixture.transitionId()))),
                    ascii("state")))
            .containsExactly(ascii("FENCED_INDETERMINATE"));
      } finally {
        release.countDown();
        try {
          old.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException expected) {
          /* already asserted failure */
        }
      }
    }
  }

  @Test
  void frozenTransferResumesAfterAdvanceAndInstallFailuresWithTheSameLiveGrant() throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var failFenceReceipt = new java.util.concurrent.atomic.AtomicBoolean(true);
    var failInstallCompletion = new java.util.concurrent.atomic.AtomicBoolean(true);
    var observer =
        new org.jooq.impl.DefaultExecuteListener() {
          @Override
          public void executeStart(org.jooq.ExecuteContext context) {
            String statement = context.sql();
            if (statement == null) return;
            if (statement.contains("UPDATE game_session_region_owner_transfer_item")
                && statement.contains("SET fence_readback=?")
                && failFenceReceipt.compareAndSet(true, false)) {
              throw new IllegalStateException("Controlled failure after successor fence CAS");
            }
            if (statement.contains("UPDATE game_session_region_owner_transfer")
                && statement.contains("SET state='COMPLETED'")
                && failInstallCompletion.compareAndSet(true, false)) {
              throw new IllegalStateException(
                  "Controlled failure before transfer completion commit");
            }
          }
        };
    var observedSql =
        org.jooq.impl.DSL.using(fixture.dsl().configuration().deriveAppending(observer));
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(observedSql, CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            observedSql, CLOCK, repository, capacityOwner(), receiver);
    var identity =
        new net.firedevops.firemud.common.runtime.RuntimeIdentity(
            "game-session-service",
            "final-owner-handoff",
            "localhost",
            CLOCK.instant(),
            "test",
            "test",
            "controlled-test-image");
    CanonicalGameplayRegionIncarnationSource incarnation =
        scope -> {
          assertThat(scope).isEqualTo(original.incarnation.scope());
          return original.incarnation;
        };
    var successor =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            incarnation,
            sources.resolver,
            identity,
            java.time.Duration.ofSeconds(30),
            CLOCK);
    admin.pexpire(ascii(original.grant.key()), 0);

    var frozenFailure =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.FrozenTransferUnconfirmed.class,
            () ->
                successor.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()));
    assertThat(failFenceReceipt).isFalse();
    var frozen = fixture.dsl().fetchSingle("SELECT * FROM game_session_region_owner_transfer");
    UUID transferId = frozen.get("transfer_id", UUID.class);
    assertThat(frozen.get("state", String.class)).isEqualTo("FROZEN");
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT fence_readback FROM game_session_region_owner_transfer_item WHERE transfer_id=? ORDER BY target_ordinal LIMIT 1",
                    transferId)
                .get(0, byte[].class))
        .isNull();
    String fencedKey = capacityKey(tags.assignmentForTransition(fixture.transitionId()));
    assertThat(admin.hget(ascii(fencedKey), ascii("state")))
        .containsExactly(ascii("FENCED_INDETERMINATE"));
    String frozenSql = sqlImage();
    Map<String, String> frozenPhysical = physicalImage();

    var differentOwner =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            incarnation,
            sources.resolver,
            identity,
            java.time.Duration.ofSeconds(30),
            CLOCK);
    assertThatThrownBy(() -> differentOwner.resumeFrozenTransfer(frozenFailure, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(frozenSql);
    assertThat(physicalImage()).isEqualTo(frozenPhysical);

    sources.withholdWorldRead =
        CanonicalGameplayOriginalNewFinalAdmissionTestFixture.ControlledRegionSources.WorldRead
            .LIFECYCLE;
    long started = System.nanoTime();
    assertThatThrownBy(() -> successor.resumeFrozenTransfer(frozenFailure, CLOCK.millis() + 1500))
        .isInstanceOf(CanonicalGameplayRegionLeaseOwner.FrozenTransferUnconfirmed.class)
        .rootCause()
        .isInstanceOfSatisfying(
            io.grpc.StatusRuntimeException.class,
            timeout ->
                assertThat(timeout.getStatus().getCode())
                    .isEqualTo(io.grpc.Status.Code.DEADLINE_EXCEEDED));
    assertThat(sources.withheldReadEntered.getCount()).isZero();
    assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        .isLessThan(3500);
    assertThat(sqlImage()).isEqualTo(frozenSql);
    assertThat(physicalImage()).isEqualTo(frozenPhysical);
    sources.withholdWorldRead = null;

    var installFailure =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.TransferCompletionUnconfirmed.class,
            () -> successor.resumeFrozenTransfer(frozenFailure, deadline()));
    assertThat(failInstallCompletion).isFalse();
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT state,transfer_id FROM game_session_region_owner_transfer"))
        .satisfies(
            row -> {
              assertThat(row.get("state", String.class)).isEqualTo("FROZEN");
              assertThat(row.get("transfer_id", UUID.class)).isEqualTo(transferId);
            });
    long reservedFence =
        fixture
            .dsl()
            .fetchSingle("SELECT successor_owner_fence FROM game_session_region_owner_transfer")
            .get(0, Long.class);
    var recovered = successor.resumeFrozenTransfer(installFailure, deadline());
    assertThat(recovered.status.executorFence()).isEqualTo(reservedFence);
    assertThat(recovered.grant.tokenCorrelation())
        .isEqualTo(recovered.status.leaseTokenCorrelation());
    var completed =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT state,transfer_id,successor_owner_fence FROM game_session_region_owner_transfer");
    assertThat(completed.get("state", String.class)).isEqualTo("COMPLETED");
    assertThat(completed.get("transfer_id", UUID.class)).isEqualTo(transferId);
    assertThat(completed.get("successor_owner_fence", Long.class)).isEqualTo(reservedFence);
    String completedSql = sqlImage();
    admin.pexpire(ascii(recovered.grant.key()), 0);
    assertThatThrownBy(() -> successor.resumeFrozenTransfer(installFailure, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(completedSql);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(HandoffAbortPhase.class)
  void sameGrantResumesFrozenAndCompletedHandoffOfDefinitivelyAbortedOriginal(
      HandoffAbortPhase abortPhase) throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    String executionKey = capacityKey(assignment);
    var originalCandidateAndIndexes = physicalImage();
    originalCandidateAndIndexes.remove(executionKey);

    var interruptCapture = new java.util.concurrent.atomic.AtomicBoolean(true);
    var interruptConfirmation = new java.util.concurrent.atomic.AtomicBoolean(true);
    var observer =
        new org.jooq.impl.DefaultExecuteListener() {
          @Override
          public void executeStart(org.jooq.ExecuteContext context) {
            String statement = context.sql();
            if (statement == null) return;
            boolean atCaptureBoundary =
                abortPhase == HandoffAbortPhase.AFTER_CAPTURE
                    ? statement.contains("UPDATE game_session_region_owner_transfer_item")
                        && statement.contains("SET fence_readback=?")
                    : statement.contains("INSERT INTO game_session_region_owner_transfer_item");
            if (atCaptureBoundary && interruptCapture.getAndSet(false)) {
              throw new IllegalStateException("Controlled interruption at original capture");
            }
          }
        };
    var observedSql =
        org.jooq.impl.DSL.using(fixture.dsl().configuration().deriveAppending(observer));
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(observedSql, CLOCK, tags);
    var observationSource =
        new CanonicalGameplayRegionOwnerTransferObservationSource() {
          @Override
          public Context readCurrent(UUID transferId, long targetOrdinal) {
            var current = repository.readCurrent(transferId, targetOrdinal);
            if ("COMPLETED".equals(current.controlState())
                && interruptConfirmation.getAndSet(false)) {
              throw new IllegalStateException("Controlled completed observation failure");
            }
            return current;
          }

          @Override
          public CanonicalGameplayTenantGameplayTagRepository.Assignment readAssignment(
              UUID transitionId, CanonicalGameplayTenantGameplayTagRepository sourceTags) {
            return repository.readAssignment(transitionId, sourceTags);
          }
        };
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(observationSource, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            observedSql, CLOCK, repository, capacityOwner(), receiver);
    var identity =
        new net.firedevops.firemud.common.runtime.RuntimeIdentity(
            "game-session-service",
            "aborted-original-successor",
            "localhost",
            CLOCK.instant(),
            "test",
            "test",
            "controlled-test-image");
    var successor =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            scope -> {
              assertThat(scope).isEqualTo(original.incarnation.scope());
              return original.incarnation;
            },
            sources.resolver,
            identity,
            Duration.ofSeconds(30),
            CLOCK);
    byte[] leaseKey = ascii(original.grant.key());
    admin.pexpire(leaseKey, 0);

    var interrupted =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.FrozenTransferUnconfirmed.class,
            () ->
                successor.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()));
    assertThat(interruptCapture).isFalse();
    var frozen = fixture.dsl().fetchSingle("SELECT * FROM game_session_region_owner_transfer");
    UUID transferId = frozen.get("transfer_id", UUID.class);
    assertThat(frozen.get("state", String.class)).isEqualTo("FROZEN");
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM game_session_region_owner_transfer_item WHERE transfer_id=?",
                    transferId)
                .get(0, Long.class))
        .isEqualTo(abortPhase == HandoffAbortPhase.AFTER_CAPTURE ? 1L : 0L);
    byte[] frozenGrant = admin.get(leaseKey);
    assertThat(frozenGrant).isNotEmpty();
    String grantCorrelation =
        CanonicalGameplayIssuerMemberV2Codec.sha256(frozenGrant).substring("sha256:".length());
    assertThat(grantCorrelation).isNotEqualTo(original.grant.tokenCorrelation());

    var abort =
        abortPhase == HandoffAbortPhase.AFTER_INSTALL ? null : fixture.recordOriginalAccountAbort();
    var lostCompletion =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.TransferCompletionUnconfirmed.class,
            () -> successor.resumeFrozenTransfer(interrupted, deadline()));
    assertThat(interruptConfirmation).isFalse();
    var completed = fixture.dsl().fetchSingle("SELECT * FROM game_session_region_owner_transfer");
    assertThat(completed.get("transfer_id", UUID.class)).isEqualTo(transferId);
    assertThat(completed.get("intent_digest", String.class))
        .isEqualTo(frozen.get("intent_digest", String.class));
    assertThat(completed.get("state", String.class)).isEqualTo("COMPLETED");
    assertThat(completed.get("new_lease_correlation", String.class)).isEqualTo(grantCorrelation);
    if (abortPhase == HandoffAbortPhase.AFTER_INSTALL) {
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM game_session_canonical_binding_transition WHERE transition_id=?",
                      original.transitionId)
                  .get(0, String.class))
          .isEqualTo("PROVISIONAL");
      abort = fixture.recordOriginalAccountAbort();
    }
    assertThat(abort).isNotNull();
    assertThat(abort.isAccountAborted()).isTrue();
    var retained =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT execution_digest,canonical_execution,fence_readback FROM game_session_region_owner_transfer_item WHERE transfer_id=?",
                transferId);
    assertThat(retained.get("execution_digest", String.class))
        .isEqualTo(controlledCapacity.digest());
    assertThat(retained.get("canonical_execution", byte[].class))
        .containsExactly(controlledCapacity.canonicalBytes());
    assertThat(retained.get("fence_readback", byte[].class)).isNotEmpty();

    var recovered = successor.resumeCompletedTransfer(lostCompletion, deadline());
    assertThat(recovered.grant.tokenCorrelation()).isEqualTo(grantCorrelation);
    assertThat(recovered.status.leaseTokenCorrelation()).isEqualTo(grantCorrelation);
    assertThat(recovered.status.executorFence())
        .isEqualTo(completed.get("new_owner_fence", BigInteger.class));
    assertThat(recovered.status.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(recovered.evidence).isNull();
    assertThat(admin.get(leaseKey)).containsExactly(frozenGrant);
    assertThat(admin.hget(ascii(executionKey), ascii("state")))
        .containsExactly(ascii("FENCED_INDETERMINATE"));
    assertThat(admin.pttl(ascii(executionKey))).isEqualTo(-1L);

    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template()) {
      assertThatThrownBy(
              () ->
                  finalOwner()
                      .finalizeOriginalNewOnce(
                          source,
                          template,
                          accountOpen,
                          issuerOpen,
                          controlledCapacity,
                          deadline()))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    var transition =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT status,admission_orphan_cleanup_id,original_new_final_session_sha256 FROM game_session_canonical_binding_transition WHERE transition_id=?",
                original.transitionId);
    assertThat(transition.get("status", String.class)).isEqualTo("ABORTED");
    assertThat(transition.get("admission_orphan_cleanup_id", UUID.class))
        .isEqualTo(abort.orphanCleanupId());
    assertThat(transition.get("original_new_final_session_sha256", String.class)).isNull();
    var execution =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT status,terminal_readback FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                controlledCapacity.digest());
    assertThat(execution.get("status", String.class)).isEqualTo("AUTHORIZED");
    assertThat(execution.get("terminal_readback", byte[].class)).isNull();
    var candidateAndIndexes = physicalImage();
    candidateAndIndexes.remove(executionKey);
    assertThat(candidateAndIndexes).isEqualTo(originalCandidateAndIndexes);
  }

  private enum HandoffAbortPhase {
    BEFORE_CAPTURE,
    AFTER_CAPTURE,
    AFTER_INSTALL
  }

  @Test
  void accountSqlFenceBeforeDelayedFinalCannotEstablishPreIntentCommitOrdering() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    // Capture the dispatch inputs before acquisition. This models an already-authorized delayed
    // final call; no OPEN hash is installed or refreshed after the SQL intent commits.
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template()) {
      var barrier = new CanonicalGameplayAccountAdmissionBarrierRepository(fixture.dsl());
      UUID fenceRequest = UUID.randomUUID();
      var intent =
          barrier.beginFenceIntentWithFinalObservation(
              source.candidate().bindingIdentity().accountId(),
              fenceRequest,
              capacityOwner(),
              controlledCapacity.request(),
              controlledCapacity.reservation(),
              deadline());
      assertThat(intent.state().name()).isEqualTo("ACQUISITION_INTENT");
      assertThat(barrier.readFinalObservation(intent.operationId())).isEmpty();
      assertThat(intent.cohortRows())
          .filteredOn(
              row ->
                  row.kind() == CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.TRANSITION
                      && row.sourceKey().equals(fixture.transitionId().toString()))
          .hasSize(1);
      String frozenSql = sqlImage();
      var beforeFinal = physicalImage();
      // The SQL intent is not the Redis CLOSED linearization point. The existing script still
      // accepts these original inputs against the unchanged exact physical OPEN barriers.
      try (var actual =
          finalOwner()
              .finalizeOriginalNewOnce(
                  source, template, accountOpen, issuerOpen, controlledCapacity, deadline())) {
        assertThat(admin.get(ascii(installation.sessionKey())))
            .containsExactly(actual.exactCanonicalBytes());
        assertThat(physicalImage()).isNotEqualTo(beforeFinal);
        assertThat(sqlImage()).isEqualTo(frozenSql);
        var frozen = barrier.readCurrentFenceIntent(intent.operationId());
        assertThat(frozen.inventorySnapshotRevision())
            .isEqualTo(intent.inventorySnapshotRevision());
        assertThat(frozen.cohortDigest()).isEqualTo(intent.cohortDigest());
        assertThat(frozen.cohortRows()).usingRecursiveComparison().isEqualTo(intent.cohortRows());
        // A genuine physical positive and matching preexisting frozen candidate do not prove
        // FINAL preceded SQL acquisition. Preserve all existing admission/recovery refusals.
        assertThatThrownBy(() -> inventory.commitOriginalNewFinalAdmission(actual))
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
            .hasMessage("Account-fence acquisition intent blocks a new binding transition");
        assertThatThrownBy(
                () ->
                    capacityOwner()
                        .retainCommittedBeforeCleanup(
                            controlledCapacity.request(), controlledCapacity.reservation()))
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
        assertThatThrownBy(
                () -> reader().readActualOriginalFinalAdmission(credential.selector(), "test"))
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
            .hasMessage("Original NEW transition is not final-readable");
        assertThat(sqlImage()).isEqualTo(frozenSql);
        assertThat(admin.get(ascii(installation.sessionKey())))
            .containsExactly(actual.exactCanonicalBytes());
        assertThat(
                barrier
                    .beginFenceIntentWithFinalObservation(
                        intent.accountId(),
                        fenceRequest,
                        capacityOwner(),
                        controlledCapacity.request(),
                        controlledCapacity.reservation(),
                        deadline())
                    .operationId())
            .isEqualTo(intent.operationId());
        assertThat(barrier.readFinalObservation(intent.operationId())).isEmpty();
      }
    }
  }

  @Test
  void fenceOwnerDoesNotRetainObservedFinalAfterInventoryRevisionChangesBeforeAcquisition()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      byte[] finalBytes = admin.get(ascii(installation.sessionKey()));
      assertThat(finalBytes).containsExactly(actual.exactCanonicalBytes());
      var accountId = source.candidate().bindingIdentity().accountId();
      UUID requestId = UUID.randomUUID();
      BigInteger observedRevision =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock WHERE singleton_id=1")
              .get(0, java.math.BigDecimal.class)
              .toBigIntegerExact();
      var observed = new AtomicBoolean();
      var displaced = new AtomicBoolean();
      var observer =
          new org.jooq.impl.DefaultExecuteListener() {
            @Override
            public void executeStart(org.jooq.ExecuteContext context) {
              String sql = context.sql();
              if (sql == null
                  || !sql.contains("game_session_canonical_binding_inventory_clock")
                  || !sql.contains("FOR UPDATE")
                  || !displaced.compareAndSet(false, true)) return;
              assertThat(observed).as("exact physical FINAL was observed before locking").isTrue();
              // This is the transaction's acquisition lock, after physical FINAL observation.
              // Advance the shared source revision on an independent committed connection before
              // the locking SELECT reads it.
              fixture
                  .dsl()
                  .execute(
                      "UPDATE game_session_canonical_binding_inventory_clock"
                          + " SET inventory_revision = inventory_revision + 1 WHERE singleton_id = 1");
            }
          };
      var observedSql =
          org.jooq.impl.DSL.using(fixture.dsl().configuration().deriveAppending(observer));
      var barrier = new CanonicalGameplayAccountAdmissionBarrierRepository(observedSql);
      long observationDeadline = deadline();
      var intent =
          barrier.beginFenceIntentWithFinalObservation(
              accountId,
              requestId,
              observingCapacityOwner(observed, observationDeadline),
              controlledCapacity.request(),
              controlledCapacity.reservation(),
              observationDeadline);

      assertThat(displaced).as("inventory revision changed after the FINAL readback").isTrue();
      assertThat(intent.state().name()).isEqualTo("ACQUISITION_INTENT");
      assertThat(intent.inventorySnapshotRevision()).isGreaterThan(observedRevision);
      assertThat(barrier.readFinalObservation(intent.operationId())).isEmpty();
      var transition =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT status, original_new_final_session_sha256 FROM game_session_canonical_binding_transition WHERE transition_id=?",
                  fixture.transitionId());
      assertThat(transition.get("status", String.class)).isEqualTo("PROVISIONAL");
      assertThat(transition.get("original_new_final_session_sha256", String.class)).isNull();
      assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(finalBytes);
    }
  }

  @Test
  void fenceOwnerDoesNotRetainObservedFinalAfterItsDeadlineExpiresBeforeAcquisition()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      byte[] finalBytes = admin.get(ascii(installation.sessionKey()));
      assertThat(finalBytes).containsExactly(actual.exactCanonicalBytes());
      var accountId = source.candidate().bindingIdentity().accountId();
      UUID requestId = UUID.randomUUID();
      long observationDeadline = CLOCK.millis() + 5_000L;
      long expiredAt = observationDeadline + 1L;
      var barrier =
          new CanonicalGameplayAccountAdmissionBarrierRepository(
              fixture.dsl(), Clock.fixed(Instant.ofEpochMilli(expiredAt), ZoneOffset.UTC));
      var observed = new AtomicBoolean();
      var intent =
          barrier.beginFenceIntentWithFinalObservation(
              accountId,
              requestId,
              observingCapacityOwner(observed, observationDeadline),
              controlledCapacity.request(),
              controlledCapacity.reservation(),
              observationDeadline);

      assertThat(observed)
          .as("exact physical FINAL was observed before expiry was checked")
          .isTrue();
      assertThat(intent.state().name()).isEqualTo("ACQUISITION_INTENT");
      assertThat(barrier.readFinalObservation(intent.operationId())).isEmpty();
      var transition =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT status, original_new_final_session_sha256 FROM game_session_canonical_binding_transition WHERE transition_id=?",
                  fixture.transitionId());
      assertThat(transition.get("status", String.class)).isEqualTo("PROVISIONAL");
      assertThat(transition.get("original_new_final_session_sha256", String.class)).isNull();
      assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(finalBytes);
    }
  }

  @Test
  void fenceOwnerRetainsObservedFinalWithoutPromotingFrozenInventory() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      var accountId = source.candidate().bindingIdentity().accountId();
      UUID fenceRequest = UUID.randomUUID();
      var physical = physicalImage();
      var revision =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock WHERE singleton_id=1")
              .get(0, java.math.BigDecimal.class)
              .toBigIntegerExact();
      var barrier = new CanonicalGameplayAccountAdmissionBarrierRepository(fixture.dsl());
      var intent =
          barrier.beginFenceIntentWithFinalObservation(
              accountId,
              fenceRequest,
              capacityOwner(),
              controlledCapacity.request(),
              controlledCapacity.reservation(),
              deadline());
      var recreated = new CanonicalGameplayAccountAdmissionBarrierRepository(fixture.dsl());
      var receipt = recreated.readFinalObservation(intent.operationId()).orElseThrow();
      assertThat(receipt.transitionId()).isEqualTo(fixture.transitionId());
      assertThat(receipt.admissionAttemptId())
          .isEqualTo(controlledCapacity.request().admissionAttemptId());
      assertThat(receipt.executionDigest()).isEqualTo(controlledCapacity.digest());
      assertThat(receipt.candidateDigest()).isEqualTo(source.candidateDigest());
      assertThat(receipt.finalSessionSha256()).isEqualTo(actual.finalSessionSha256());
      assertThat(receipt.admissionAtMillis()).isEqualTo(actual.admissionAtMillis());
      assertThat(receipt.continuityExpiresAtMillis()).isEqualTo(actual.continuityExpiresAtMillis());
      assertThat(receipt.inventorySnapshotRevision()).isEqualTo(revision);
      assertThat(receipt.cohortDigest()).isEqualTo(intent.cohortDigest());
      assertThat(receipt.barrierTargetDigest()).isEqualTo(intent.barrierTargetDigest());
      for (String mutation :
          java.util.List.of(
              "UPDATE game_session_account_fence_final_observation"
                  + " SET inventory_snapshot_revision = inventory_snapshot_revision + 1",
              "DELETE FROM game_session_account_fence_final_observation",
              "TRUNCATE game_session_account_fence_final_observation")) {
        assertThatThrownBy(() -> fixture.dsl().execute(mutation))
            .isInstanceOf(org.jooq.exception.DataAccessException.class)
            .rootCause()
            .isInstanceOfSatisfying(
                org.postgresql.util.PSQLException.class,
                failure -> {
                  assertThat(failure.getSQLState()).isEqualTo("23514");
                  assertThat(failure.getMessage())
                      .contains("Historical FINAL observation is immutable");
                });
        assertThat(recreated.readFinalObservation(intent.operationId())).contains(receipt);
      }
      var transition =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT status, original_new_final_session_sha256 FROM game_session_canonical_binding_transition WHERE transition_id=?",
                  fixture.transitionId());
      assertThat(transition.get("status", String.class)).isEqualTo("PROVISIONAL");
      assertThat(transition.get("original_new_final_session_sha256", String.class)).isNull();
      var frozenSql = sqlImage();
      assertThatThrownBy(
              () ->
                  capacityOwner()
                      .retainCommittedBeforeCleanup(
                          controlledCapacity.request(), controlledCapacity.reservation()))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
      assertThat(recreated.beginFenceIntent(accountId, fenceRequest).cohortDigest())
          .isEqualTo(intent.cohortDigest());
      assertThat(recreated.readFinalObservation(intent.operationId())).contains(receipt);
      assertThat(sqlImage()).isEqualTo(frozenSql);
      assertThat(physicalImage()).isEqualTo(physical);
    }
  }

  private CanonicalGameplayOriginalNewCapacityExecutionOwner observingCapacityOwner(
      AtomicBoolean observed, long deadline) {
    var owner = spy(capacityOwner());
    doAnswer(
            call -> {
              Object actual = call.callRealMethod();
              observed.set(actual != null);
              return actual;
            })
        .when(owner)
        .readFinalBeforeAccountIntent(
            controlledCapacity.request(), controlledCapacity.reservation(), deadline);
    return owner;
  }

  @SuppressWarnings("unchecked")
  private static StatefulRedisConnection<byte[], byte[]> delayedConnection(
      StatefulRedisConnection<byte[], byte[]> actual,
      String predecessorSha,
      String action,
      java.util.concurrent.CountDownLatch entered,
      java.util.concurrent.CountDownLatch release,
      java.util.concurrent.atomic.AtomicInteger calls,
      java.util.concurrent.CompletableFuture<Long> redisReply,
      java.util.concurrent.atomic.AtomicLong dispatchedAt) {
    var commands = actual.async();
    Object delayedCommands =
        java.lang.reflect.Proxy.newProxyInstance(
            io.lettuce.core.api.async.RedisAsyncCommands.class.getClassLoader(),
            new Class<?>[] {io.lettuce.core.api.async.RedisAsyncCommands.class},
            (proxy, method, args) -> {
              if (method.getName().equals("evalsha")
                  && args.length == 4
                  && args[3] instanceof byte[][] values
                  && values.length == 19
                  && action.equals(new String(values[17], StandardCharsets.US_ASCII))) {
                entered.countDown();
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                  throw new AssertionError("Delayed old invocation not released to Redis");
                Object[] original = args.clone();
                original[0] = predecessorSha;
                try {
                  dispatchedAt.set(CLOCK.millis());
                  var reply = (io.lettuce.core.RedisFuture<Long>) method.invoke(commands, original);
                  calls.incrementAndGet();
                  reply.whenComplete(
                      (value, failure) -> {
                        if (failure == null) redisReply.complete(value);
                        else redisReply.completeExceptionally(failure);
                      });
                  return reply;
                } catch (java.lang.reflect.InvocationTargetException failure) {
                  throw failure.getCause();
                }
              }
              try {
                return method.invoke(commands, args);
              } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
    return (StatefulRedisConnection<byte[], byte[]>)
        java.lang.reflect.Proxy.newProxyInstance(
            StatefulRedisConnection.class.getClassLoader(),
            new Class<?>[] {StatefulRedisConnection.class},
            (proxy, method, args) -> {
              if (method.getName().equals("async")) return delayedCommands;
              try {
                return method.invoke(actual, args);
              } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }
}
