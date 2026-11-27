package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.grpc.Context;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import io.lettuce.core.event.command.CommandSucceededEvent;
import io.lettuce.core.protocol.CommandType;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.junit.jupiter.api.Test;
import org.postgresql.jdbc.PgConnection;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Native component composition: actual GS SQL/candidate/account-member/issuer/region/final owners.
 * Account lease, signer/JWKS, physical Account OPEN, World/Entity RPC response DTOs, incarnation,
 * launch and empty legacy cohort are controlled fixtures. Real mTLS clients mint private Entity
 * references; real region owners mint grants and ACKs. This does not exercise Account callbacks,
 * INITIAL protected observation, the full coordinator, external source custody or activation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayOriginalNewCapacityExecutionOwnerIntegrationTest
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

  @Test
  void committedCapacityWithoutExecutionCustodyRemainsIndeterminate() throws Exception {
    repairAccountMember();
    bridge(false);
    authorizeKnownAccountSend(inventory.readOriginalNewFinalAdmissionSource(decision));
    var before = physicalImage();
    assertThat(
            fixture
                .dsl()
                .fetchCount(
                    org.jooq.impl.DSL.table("game_session_original_new_capacity_execution")))
        .isZero();
    assertThatThrownBy(() ->
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() ->
            capacityOwner()
                .closeOriginalAttempt(
                    controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            fixture
                .dsl()
                .fetchCount(
                    org.jooq.impl.DSL.table("game_session_original_new_capacity_execution")))
        .isZero();
    assertThat(physicalImage()).isEqualTo(before);
  }

  @Test
  void deadlineRetiresPostgresDriverAndHikariReplacesBorrowedPoolEntry() throws Exception {
    HikariDataSource pool = new HikariDataSource();
    pool.setPoolName("capacity-retirement-" + UUID.randomUUID());
    pool.setDriverClassName("org.postgresql.Driver");
    pool.setJdbcUrl(POSTGRES.getJdbcUrl());
    pool.setUsername(POSTGRES.getUsername());
    pool.setPassword(POSTGRES.getPassword());
    pool.setMaximumPoolSize(1);
    pool.setMinimumIdle(1);
    pool.setAutoCommit(true);
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(pool), SQLDialect.POSTGRES);
      assertThat(dsl.configuration().connectionProvider())
          .isInstanceOf(DataSourceConnectionProvider.class);
      var configuredDataSource =
          ((DataSourceConnectionProvider) dsl.configuration().connectionProvider()).dataSource();
      assertThat(configuredDataSource).isInstanceOf(TransactionAwareDataSourceProxy.class);
      assertThat(((TransactionAwareDataSourceProxy) configuredDataSource).getTargetDataSource())
          .isSameAs(pool);
      PgConnection initiallyIdleDriver;
      try (Connection connection = pool.getConnection()) {
        initiallyIdleDriver = connection.unwrap(PgConnection.class);
      }
      org.awaitility.Awaitility.await()
          .atMost(2, TimeUnit.SECONDS)
          .untilAsserted(
              () -> assertThat(pool.getHikariPoolMXBean().getIdleConnections()).isEqualTo(1));

      var capturedBudget =
          new AtomicReference<CanonicalGameplayOriginalNewCapacityExecutionBudget>();
      var executingDriver = new AtomicReference<PgConnection>();
      var backendPid = new java.util.concurrent.atomic.AtomicInteger();
      Context deadline = Context.current().withDeadlineAfter(10, TimeUnit.SECONDS, scheduler);
      try (var execution = Executors.newVirtualThreadPerTaskExecutor()) {
        var caller =
            execution.submit(() -> {
              var previous = deadline.attach();
              try {
                assertThatThrownBy(() ->
                        CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
                            CLOCK,
                            Long.MAX_VALUE,
                            budget -> {
                              capturedBudget.set(budget);
                              return budget
                                  .sql(dsl)
                                  .connectionResult(connection -> {
                                    executingDriver.set(connection.unwrap(PgConnection.class));
                                    try (Statement pidStatement = connection.createStatement();
                                        var rows =
                                            pidStatement.executeQuery("SELECT pg_backend_pid()")) {
                                      assertThat(rows.next()).isTrue();
                                      backendPid.set(rows.getInt(1));
                                    }
                                    try (Statement statement = connection.createStatement()) {
                                      statement.execute("SELECT pg_sleep(30)");
                                      return true;
                                    }
                                  });
                            }))
                    .isInstanceOf(
                        CanonicalGameplayOriginalNewCapacityExecutionBudget.BudgetUnavailable
                            .class);
              } finally {
                deadline.detach(previous);
              }
            });
        try {
          org.awaitility.Awaitility.await()
              .atMost(3, TimeUnit.SECONDS)
              .untilAsserted(() -> {
                int pid = backendPid.get();
                assertThat(pid).isPositive();
                try (Connection observer =
                        java.sql.DriverManager.getConnection(
                            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                    var statement =
                        observer.prepareStatement(
                            "SELECT state, query FROM pg_stat_activity WHERE pid = ?")) {
                  statement.setInt(1, pid);
                  try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("state")).isEqualTo("active");
                    assertThat(rows.getString("query")).contains("pg_sleep(30)");
                  }
                }
              });
          caller.get(8, TimeUnit.SECONDS);
        } finally {
          execution.shutdownNow();
        }
      }

      PgConnection retiredDriver = executingDriver.get();
      assertThat(retiredDriver).isNotNull().isSameAs(initiallyIdleDriver);
      var physicallyReleased =
          (CompletableFuture<?>)
              java.util.Objects.requireNonNull(
                  ReflectionTestUtils.getField(capturedBudget.get(), "physicallyReleased"));
      physicallyReleased.get(5, TimeUnit.SECONDS);
      assertThat(retiredDriver.isClosed()).isTrue();
      org.awaitility.Awaitility.await()
          .atMost(5, TimeUnit.SECONDS)
          .untilAsserted(() -> {
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            assertThat(pool.getHikariPoolMXBean().getIdleConnections()).isEqualTo(1);
          });

      try (Connection replacement = pool.getConnection()) {
        PgConnection replacementDriver = replacement.unwrap(PgConnection.class);
        assertThat(replacementDriver).isNotSameAs(retiredDriver);
        assertThat(replacementDriver.isClosed()).isFalse();
        try (Statement statement = replacement.createStatement();
            var rows = statement.executeQuery("SELECT 1")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getInt(1)).isEqualTo(1);
        }
      }
    } finally {
      scheduler.shutdownNow();
      pool.close();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void abortedOriginalRetainsPhysicalClosureAcrossAbortOrdering(boolean closeBeforeAbort)
      throws Exception {
    repairAccountMember();
    bridge();
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
    var terminal =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT status, terminal_readback FROM game_session_original_new_capacity_execution");
    assertThat(terminal.get("status", String.class)).isEqualTo("CLOSED");
    byte[] terminalReadback = terminal.get("terminal_readback", byte[].class);
    assertThat(terminalReadback).containsExactly(proof.canonicalReadback());
    assertThat(terminalReadback.length).isBetween(1, 512);
    byte[] tombstone =
        CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(
            admin,
            ascii(
                capacityKey(tags.assignmentForRetainedCapacityExecution(fixture.transitionId()))));
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
  void physicalImageRetainsAbsoluteExpiryRatherThanOnlyItsPresence() {
    byte[] key = "physical-image-expiry".getBytes(StandardCharsets.US_ASCII);
    admin.set(key, "unchanged".getBytes(StandardCharsets.US_ASCII));
    byte[] persistent =
        CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key);
    long expiresAt = System.currentTimeMillis() + 60_000L;
    admin.pexpireat(key, expiresAt);
    byte[] expiring =
        CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key);
    assertThat(expiring).isNotEqualTo(persistent);
    assertThat(CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key))
        .isEqualTo(expiring);
    admin.pexpireat(key, expiresAt + 600_000L);
    assertThat(CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key))
        .isNotEqualTo(expiring);
    admin.persist(key);
    assertThat(CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key))
        .isEqualTo(persistent);
    admin.del(java.util.List.of(key).toArray(byte[][]::new));
    assertThat(CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key))
        .isNotEqualTo(persistent);
  }

  @Test
  void nullCapacityExecutionRefusesTheCanonicalFinalPathWithoutMutation() throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var account = controlledAccountOpen();
    var issuer = issuerOpen();
    String rows = sqlImage();
    var physical = physicalImage();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template()) {
      assertThatThrownBy(() ->
              new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                      ownerClient, catalog, CLOCK, tags)
                  .finalizeOriginalNewOnce(source, template, account, issuer, null, deadline()))
          .isExactlyInstanceOf(NullPointerException.class)
          .hasMessage("Exact Account capacity execution is required");
    }
    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(physicalImage()).isEqualTo(physical);
  }

  @Test
  void missingCapacityExecutionRefusesTheCanonicalFinalPathWithoutMutation() throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var account = controlledAccountOpen();
    var issuer = issuerOpen();
    byte[] key = ascii(capacityKey(tags.assignmentForTransition(fixture.transitionId())));
    assertThat(admin.type(key)).isEqualTo("none");
    String rows = sqlImage();
    var physical = physicalImage();
    var finalResult = new AtomicReference<Object>();
    var finalCalls = new java.util.concurrent.atomic.AtomicInteger();
    var finalObserver =
        new CommandListener() {
          @Override
          public void commandStarted(CommandStartedEvent event) {
            if (event.getCommand().getType() == CommandType.EVALSHA) finalCalls.incrementAndGet();
          }

          @Override
          public void commandSucceeded(CommandSucceededEvent event) {
            if (event.getCommand().getType() == CommandType.EVALSHA) {
              finalResult.set(event.getCommand().getOutput().get());
            }
          }
        };
    ownerClient.addListener(finalObserver);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var raw = credential.template();
        var bound = raw.bindCapacityExecution(controlledCapacity)) {
      assertThatThrownBy(() ->
              new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                      ownerClient, catalog, CLOCK, tags)
                  .finalizeOriginalNewOnce(
                      source, bound, account, issuer, controlledCapacity, deadline()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Original NEW final admission Redis readback is unavailable");
    } finally {
      ownerClient.removeListener(finalObserver);
    }
    assertThat(finalCalls).as("FINAL Redis EVALSHA dispatch").hasValue(1);
    assertThat(finalResult).as("FINAL missing-state Redis result").hasValue(-1L);
    assertThat(admin.type(key)).isEqualTo("none");
    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(physicalImage()).isEqualTo(physical);
  }

  @Test
  void executionAndTerminalReadbackShareOnePrechargedRecord() throws Exception {
    repairAccountMember();
    bridge(false, 1, 1048576);
    DSLContext sql = fixture.dsl();
    UUID attempt = controlledCapacity.request().admissionAttemptId();
    byte[] canonical = controlledCapacity.canonicalBytes();
    // Pad with JSON whitespace in the SQL boundary fixture. The actual codec output is smaller.
    String insert =
        """
        WITH payload AS (
            SELECT ?::bytea || convert_to(repeat(' ', ?), 'UTF8') AS bytes
        )
        INSERT INTO game_session_original_new_capacity_execution (
            tenant_id, request_id, admission_id, admission_attempt_id, transition_id,
            capacity_rearm_fence, reservation_id, reservation_fence, mutation_digest,
            execution_digest, canonical_execution, selected_owner_id, selected_owner_fence,
            tenant_gameplay_tag, status)
        SELECT c.tenant_id, c.request_id, c.admission_id, c.admission_attempt_id, c.admission_id,
            c.capacity_rearm_fence, ?, ?, c.mutation_digest,
            'sha256:' || encode(sha256(p.bytes), 'hex'), p.bytes, c.selected_owner_id,
            c.selected_owner_fence, t.tenant_gameplay_tag, 'INSTALL_DISPATCHED'
        FROM game_session_capacity_evidence_charge c
        JOIN game_session_canonical_tenant_gameplay_tag t USING (tenant_id)
        CROSS JOIN payload p
        WHERE c.admission_attempt_id = ?
        """;
    assertThatThrownBy(() ->
            sql.execute(
                insert,
                canonical,
                1048065 - canonical.length,
                controlledCapacity.reservation().reservationId(),
                controlledCapacity.reservation().reservationFence(),
                attempt))
        .isInstanceOfSatisfying(
            org.jooq.exception.DataAccessException.class,
            failure -> {
              assertThat(failure.sqlState()).isEqualTo("23514");
              assertThat(failure).hasMessageContaining("gs_capacity_execution_record_bytes");
            });
    assertThat(
            sql.execute(
                insert,
                canonical,
                1048064 - canonical.length,
                controlledCapacity.reservation().reservationId(),
                controlledCapacity.reservation().reservationFence(),
                attempt))
        .isEqualTo(1);

    String close =
        "UPDATE game_session_original_new_capacity_execution"
            + " SET status = 'CLOSED', terminal_readback = ? WHERE admission_attempt_id = ?";
    assertThatThrownBy(() -> sql.execute(close, new byte[513], attempt))
        .isInstanceOfSatisfying(
            org.jooq.exception.DataAccessException.class,
            failure -> {
              assertThat(failure.sqlState()).isEqualTo("23514");
              assertThat(failure).hasMessageContaining("gs_capacity_terminal_record_bytes");
            });
    assertThat(sql.execute(close, new byte[512], attempt)).isEqualTo(1);
    assertThat(
            sql.fetchSingle(
                    "SELECT octet_length(canonical_execution) + octet_length(terminal_readback)"
                        + " FROM game_session_original_new_capacity_execution"
                        + " WHERE admission_attempt_id = ?",
                    attempt)
                .get(0, Integer.class))
        .isEqualTo(1048576);
  }

  @Test
  void activeRecordCeilingRefusesADifferentOriginBelowTheRetainedCeilings() throws Exception {
    repairAccountMember();
    bridge(false, 1, 134217728L, 2);
    var dsl = fixture.dsl();
    var attempt = controlledCapacity.request().admissionAttemptId();
    var tenant = controlledCapacity.request().identity().tenantId();
    var budget =
        dsl.fetchSingle(
            "SELECT maximum_records, maximum_retained_records"
                + " FROM game_session_capacity_evidence_budget WHERE tenant_id = ?",
            tenant);
    assertThat(budget.get(0, Integer.class)).isEqualTo(1);
    assertThat(budget.get(1, Integer.class)).isEqualTo(2);
    UUID otherAttempt = UUID.randomUUID();
    UUID otherRequest = UUID.randomUUID();
    // Isolate the physical active-record trigger. The usual same-origin owner replay would
    // reject before reaching the INSERT, so use a distinct request with the valid candidate.
    assertThatThrownBy(() ->
            dsl.execute(
                "INSERT INTO game_session_capacity_evidence_charge (admission_attempt_id, tenant_id,"
                    + " request_id, admission_id, capacity_rearm_fence, mutation_digest,"
                    + " candidate_digest, selected_owner_id, selected_owner_fence,"
                    + " reserved_binding_bytes, account_send_state, account_send_evidence_expires_at)"
                    + " SELECT ?, tenant_id, ?, admission_id, capacity_rearm_fence, mutation_digest,"
                    + " candidate_digest, selected_owner_id, selected_owner_fence,"
                    + " reserved_binding_bytes, 'UNCLAIMED', clock_timestamp() + INTERVAL '10 seconds' FROM game_session_capacity_evidence_charge"
                    + " WHERE admission_attempt_id = ?",
                otherAttempt,
                otherRequest,
                attempt))
        .isInstanceOfSatisfying(
            org.jooq.exception.DataAccessException.class,
            failure -> {
              assertThat(failure.sqlState()).isEqualTo("23514");
              assertThat(failure).hasMessageContaining("Capacity evidence budget is exhausted");
            });
    assertThat(
            dsl.fetchSingle(
                    "SELECT admission_attempt_id FROM game_session_capacity_evidence_charge"
                        + " WHERE tenant_id = ?",
                    tenant)
                .get(0, UUID.class))
        .isEqualTo(attempt);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"1,134217728", "128,1048576"})
  void lastPrechargedCapacityEvidenceSlotSurvivesFullBudgetAndSameAttemptReplay(
      int maximumRecords, long maximumBindingBytes) throws Exception {
    repairAccountMember();
    bridge(false, maximumRecords, maximumBindingBytes);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var owner = capacityOwner();
    var selected =
        new CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner(
            controlledCapacity.selectedOwnerId(), controlledCapacity.selectedOwnerFence());

    // The initial charge happened in bridge(); the exact replay is idempotent even at capacity.
    owner.precharge(source, controlledCapacity.request(), selected, deadline());
    authorizeKnownAccountSend(source);
    var original = controlledCapacity.request();
    var unsupported =
        new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request(
                original.identity(),
                UUID.randomUUID(),
                original.capacityRearmFence(),
                original.targetNamespace(),
                original.context(),
                original.operation(),
                original.priorShape(),
                new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Shape(
                    2, 0, 0),
                original.evidence(),
                2L,
                null)
            .withDigest();
    unsupported.requireCommittable();
    assertThatThrownBy(() -> owner.precharge(source, unsupported, selected, deadline()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Exact original NEW capacity request required");
    assertThat(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT 1 FROM game_session_capacity_evidence_charge WHERE admission_attempt_id"
                        + " = ?",
                    unsupported.admissionAttemptId()))
        .isNull();
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM game_session_capacity_evidence_charge WHERE tenant_id ="
                        + " ?",
                    controlledCapacity.request().identity().tenantId())
                .get(0, Long.class))
        .isEqualTo(1L);

    UUID distinctAttempt = UUID.randomUUID();
    UUID distinctRequest = UUID.randomUUID();
    // The owner intentionally rejects non-original requests before SQL. Insert a distinct-origin
    // charge directly so this case reaches the SQL byte-budget trigger itself.
    assertThatThrownBy(() ->
            fixture
                .dsl()
                .execute(
                    "INSERT INTO game_session_capacity_evidence_charge"
                        + " (admission_attempt_id, tenant_id, request_id, admission_id,"
                        + " capacity_rearm_fence, mutation_digest, candidate_digest,"
                        + " selected_owner_id, selected_owner_fence, reserved_binding_bytes,"
                        + " account_send_state, account_send_evidence_expires_at)"
                        + " SELECT ?, tenant_id, ?, admission_id, capacity_rearm_fence,"
                        + " mutation_digest, candidate_digest, selected_owner_id,"
                        + " selected_owner_fence, reserved_binding_bytes,"
                        + " 'UNCLAIMED', clock_timestamp() + INTERVAL '5 seconds'"
                        + " FROM game_session_capacity_evidence_charge"
                        + " WHERE admission_attempt_id = ?",
                    distinctAttempt,
                    distinctRequest,
                    original.admissionAttemptId()))
        .isInstanceOfSatisfying(
            org.jooq.exception.DataAccessException.class,
            failure -> {
              assertThat(failure.sqlState()).isEqualTo("23514");
              assertThat(failure).hasMessageContaining("Capacity evidence budget is exhausted");
            });

    var refusedRequest = anotherAttempt(controlledCapacity.request());
    assertThatThrownBy(() -> owner.precharge(source, refusedRequest, selected, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT 1 FROM game_session_capacity_evidence_charge WHERE admission_attempt_id"
                        + " = ?",
                    refusedRequest.admissionAttemptId()))
        .isNull();
    assertThat(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT 1 FROM game_session_original_new_capacity_execution WHERE"
                        + " admission_attempt_id = ?",
                    refusedRequest.admissionAttemptId()))
        .isNull();
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    String refusedKey =
        "session:game:capacity-execution:{"
            + assignment.tenantGameplayTag()
            + "}:"
            + refusedRequest.admissionAttemptId();
    assertThat(admin.type(ascii(refusedKey))).isEqualTo("none");

    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(controlledCapacity)) {
      owner.authorize(
          source, template, controlledAccountOpen(), issuerOpen(), controlledCapacity, deadline());
    }
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE"
                        + " admission_attempt_id = ?",
                    controlledCapacity.request().admissionAttemptId())
                .get(0, String.class))
        .isEqualTo("AUTHORIZED");
    assertThat(admin.type(ascii(capacityKey(assignment)))).isEqualTo("hash");
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM game_session_capacity_evidence_charge WHERE tenant_id ="
                        + " ?",
                    controlledCapacity.request().identity().tenantId())
                .get(0, Long.class))
        .isEqualTo(1L);
  }

  @Test
  void missingExecutionCannotBeReinstalledFromTheRetainedSqlDispatchOrUsedAsNoCommit()
      throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    String key =
        "session:game:capacity-execution:{"
            + assignment.tenantGameplayTag()
            + "}:"
            + controlledCapacity.request().admissionAttemptId();
    admin.del(ascii(key));
    String rows = sqlImage();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(controlledCapacity)) {
      assertThatThrownBy(() ->
              capacityOwner()
                  .authorize(
                      source,
                      template,
                      controlledAccountOpen(),
                      issuerOpen(),
                      controlledCapacity,
                      deadline()))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(() ->
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() ->
            capacityOwner()
                .closeOriginalAttempt(
                    controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(admin.type(ascii(key))).isEqualTo("none");
    assertThat(admin.get(ascii(installation.sessionKey())))
        .containsExactly(source.candidate().canonicalBytes());
    assertThat(sqlImage()).isEqualTo(rows);
  }

  @Test
  void failedInstallConnectRetainsDispatchAndNeverPermitsReinstallationOrNegativeProof()
      throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    authorizeKnownAccountSend(source);
    RedisClient failedClient = mock(RedisClient.class);
    when(failedClient.connect(ByteArrayCodec.INSTANCE))
        .thenThrow(new IllegalStateException("Controlled connection failure"));
    var failedOwner =
        new CanonicalGameplayOriginalNewCapacityExecutionOwner(
            fixture.dsl(), failedClient, catalog, CLOCK, tags);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    String key =
        "session:game:capacity-execution:{"
            + assignment.tenantGameplayTag()
            + "}:"
            + controlledCapacity.request().admissionAttemptId();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(controlledCapacity)) {
      var account = controlledAccountOpen();
      var issuer = issuerOpen();
      assertThatThrownBy(() ->
              failedOwner.authorize(
                  source, template, account, issuer, controlledCapacity, deadline()))
          .isInstanceOf(IllegalStateException.class);
      verify(failedClient, times(1)).connect(ByteArrayCodec.INSTANCE);
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM game_session_original_new_capacity_execution WHERE"
                          + " execution_digest = ?",
                      controlledCapacity.digest())
                  .get(0, String.class))
          .isEqualTo("INSTALL_DISPATCHED");
      String retained = sqlImage();
      assertThatThrownBy(() ->
              capacityOwner()
                  .authorize(source, template, account, issuer, controlledCapacity, deadline()))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() ->
              capacityOwner()
                  .closeOriginalAttempt(
                      controlledCapacity.request(), controlledCapacity.reservation()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(admin.type(ascii(key))).isEqualTo("none");
      assertThat(sqlImage()).isEqualTo(retained);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void concurrentRetainedClosureReplaysAfterServingRetirementOnlyWithPermanentProof(
      boolean keepTombstone) throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var initialReadDone = new java.util.concurrent.CountDownLatch(1);
    var continueFirstClose = new java.util.concurrent.CountDownLatch(1);
    var executionReads = new java.util.concurrent.atomic.AtomicInteger();
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    byte[] tombstoneKey = ascii(capacityKey(assignment));
    var listener =
        new org.jooq.impl.DefaultExecuteListener() {
          @Override
          public void resultEnd(org.jooq.ExecuteContext ctx) {
            String sql = ctx.sql();
            if (sql == null
                || !sql.startsWith("SELECT * FROM game_session_original_new_capacity_execution"))
              return;
            int read = executionReads.incrementAndGet();
            if (read == 2 && !keepTombstone) {
              assertThat(admin.pexpire(tombstoneKey, 0)).isTrue();
              assertThat(admin.type(tombstoneKey)).isEqualTo("none");
            }
            if (read != 1) return;
            initialReadDone.countDown();
            try {
              if (!continueFirstClose.await(4, java.util.concurrent.TimeUnit.SECONDS))
                throw new AssertionError("Exact close race gate was not released");
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new AssertionError(interrupted);
            }
          }
        };
    var waitingDsl =
        org.jooq.impl.DSL.using(
            fixture
                .dsl()
                .configuration()
                .derive(new org.jooq.impl.DefaultExecuteListenerProvider(listener)));
    var waitingOwner =
        new CanonicalGameplayOriginalNewCapacityExecutionOwner(
            waitingDsl, ownerClient, catalog, CLOCK, tags);
    byte[] sessionKey =
        ascii(
            CanonicalGameplaySessionRedisKeys.session(
                assignment, source.candidate().bindingIdentity().bindingRef()));
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          workers.submit(() ->
              waitingOwner.closeOriginalAttempt(
                  controlledCapacity.request(), controlledCapacity.reservation()));
      try {
        assertThat(initialReadDone.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(first.isDone()).isFalse();
        capacityOwner()
            .closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
        var retained =
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation());
        assertThat(retained.disposition()).isEqualTo("NOT_EXECUTED_AFTER_AUTHORIZATION");
        assertThat(admin.pexpire(sessionKey, 0)).isTrue();
        assertThat(admin.type(sessionKey)).isEqualTo("none");
      } finally {
        continueFirstClose.countDown();
      }
      if (keepTombstone) {
        first.get(6, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                    .disposition())
            .isEqualTo("NOT_EXECUTED_AFTER_AUTHORIZATION");
      } else {
        assertThatThrownBy(() -> first.get(6, java.util.concurrent.TimeUnit.SECONDS))
            .isInstanceOf(java.util.concurrent.ExecutionException.class)
            .cause()
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("Original NEW capacity execution is indeterminate")
            .satisfies(failure -> {
              // The second retained SQL read removed this exact Redis execution hash. Refusal
              // must come from reading its missing persistent tombstone, not the budget.
              StackTraceElement refusal = failure.getStackTrace()[1];
              assertThat(refusal.getClassName())
                  .isEqualTo(CanonicalGameplayOriginalNewCapacityExecutionOwner.class.getName());
              assertThat(refusal.getMethodName()).isEqualTo("readExecution");
            });
        assertThat(admin.type(tombstoneKey)).isEqualTo("none");
      }
      assertThat(executionReads.get()).isGreaterThanOrEqualTo(2);
    }
  }

  @Test
  void boundCapacityTemplateFinalizesWhileExecutionRemainsAuthorized() throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    authorizeKnownAccountSend(source);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(controlledCapacity)) {
      capacityOwner()
          .authorize(
              source,
              template,
              controlledAccountOpen(),
              issuerOpen(),
              controlledCapacity,
              deadline());
      assertThat(
              finalOwner()
                  .finalizeOriginalNewOnce(
                      source,
                      template,
                      controlledAccountOpen(),
                      issuerOpen(),
                      controlledCapacity,
                      deadline()))
          .isNotNull();
    }
  }

  @Test
  void recreatedCapacityOwnerClosesExactAttemptAndRejectsDelayedFinalCas() throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    authorizeKnownAccountSend(source);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(controlledCapacity)) {
      var authorized = capacityOwner();
      authorized.authorize(
          source, template, controlledAccountOpen(), issuerOpen(), controlledCapacity, deadline());
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM game_session_original_new_capacity_execution"
                          + " WHERE execution_digest = ?",
                      controlledCapacity.digest())
                  .get(0, String.class))
          .isEqualTo("AUTHORIZED");
      authorized.closeOriginalAttempt(
          controlledCapacity.request(), controlledCapacity.reservation());
      var proof =
          authorized.readExact(controlledCapacity.request(), controlledCapacity.reservation());
      assertThat(proof.disposition()).isEqualTo("NOT_EXECUTED_AFTER_AUTHORIZATION");
      assertThat(proof.currentOwnerFence())
          .isGreaterThanOrEqualTo(controlledCapacity.selectedOwnerFence());
      assertThatThrownBy(() ->
              finalOwner()
                  .finalizeOriginalNewOnce(
                      source,
                      template,
                      controlledAccountOpen(),
                      issuerOpen(),
                      controlledCapacity,
                      deadline()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(
              authorized
                  .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                  .canonicalReadback())
          .containsExactly(proof.canonicalReadback());
    }
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    assertThat(
            admin.get(
                ascii(
                    CanonicalGameplaySessionRedisKeys.session(
                        assignment, source.candidate().bindingIdentity().bindingRef()))))
        .containsExactly(source.candidate().canonicalBytes());
  }

  @Test
  void changedExecutionCandidateRefusesBeforeCreatingSqlDispatchCustody() throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var changed =
        new net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution(
            controlledCapacity.request(),
            controlledCapacity.reservation(),
            controlledCapacity.transitionId(),
            "sha256:" + "0".repeat(64),
            controlledCapacity.selectedOwnerId(),
            controlledCapacity.selectedOwnerFence());
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    var sqlBefore = sqlImage();
    var physicalBefore = physicalImage();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template()) {
      assertThatThrownBy(() ->
              capacityOwner()
                  .authorize(source, template, accountOpen, issuerOpen, changed, deadline()))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(sqlImage()).isEqualTo(sqlBefore);
    assertThat(physicalImage()).isEqualTo(physicalBefore);
  }

  @Test
  void invalidInstallInputsRefuseBeforeCreatingSqlDispatchCustody() throws Exception {
    repairAccountMember();
    bridge(false);
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    String sqlBefore = sqlImage();
    Map<String, String> physicalBefore = physicalImage();
    String capacityKey = capacityKey(assignment);

    assertThatThrownBy(() ->
            capacityOwner()
                .authorize(source, null, accountOpen, issuerOpen, controlledCapacity, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE"
                        + " execution_digest = ?",
                    controlledCapacity.digest()))
        .isNull();
    assertThat(admin.type(ascii(capacityKey))).isEqualTo("none");
    assertThat(sqlImage()).isEqualTo(sqlBefore);
    assertThat(physicalImage()).isEqualTo(physicalBefore);

    var mismatchedTemplateExecution =
        new net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution(
            controlledCapacity.request(),
            controlledCapacity.reservation(),
            controlledCapacity.transitionId(),
            controlledCapacity.candidateDigest(),
            controlledCapacity.selectedOwnerId() + "-other",
            controlledCapacity.selectedOwnerFence());
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var mismatchedTemplate = template.bindCapacityExecution(mismatchedTemplateExecution)) {
      assertThatThrownBy(() ->
              capacityOwner()
                  .authorize(
                      source,
                      mismatchedTemplate,
                      accountOpen,
                      issuerOpen,
                      controlledCapacity,
                      deadline()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT status FROM game_session_original_new_capacity_execution WHERE"
                        + " execution_digest = ?",
                    controlledCapacity.digest()))
        .isNull();
    assertThat(admin.type(ascii(capacityKey))).isEqualTo("none");
    assertThat(sqlImage()).isEqualTo(sqlBefore);
    assertThat(physicalImage()).isEqualTo(physicalBefore);
  }
}
