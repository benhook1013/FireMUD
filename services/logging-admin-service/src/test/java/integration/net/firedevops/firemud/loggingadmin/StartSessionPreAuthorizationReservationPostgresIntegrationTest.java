package net.firedevops.firemud.loggingadmin;

import static net.firedevops.firemud.loggingadmin.jooq.tables.StartSessionPreAuthorizationReservations.START_SESSION_PRE_AUTHORIZATION_RESERVATIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimState;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Phase;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.State;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.loggingadmin.repository.StartSessionPreAuthorizationReservationRepository;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class StartSessionPreAuthorizationReservationPostgresIntegrationTest {
  private static final long NOW_EPOCH_MILLIS = 1_800_000_000_000L;
  private static final UUID TENANT_ID = UUID.fromString("ffbe29e3-a8d2-4b7d-a046-cf1af57ae904");
  private static final UUID ACTOR_ID = UUID.fromString("4c5b3e92-f60d-4e3d-8b8f-6d89b28495d8");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private DriverManagerDataSource dataSource;
  private DSLContext dsl;
  private StartSessionPreAuthorizationReservationRepository repository;

  @BeforeEach
  void migrateAndCreateRepository() {
    dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .cleanDisabled(false)
        .load()
        .clean();
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    repository = new StartSessionPreAuthorizationReservationRepository(dsl);
  }

  @AfterEach
  void clearAuthenticatedContext() {
    SessionContext.clear();
  }

  @Test
  void concurrentExactAcquiresPersistOneDurableReservationOwner() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple =
        tuple("postgres-concurrent-01", "same action");
    int callerCount = 8;
    ExecutorService executor = Executors.newFixedThreadPool(callerCount);
    CountDownLatch ready = new CountDownLatch(callerCount);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<StartSessionPreAuthorizationReservationRepository.AcquireResult>> futures =
        new ArrayList<>();
    try {
      for (int index = 0; index < callerCount; index++) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent acquire gate timed out");
                  }
                  return acquire(tuple, UUID.randomUUID());
                }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<StartSessionPreAuthorizationReservationRepository.AcquireResult> results =
          new ArrayList<>();
      for (Future<StartSessionPreAuthorizationReservationRepository.AcquireResult> future :
          futures) {
        results.add(future.get(10, TimeUnit.SECONDS));
      }

      assertThat(results)
          .filteredOn(StartSessionPreAuthorizationReservationRepository.AcquireResult::created)
          .hasSize(1);
      assertThat(results)
          .allSatisfy(result -> assertThat(result.snapshot().tuple()).isEqualTo(tuple));
      assertThat(dsl.fetchCount(START_SESSION_PRE_AUTHORIZATION_RESERVATIONS)).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void sameRequestIdWithChangedTupleConflictsAndFullTupleIsPersisted() {
    StartSessionPreAuthorizationReservationTuple original =
        tuple("postgres-conflict-02", "same action");
    acquire(original, UUID.randomUUID());
    StartSessionPreAuthorizationReservationTuple changed =
        tuple("postgres-conflict-02", "changed audit reason");

    assertThatThrownBy(() -> acquire(changed, UUID.randomUUID()))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.IdempotencyConflictException.class);

    String storedTuple =
        dsl.fetchOne(
                "SELECT pre_authorization_tuple_json FROM start_session_pre_authorization_reservations "
                    + "WHERE control_plane_request_id = ?",
                original.controlPlaneRequestId())
            .get(0, String.class);
    assertThat(storedTuple)
        .contains(ACTOR_ID.toString())
        .contains("\"ownerService\":\"game-session-service\"")
        .contains("\"expectedVersion\":{\"presence\":\"ABSENT\"}")
        .contains("\"clientIp\":{\"presence\":\"ABSENT\"}");
  }

  @Test
  void expiryRecoveryFencesStaleOwnerAndCannotInventTerminalOutcome() {
    StartSessionPreAuthorizationReservationTuple tuple =
        tuple("postgres-recovery-03", "recovery fixture");
    UUID originalOwner = UUID.randomUUID();
    repository.acquire(tuple, originalOwner, NOW_EPOCH_MILLIS, NOW_EPOCH_MILLIS + 30_000L);

    var firstTransition =
        repository.markAuthorizationPending(
            tuple, tuple.mutationDigest(), originalOwner, 1L, NOW_EPOCH_MILLIS + 1_000L);
    assertThat(firstTransition.transitioned()).isTrue();
    assertThat(firstTransition.snapshot().state()).isEqualTo(State.AUTHORIZATION_PENDING);
    var duplicateTransition =
        repository.markAuthorizationPending(
            tuple, tuple.mutationDigest(), originalOwner, 1L, NOW_EPOCH_MILLIS + 1_000L);
    assertThat(duplicateTransition.transitioned()).isFalse();

    var expired =
        repository.expireClaim(
            tuple,
            tuple.mutationDigest(),
            originalOwner,
            1L,
            Phase.ACCOUNT_AUTHORIZATION,
            State.AUTHORIZATION_PENDING,
            NOW_EPOCH_MILLIS + 30_000L);
    assertThat(expired.claimState()).isEqualTo(ClaimState.EXPIRED);
    assertThat(expired.claimFence()).isEqualTo(2L);
    assertThat(expired.reservationClaimFence()).isEqualTo(1L);
    assertThat(expired.state()).isEqualTo(State.AUTHORIZATION_PENDING);

    assertThatThrownBy(
            () ->
                repository.renew(
                    tuple,
                    tuple.mutationDigest(),
                    originalOwner,
                    1L,
                    Phase.ACCOUNT_AUTHORIZATION,
                    State.AUTHORIZATION_PENDING,
                    NOW_EPOCH_MILLIS + 30_001L,
                    NOW_EPOCH_MILLIS + 60_001L))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.StaleReservationClaimException.class);

    UUID recoveryOwner = UUID.randomUUID();
    var recovery =
        repository
            .acquireRecoveryClaim(
                tuple, recoveryOwner, NOW_EPOCH_MILLIS + 30_001L, NOW_EPOCH_MILLIS + 60_001L)
            .orElseThrow();
    assertThat(recovery.reservationOwnerId()).isEqualTo(originalOwner);
    assertThat(recovery.snapshot().claimFence()).isEqualTo(3L);
    assertThat(recovery.snapshot().reservationClaimFence()).isEqualTo(1L);
    assertThat(recovery.snapshot().claimState()).isEqualTo(ClaimState.ACTIVE);
    assertThat(recovery.snapshot().state()).isEqualTo(State.AUTHORIZATION_PENDING);
    assertThat(
            repository.acquireRecoveryClaim(
                tuple, UUID.randomUUID(), NOW_EPOCH_MILLIS + 30_002L, NOW_EPOCH_MILLIS + 60_002L))
        .isEmpty();

    var recoveredRecord = repository.findExact(tuple).orElseThrow();
    assertThat(recoveredRecord.phase()).isEqualTo(Phase.ACCOUNT_AUTHORIZATION);
    assertThat(recoveredRecord.state()).isEqualTo(State.AUTHORIZATION_PENDING);
    assertThat(recoveredRecord.reservationClaimFence()).isEqualTo(1L);
    assertThat(recoveredRecord.claimFence()).isEqualTo(3L);
    assertThat(State.values()).containsExactly(State.RESERVED, State.AUTHORIZATION_PENDING);
  }

  @Test
  void lookupRecomputesDigestAndRejectsCorruptedStoredEvidence() {
    StartSessionPreAuthorizationReservationTuple tuple =
        tuple("postgres-integrity-04", "integrity fixture");
    acquire(tuple, UUID.randomUUID());
    dsl.execute(
        "UPDATE start_session_pre_authorization_reservations SET mutation_digest = ? "
            + "WHERE control_plane_request_id = ?",
        "0".repeat(64),
        tuple.controlPlaneRequestId());

    assertThatThrownBy(() -> repository.find(tuple.controlPlaneRequestId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mutationDigest");
  }

  private StartSessionPreAuthorizationReservationRepository.AcquireResult acquire(
      StartSessionPreAuthorizationReservationTuple tuple, UUID ownerId) {
    return repository.acquire(tuple, ownerId, NOW_EPOCH_MILLIS, NOW_EPOCH_MILLIS + 30_000L);
  }

  private static StartSessionPreAuthorizationReservationTuple tuple(
      String requestId, String auditReason) {
    SessionContext.setContext(
        ACTOR_ID.toString(),
        List.of(),
        Map.of(TENANT_ID.toString(), List.of("tenantAdmin")),
        false,
        null,
        null);
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, "world-runtime"),
            new StartSessionOperatorAction.Target(
                29L, UUID.fromString("be78a8de-a113-48c4-a247-39e3505aed8c")),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            auditReason);
    return StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(requestId, action);
  }
}
