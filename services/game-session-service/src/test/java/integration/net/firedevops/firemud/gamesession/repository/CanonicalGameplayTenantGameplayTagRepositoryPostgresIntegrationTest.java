package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class CanonicalGameplayTenantGameplayTagRepositoryPostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String DIGEST = "sha256:" + "0".repeat(64);

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @org.junit.jupiter.api.Test
  void serializesFirstAllocationPreservesRestartMappingAndRejectsGlobalCrossVersionCollision()
      throws Exception {
    String schema = "gs_tenant_tag_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
      DSLContext dsl = dslForSchema(dataSource, schema);

      UUID tenantA = UUID.randomUUID();
      UUID tenantB = UUID.randomUUID();
      var targetA =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource, tenantA);
      var targetB =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource, tenantB);
      CanonicalGameplayBindingInventoryRepositoryIntegrationTest
          .seedSyntheticVerifiedLegacyDisposition(dsl);
      PreparedCandidate candidateA = prepareCandidate(dsl, targetA);
      PreparedCandidate candidateB = prepareCandidate(dsl, targetB);

      CanonicalGameplayTenantGameplayTagRepository versionOne =
          new CanonicalGameplayTenantGameplayTagRepository(dsl, targetA.targetNamespace(), 1);
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService executor = Executors.newFixedThreadPool(2);
      CanonicalGameplayTenantGameplayTagRepository.Assignment first;
      CanonicalGameplayTenantGameplayTagRepository.Assignment second;
      try {
        Future<CanonicalGameplayTenantGameplayTagRepository.Assignment> firstFuture =
            executor.submit(
                () -> {
                  ready.countDown();
                  await(start);
                  return versionOne.assignmentForTransition(candidateA.transitionId());
                });
        Future<CanonicalGameplayTenantGameplayTagRepository.Assignment> secondFuture =
            executor.submit(
                () -> {
                  ready.countDown();
                  await(start);
                  return versionOne.assignmentForTransition(candidateA.transitionId());
                });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        first = firstFuture.get(30, TimeUnit.SECONDS);
        second = secondFuture.get(30, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
      }

      assertThat(first.tenantGameplayTag()).isEqualTo(second.tenantGameplayTag());
      assertThat(first.allocationVersion()).isEqualTo(1);
      assertThat(first.bindingRef()).isEqualTo(candidateA.bindingRef());
      assertThat(first.tenantId()).isEqualTo(tenantA);

      // A changed configured allocation version only applies to future mappings.
      var restartedVersionTwo =
          new CanonicalGameplayTenantGameplayTagRepository(dsl, targetA.targetNamespace(), 2)
              .assignmentForTransition(candidateA.transitionId());
      assertThat(restartedVersionTwo.tenantGameplayTag()).isEqualTo(first.tenantGameplayTag());
      assertThat(restartedVersionTwo.allocationVersion()).isEqualTo(1);

      String expectedSessionKey =
          "session:game:{"
              + first.tenantGameplayTag()
              + "}:"
              + targetA.gameInstanceUuid()
              + ":"
              + candidateA.sessionId();
      assertThat(CanonicalGameplaySessionRedisKeys.session(first, candidateA.bindingRef()))
          .isEqualTo(expectedSessionKey);
      assertThat(CanonicalGameplaySessionRedisKeys.characterIndex(first, candidateA.bindingRef()))
          .isEqualTo(
              "session:game:index:character:{"
                  + first.tenantGameplayTag()
                  + "}:"
                  + targetA.playableStateNamespaceId()
                  + ":"
                  + candidateA.characterId());
      assertThat(
              CanonicalGameplaySessionRedisKeys.accountTenantIndex(first, candidateA.bindingRef()))
          .isEqualTo(
              "session:game:index:account-tenant:{"
                  + first.tenantGameplayTag()
                  + "}:"
                  + candidateA.accountId());
      assertThat(CanonicalGameplaySessionRedisKeys.tenantIndex(first, candidateA.bindingRef()))
          .isEqualTo("session:game:index:tenant:{" + first.tenantGameplayTag() + "}");

      CanonicalGameplayBindingRef foreignBindingRef =
          CanonicalGameplayBindingRef.of(
              tenantB, targetB.gameInstanceUuid(), candidateB.sessionId());
      assertThatThrownBy(() -> CanonicalGameplaySessionRedisKeys.session(first, foreignBindingRef))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("persisted tenant assignment");
      assertThatThrownBy(
              () ->
                  new CanonicalGameplayTenantGameplayTagRepository(
                          dsl, targetB.targetNamespace(), 2)
                      .assignmentForTransition(candidateA.transitionId()))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("configured Game Session workload");

      // Simulate a future-version candidate colliding with the immutable version-one owner.
      assertThatThrownBy(
              () ->
                  new CanonicalGameplayTenantGameplayTagRepository(
                          dsl, targetB.targetNamespace(), 2)
                      .assignmentForTransitionWithCandidate(
                          candidateB.transitionId(), 2, first.tenantGameplayTag()))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("already owned by a different tenant");
      assertThat(
              dsl.fetchOne(
                  "SELECT tenant_id FROM game_session_canonical_tenant_gameplay_tag "
                      + "WHERE tenant_id = ?",
                  tenantB))
          .isNull();
      var assignmentB =
          new CanonicalGameplayTenantGameplayTagRepository(dsl, targetB.targetNamespace(), 2)
              .assignmentForTransition(candidateB.transitionId());
      assertThat(assignmentB.tenantGameplayTag()).isNotEqualTo(first.tenantGameplayTag());
      assertThat(assignmentB.allocationVersion()).isEqualTo(2);
      assertThat(
              dsl.fetchSingle(
                      "SELECT count(*) AS row_count FROM "
                          + "game_session_canonical_tenant_gameplay_tag")
                  .get("row_count", Long.class))
          .isEqualTo(2L);
      assertThat(
              dsl.fetchSingle(
                      "SELECT allocation_version, tenant_gameplay_tag FROM "
                          + "game_session_canonical_tenant_gameplay_tag WHERE tenant_id = ?",
                      tenantA)
                  .get("tenant_gameplay_tag", String.class))
          .isEqualTo(first.tenantGameplayTag());

      // The V1027 row identifies a required target only. Its returned key is not an installation
      // receipt or admission decision.
      UUID intentOperationId = UUID.randomUUID();
      seedCurrentBarrierTarget(
          dsl,
          candidateA.accountId(),
          tenantA,
          targetA.playableStateNamespaceId(),
          intentOperationId);
      var barrierTarget = versionOne.assignmentForBarrierTarget(intentOperationId, 0L);
      assertThat(CanonicalGameplaySessionRedisKeys.accountAdmissionBarrier(barrierTarget))
          .isEqualTo(
              "session:game:account-admission:{"
                  + first.tenantGameplayTag()
                  + "}:"
                  + candidateA.accountId()
                  + ":"
                  + targetA.playableStateNamespaceId());
      assertThat(barrierTarget.admissionFenceIntentOperationId()).isEqualTo(intentOperationId);
    } finally {
      dropSchema(schema);
    }
  }

  @org.junit.jupiter.api.Test
  void barrierTargetAssignmentLocksControlBeforeIntentAndTarget() throws Exception {
    String schema = "gs_tenant_tag_lock_order_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
      DSLContext dsl = dslForSchema(dataSource, schema);

      UUID tenantId = UUID.randomUUID();
      var runtime =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource, tenantId);
      CanonicalGameplayBindingInventoryRepositoryIntegrationTest
          .seedSyntheticVerifiedLegacyDisposition(dsl);
      PreparedCandidate candidate = prepareCandidate(dsl, runtime);
      var expected =
          new CanonicalGameplayTenantGameplayTagRepository(dsl, runtime.targetNamespace(), 1)
              .assignmentForTransition(candidate.transitionId());

      UUID operationId = UUID.randomUUID();
      seedCurrentBarrierTarget(
          dsl, candidate.accountId(), tenantId, runtime.playableStateNamespaceId(), operationId);

      String holderApplicationName =
          "gs_tenant_tag_holder_" + UUID.randomUUID().toString().replace("-", "");
      String assignmentApplicationName =
          "gs_tenant_tag_assignment_" + UUID.randomUUID().toString().replace("-", "");
      DSLContext holderDsl = dslForSchema(dataSource(schema, holderApplicationName), schema);
      DSLContext assignmentDsl =
          dslForSchema(dataSource(schema, assignmentApplicationName), schema);

      CountDownLatch controlRowLocked = new CountDownLatch(1);
      CountDownLatch allowIntentAndTargetLocks = new CountDownLatch(1);
      CountDownLatch intentAndTargetLocked = new CountDownLatch(1);
      CountDownLatch releaseControl = new CountDownLatch(1);
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        Future<?> controlOwner =
            executor.submit(
                () ->
                    holderDsl.transactionResult(
                        configuration -> {
                          DSLContext transaction = DSL.using(configuration);
                          if (transaction.fetchOne(
                                  "SELECT account_id FROM"
                                      + " game_session_canonical_account_coverage_control"
                                      + " WHERE account_id = ? FOR UPDATE",
                                  candidate.accountId())
                              == null) {
                            throw new IllegalStateException(
                                "Current account control row is missing");
                          }
                          controlRowLocked.countDown();
                          await(allowIntentAndTargetLocks);
                          if (transaction.fetchOne(
                                  "SELECT operation_id FROM"
                                      + " game_session_canonical_account_admission_fence_intent"
                                      + " WHERE operation_id = ? AND intent_state ="
                                      + " 'ACQUISITION_INTENT' FOR UPDATE NOWAIT",
                                  operationId)
                              == null) {
                            throw new IllegalStateException(
                                "Current acquisition intent is missing");
                          }
                          if (transaction.fetchOne(
                                  "SELECT operation_id FROM"
                                      + " game_session_canonical_account_admission_fence_intent_barrier_target"
                                      + " WHERE operation_id = ? AND target_ordinal = 0"
                                      + " AND target_state = 'REQUIRED' FOR UPDATE NOWAIT",
                                  operationId)
                              == null) {
                            throw new IllegalStateException("Current barrier target is missing");
                          }
                          intentAndTargetLocked.countDown();
                          await(releaseControl);
                          return Boolean.TRUE;
                        }));
        try {
          assertThat(controlRowLocked.await(10, TimeUnit.SECONDS)).isTrue();
          Future<CanonicalGameplayTenantGameplayTagRepository.Assignment> assignment =
              executor.submit(
                  () ->
                      new CanonicalGameplayTenantGameplayTagRepository(
                              assignmentDsl, runtime.targetNamespace(), 1)
                          .assignmentForBarrierTarget(operationId, 0L));
          awaitControlLockWait(dsl, assignmentApplicationName);

          // The reader is queued only on control: the control owner can still lock both later rows
          // without waiting or receiving a lock-not-available error.
          allowIntentAndTargetLocks.countDown();
          assertThat(intentAndTargetLocked.await(10, TimeUnit.SECONDS)).isTrue();
          releaseControl.countDown();
          controlOwner.get(10, TimeUnit.SECONDS);

          var actual = assignment.get(10, TimeUnit.SECONDS);
          assertThat(actual.accountId()).isEqualTo(candidate.accountId());
          assertThat(actual.tenantId()).isEqualTo(tenantId);
          assertThat(actual.playableStateNamespaceId())
              .isEqualTo(runtime.playableStateNamespaceId());
          assertThat(actual.tenantGameplayTag()).isEqualTo(expected.tenantGameplayTag());
          assertThat(actual.allocationVersion()).isEqualTo(expected.allocationVersion());
          assertThat(actual.workloadNamespace()).isEqualTo(expected.workloadNamespace());
          assertThat(actual.admissionFenceIntentOperationId()).isEqualTo(operationId);
          assertThat(actual.barrierTargetOrdinal()).isEqualTo(0L);
        } finally {
          allowIntentAndTargetLocks.countDown();
          releaseControl.countDown();
          controlOwner.get(10, TimeUnit.SECONDS);
        }
      } finally {
        executor.shutdownNow();
      }
    } finally {
      dropSchema(schema);
    }
  }

  private static PreparedCandidate prepareCandidate(
      DSLContext dsl, CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget target) {
    UUID transitionId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID characterId = UUID.randomUUID();
    String sessionId = UUID.randomUUID().toString();
    CanonicalGameplayBindingIdentity identity =
        new CanonicalGameplayBindingIdentity(
            accountId,
            target.canonicalTenantId(),
            target.playableStateNamespaceId(),
            characterId,
            "SHARED",
            target.gameInstanceUuid(),
            target.gameInstanceId(),
            sessionId,
            UUID.randomUUID(),
            BigInteger.ONE,
            GameSessionAccountDelegationProfile.ISSUER,
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.valueOf(16),
            BigInteger.valueOf(32));
    CanonicalIssuerReservationFenceEvidence reservation =
        new CanonicalIssuerReservationFenceEvidence(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.ONE);
    CanonicalGameplayBindingTransitionRequest request =
        new CanonicalGameplayBindingTransitionRequest(
            transitionId, UUID.randomUUID(), identity, reservation, null, null);
    new CanonicalGameplayBindingInventoryRepository(dsl).prepare(request);
    return new PreparedCandidate(
        transitionId, accountId, characterId, sessionId, identity.bindingRef());
  }

  private static void seedCurrentBarrierTarget(
      DSLContext dsl, UUID accountId, UUID tenantId, UUID namespaceId, UUID operationId) {
    var clock =
        dsl.fetchSingle(
            "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock "
                + "WHERE singleton_id = 1");
    BigInteger snapshotRevision =
        clock.get("inventory_revision", java.math.BigDecimal.class).toBigIntegerExact();
    UUID requestId = UUID.randomUUID();
    UUID plannedFence = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO game_session_canonical_account_admission_fence_intent ("
            + "operation_id, request_id, account_id, operation_fence, planned_account_admission_fence, "
            + "inventory_snapshot_revision, legacy_cohort_id, legacy_owner_operation_id, "
            + "legacy_writer_fence, legacy_source_snapshot_revision, "
            + "legacy_canonical_inventory_revision, legacy_namespace_index_readback_revision, "
            + "legacy_evidence_digest, intent_state, cohort_row_count, cohort_digest, "
            + "barrier_target_count, barrier_target_digest) "
            + "VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, 1, 1, 1, ?, 'ACQUISITION_INTENT', 0, ?, 1, ?)",
        operationId,
        requestId,
        accountId,
        plannedFence,
        snapshotRevision,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        DIGEST,
        DIGEST,
        DIGEST);
    dsl.execute(
        "INSERT INTO game_session_canonical_account_admission_fence_intent_barrier_target "
            + "(operation_id, account_id, target_ordinal, tenant_id, "
            + "playable_state_namespace_id, target_state) VALUES (?, ?, 0, ?, ?, 'REQUIRED')",
        operationId,
        accountId,
        tenantId,
        namespaceId);
    dsl.execute(
        "UPDATE game_session_canonical_account_coverage_control SET state = 'ACQUISITION_INTENT', "
            + "last_operation_fence = 1, current_operation_id = NULL, current_fence_intent_id = ?, "
            + "current_operation_fence = 1, account_admission_fence = ?, "
            + "historical_account_admission_fence = NULL, coverage_fence = NULL, "
            + "inventory_snapshot_revision = ?, coverage_generation = NULL "
            + "WHERE account_id = ? AND state = 'NO_ACTIVE_ACCOUNT_WIDE_FLOW'",
        operationId,
        plannedFence,
        snapshotRevision,
        accountId);
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    if (!latch.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent tag allocation fixture did not start");
    }
  }

  private static void awaitControlLockWait(DSLContext observer, String applicationName)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Boolean waiting =
          Objects.requireNonNull(
                  observer.fetchOne(
                      "SELECT EXISTS (SELECT 1 FROM pg_stat_activity"
                          + " WHERE pid <> pg_backend_pid() AND application_name = ?"
                          + " AND state = 'active' AND wait_event_type = 'Lock'"
                          + " AND query ILIKE '%game_session_canonical_account_coverage_control%')",
                      applicationName),
                  "account control lock wait query")
              .get(0, Boolean.class);
      if (Boolean.TRUE.equals(waiting)) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Barrier target assignment did not wait on the account control row");
  }

  private static DriverManagerDataSource dataSource(String schema) {
    return dataSource(schema, null);
  }

  private static DriverManagerDataSource dataSource(String schema, String applicationName) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String jdbcUrl = postgres.getJdbcUrl();
    String separator = jdbcUrl.contains("?") ? "&" : "?";
    String applicationNameParameter =
        applicationName == null ? "" : "&ApplicationName=" + applicationName;
    dataSource.setUrl(jdbcUrl + separator + "currentSchema=" + schema + applicationNameParameter);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static DSLContext dslForSchema(DriverManagerDataSource dataSource, String schema) {
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    assertThat(
            dsl.fetchSingle("SELECT current_schema() AS actual_schema")
                .get("actual_schema", String.class))
        .isEqualTo(schema);
    return dsl;
  }

  private static void dropSchema(String schema) {
    try (var connection =
            java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    } catch (Exception failure) {
      throw new IllegalStateException("Failed to dispose tenant tag test schema", failure);
    }
  }

  private record PreparedCandidate(
      UUID transitionId,
      UUID accountId,
      UUID characterId,
      String sessionId,
      CanonicalGameplayBindingRef bindingRef) {}
}
