package net.firedevops.firemud.loggingadmin;

import static net.firedevops.firemud.loggingadmin.jooq.tables.StartSessionPreAuthorizationReservations.START_SESSION_PRE_AUTHORIZATION_RESERVATIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimState;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Phase;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.State;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.loggingadmin.repository.StartSessionPreAuthorizationReservationRepository;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class StartSessionPreAuthorizationReservationPostgresIntegrationTest {
  private static final long NOW_EPOCH_MILLIS = 1_800_000_000_000L;
  private static final Map<String, String> FLYWAY_PLACEHOLDERS = Map.of("serviceSchema", "public");
  private static final UUID TENANT_ID = UUID.fromString("ffbe29e3-a8d2-4b7d-a046-cf1af57ae904");
  private static final UUID ACTOR_ID = UUID.fromString("4c5b3e92-f60d-4e3d-8b8f-6d89b28495d8");
  private static final UUID ISSUANCE_ID = UUID.fromString("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = UUID.fromString("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String WORKLOAD =
      "spiffe://firemud/ns/world-runtime/sa/logging-admin-service";
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);

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
        .placeholders(FLYWAY_PLACEHOLDERS)
        .cleanDisabled(false)
        .load()
        .clean();
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .placeholders(FLYWAY_PLACEHOLDERS)
        .load()
        .migrate();
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
  void upgradeFromV1RetainsExistingLogEventsThroughV2AndV3() {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .placeholders(FLYWAY_PLACEHOLDERS)
        .cleanDisabled(false)
        .target(MigrationVersion.fromVersion("1"))
        .load()
        .clean();
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .placeholders(FLYWAY_PLACEHOLDERS)
        .target(MigrationVersion.fromVersion("1"))
        .load()
        .migrate();

    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    long existingLogEventId =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "INSERT INTO log_events (tenant_id, type, message, timestamp, account_id) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                    42L,
                    "audit",
                    "existing row before reservation migrations",
                    java.sql.Timestamp.valueOf("2026-10-10 12:00:00"),
                    7L),
                "The V1 log row must be inserted before the reservation migrations")
            .get(0, Long.class);

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .placeholders(FLYWAY_PLACEHOLDERS)
        .load()
        .migrate();

    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne("SELECT message FROM log_events WHERE id = ?", existingLogEventId),
                    "The V1 log row must remain after the reservation migrations")
                .get(0, String.class))
        .isEqualTo("existing row before reservation migrations");
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT COUNT(*) FROM start_session_pre_authorization_reservations"),
                    "The V2 reservation table must exist after migration")
                .get(0, Long.class))
        .isZero();
    assertThat(
            dsl.fetch(
                    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .getValues("version", String.class))
        .containsExactly("1", "2", "3");
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
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT pre_authorization_tuple_json FROM start_session_pre_authorization_reservations "
                        + "WHERE control_plane_request_id = ?",
                    original.controlPlaneRequestId()),
                "The original committed reservation must remain readable")
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
    assertThat(State.values())
        .containsExactly(
            State.RESERVED,
            State.AUTHORIZATION_PENDING,
            State.AUTHORIZED,
            State.OWNER_EXECUTION_PENDING);
  }

  @Test
  void exactAuthorizationEnrichmentAndOwnerHandoffAreDurableBeforeDispatch() {
    StartSessionPreAuthorizationReservationTuple tuple =
        tuple("postgres-post-authorization-05", "post-authorization fixture");
    UUID reservationOwner = UUID.fromString("6641ea9e-dc0a-4f5d-80d0-6d87104a2fe7");
    UUID handoffId = UUID.fromString("dcb77e1f-e82a-4e07-a74a-1a4c92d1640c");
    StartSessionPostAuthorizationExecutionTuple postTuple =
        postTuple(tuple, reservationOwner, 1L, FINGERPRINT);
    repository.acquire(tuple, reservationOwner, NOW_EPOCH_MILLIS, NOW_EPOCH_MILLIS + 30_000L);
    repository.markAuthorizationPending(
        tuple, tuple.mutationDigest(), reservationOwner, 1L, NOW_EPOCH_MILLIS + 1_000L);

    var authorized =
        repository.completeAuthorization(
            tuple,
            tuple.mutationDigest(),
            reservationOwner,
            1L,
            reservationOwner,
            1L,
            postTuple.canonicalJson(),
            NOW_EPOCH_MILLIS + 2_000L);
    assertThat(authorized.transitioned()).isTrue();
    assertThat(authorized.snapshot().reservationSnapshot().phase())
        .isEqualTo(Phase.ACCOUNT_AUTHORIZATION);
    assertThat(authorized.snapshot().reservationSnapshot().state()).isEqualTo(State.AUTHORIZED);
    assertThat(authorized.snapshot().reservationSnapshot().reservationClaimFence()).isEqualTo(1L);
    assertThat(authorized.snapshot().postAuthorizationTuple().canonicalBytes())
        .containsExactly(postTuple.canonicalBytes());
    assertThat(authorized.snapshot().handoff()).isNull();

    var duplicateAuthorization =
        repository.completeAuthorization(
            tuple,
            tuple.mutationDigest(),
            reservationOwner,
            1L,
            reservationOwner,
            1L,
            postTuple.canonicalJson(),
            NOW_EPOCH_MILLIS + 3_000L);
    assertThat(duplicateAuthorization.transitioned()).isFalse();

    var firstHandoff =
        repository.beginOwnerExecution(
            tuple,
            tuple.mutationDigest(),
            reservationOwner,
            1L,
            reservationOwner,
            1L,
            postTuple.canonicalJson(),
            handoffId,
            NOW_EPOCH_MILLIS + 4_000L);
    assertThat(firstHandoff.mayDispatch()).isTrue();
    assertThat(firstHandoff.snapshot().reservationSnapshot().phase())
        .isEqualTo(Phase.OWNER_EXECUTION);
    assertThat(firstHandoff.snapshot().reservationSnapshot().state())
        .isEqualTo(State.OWNER_EXECUTION_PENDING);
    assertThat(firstHandoff.snapshot().reservationSnapshot().reservationClaimFence()).isEqualTo(1L);
    assertThat(firstHandoff.snapshot().handoff().handoffId()).isEqualTo(handoffId);

    var duplicateHandoff =
        repository.beginOwnerExecution(
            tuple,
            tuple.mutationDigest(),
            reservationOwner,
            1L,
            reservationOwner,
            1L,
            postTuple.canonicalJson(),
            handoffId,
            NOW_EPOCH_MILLIS + 5_000L);
    assertThat(duplicateHandoff.mayDispatch()).isFalse();
    assertThat(repository.findAuthorizedExact(tuple))
        .isPresent()
        .get()
        .satisfies(
            snapshot -> {
              assertThat(snapshot.postAuthorizationTuple().canonicalBytes())
                  .containsExactly(postTuple.canonicalBytes());
              assertThat(snapshot.handoff()).isEqualTo(firstHandoff.snapshot().handoff());
              assertThat(snapshot.reservationOwnerId()).isEqualTo(reservationOwner);
              assertThat(snapshot.currentClaimOwnerId()).isEqualTo(reservationOwner);
            });

    assertThatThrownBy(
            () ->
                repository.beginOwnerExecution(
                    tuple,
                    tuple.mutationDigest(),
                    reservationOwner,
                    1L,
                    reservationOwner,
                    1L,
                    postTuple.canonicalJson(),
                    UUID.fromString("53d31d97-a8c3-48a1-9815-b3e3228bb6f2"),
                    NOW_EPOCH_MILLIS + 5_500L))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.IdempotencyConflictException.class);
    StartSessionPostAuthorizationExecutionTuple changedFingerprint =
        postTuple(tuple, reservationOwner, 1L, "arfp/v1/other-key/" + "c".repeat(64));
    assertThatThrownBy(
            () ->
                repository.beginOwnerExecution(
                    tuple,
                    tuple.mutationDigest(),
                    reservationOwner,
                    1L,
                    reservationOwner,
                    1L,
                    changedFingerprint.canonicalJson(),
                    handoffId,
                    NOW_EPOCH_MILLIS + 6_000L))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE start_session_pre_authorization_reservations "
                        + "SET post_authorization_execution_tuple_json = ? "
                        + "WHERE control_plane_request_id = ?",
                    postTuple.canonicalJson() + " ",
                    tuple.controlPlaneRequestId()))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM start_session_pre_authorization_reservations "
                        + "WHERE control_plane_request_id = ?",
                    tuple.controlPlaneRequestId()))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () -> dsl.execute("TRUNCATE TABLE start_session_pre_authorization_reservations"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void phaseMismatchExpiredClaimAndChangedTupleCannotEnrichOrDispatch() {
    StartSessionPreAuthorizationReservationTuple tuple =
        tuple("postgres-post-stale-08", "post-authorization stale fixture");
    UUID reservationOwner = UUID.fromString("fb2827eb-6614-4cec-9e4a-e6c653ab827e");
    StartSessionPostAuthorizationExecutionTuple postTuple =
        postTuple(tuple, reservationOwner, 1L, FINGERPRINT);
    repository.acquire(tuple, reservationOwner, NOW_EPOCH_MILLIS, NOW_EPOCH_MILLIS + 30_000L);

    assertThatThrownBy(
            () ->
                repository.completeAuthorization(
                    tuple,
                    tuple.mutationDigest(),
                    reservationOwner,
                    1L,
                    reservationOwner,
                    1L,
                    postTuple.canonicalJson(),
                    NOW_EPOCH_MILLIS + 1_000L))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.StaleReservationClaimException.class);

    repository.markAuthorizationPending(
        tuple, tuple.mutationDigest(), reservationOwner, 1L, NOW_EPOCH_MILLIS + 2_000L);
    assertThatThrownBy(
            () ->
                repository.completeAuthorization(
                    tuple,
                    tuple.mutationDigest(),
                    reservationOwner,
                    1L,
                    reservationOwner,
                    1L,
                    postTuple.canonicalJson(),
                    NOW_EPOCH_MILLIS + 30_000L))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.StaleReservationClaimException.class);

    StartSessionPreAuthorizationReservationTuple changedTuple =
        tuple(tuple.controlPlaneRequestId(), "changed action tuple");
    StartSessionPostAuthorizationExecutionTuple changedPostTuple =
        postTuple(changedTuple, reservationOwner, 1L, FINGERPRINT);
    assertThatThrownBy(
            () ->
                repository.completeAuthorization(
                    tuple,
                    tuple.mutationDigest(),
                    reservationOwner,
                    1L,
                    reservationOwner,
                    1L,
                    changedPostTuple.canonicalJson(),
                    NOW_EPOCH_MILLIS + 3_000L))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                repository.beginOwnerExecution(
                    tuple,
                    tuple.mutationDigest(),
                    reservationOwner,
                    1L,
                    reservationOwner,
                    1L,
                    postTuple.canonicalJson(),
                    UUID.fromString("f80cc7af-327c-44ab-96cc-23a84ff0ab4a"),
                    NOW_EPOCH_MILLIS + 4_000L))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.StaleReservationClaimException.class);
    assertThat(repository.findExact(tuple).orElseThrow().state())
        .isEqualTo(State.AUTHORIZATION_PENDING);
    assertThat(repository.findAuthorizedExact(tuple)).isEmpty();
  }

  @Test
  void recoveryClaimMayEnrichTheOriginalResponseButCannotReplaceOriginalFence() {
    StartSessionPreAuthorizationReservationTuple tuple =
        tuple("postgres-post-recovery-06", "post-authorization recovery fixture");
    UUID reservationOwner = UUID.fromString("06126c36-e190-43cb-932c-0f585ae2f795");
    UUID recoveryOwner = UUID.fromString("66369c32-4c13-4108-8cbc-e8c6103780b2");
    StartSessionPostAuthorizationExecutionTuple postTuple =
        postTuple(tuple, reservationOwner, 1L, FINGERPRINT);
    repository.acquire(tuple, reservationOwner, NOW_EPOCH_MILLIS, NOW_EPOCH_MILLIS + 30_000L);
    repository.markAuthorizationPending(
        tuple, tuple.mutationDigest(), reservationOwner, 1L, NOW_EPOCH_MILLIS + 1_000L);
    repository.expireClaim(
        tuple,
        tuple.mutationDigest(),
        reservationOwner,
        1L,
        Phase.ACCOUNT_AUTHORIZATION,
        State.AUTHORIZATION_PENDING,
        NOW_EPOCH_MILLIS + 30_000L);
    var recovery =
        repository
            .acquireRecoveryClaim(
                tuple, recoveryOwner, NOW_EPOCH_MILLIS + 30_001L, NOW_EPOCH_MILLIS + 60_001L)
            .orElseThrow();

    var enriched =
        repository.completeAuthorization(
            tuple,
            tuple.mutationDigest(),
            reservationOwner,
            1L,
            recoveryOwner,
            recovery.snapshot().claimFence(),
            postTuple.canonicalJson(),
            NOW_EPOCH_MILLIS + 30_002L);
    assertThat(enriched.transitioned()).isTrue();
    assertThat(enriched.snapshot().reservationSnapshot().reservationClaimFence()).isEqualTo(1L);
    assertThat(enriched.snapshot().reservationSnapshot().claimFence()).isEqualTo(3L);
    assertThat(enriched.snapshot().postAuthorizationTuple().reservationOwnerId())
        .isEqualTo(reservationOwner);
    assertThat(enriched.snapshot().postAuthorizationTuple().reservationClaimFence()).isEqualTo(1L);

    var recoveryHandoff =
        repository.beginOwnerExecution(
            tuple,
            tuple.mutationDigest(),
            reservationOwner,
            1L,
            recoveryOwner,
            recovery.snapshot().claimFence(),
            postTuple.canonicalJson(),
            UUID.fromString("d3f21a41-f718-4d82-9438-411377a10852"),
            NOW_EPOCH_MILLIS + 30_004L);
    assertThat(recoveryHandoff.mayDispatch()).isTrue();
    assertThat(recoveryHandoff.snapshot().reservationOwnerId()).isEqualTo(reservationOwner);
    assertThat(recoveryHandoff.snapshot().currentClaimOwnerId()).isEqualTo(recoveryOwner);
    assertThat(recoveryHandoff.snapshot().reservationSnapshot().reservationClaimFence())
        .isEqualTo(1L);
    assertThat(recoveryHandoff.snapshot().reservationSnapshot().claimFence()).isEqualTo(3L);

    assertThatThrownBy(
            () ->
                repository.beginOwnerExecution(
                    tuple,
                    tuple.mutationDigest(),
                    reservationOwner,
                    1L,
                    reservationOwner,
                    1L,
                    postTuple.canonicalJson(),
                    UUID.fromString("d3f21a41-f718-4d82-9438-411377a10852"),
                    NOW_EPOCH_MILLIS + 30_003L))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.StaleReservationClaimException.class);
    assertThatThrownBy(
            () ->
                repository.beginOwnerExecution(
                    tuple,
                    tuple.mutationDigest(),
                    reservationOwner,
                    1L,
                    recoveryOwner,
                    recovery.snapshot().claimFence() - 1L,
                    postTuple.canonicalJson(),
                    UUID.fromString("d3f21a41-f718-4d82-9438-411377a10852"),
                    NOW_EPOCH_MILLIS + 30_005L))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.StaleReservationClaimException.class);
  }

  @Test
  void concurrentExactOwnerHandoffCasReturnsOnlyOneDispatchWinner() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple =
        tuple("postgres-post-concurrent-07", "concurrent handoff fixture");
    UUID reservationOwner = UUID.fromString("8a320d67-5c29-4671-a29e-21ac2bb93c71");
    UUID handoffId = UUID.fromString("7e1604ee-0a58-4fc5-a7ef-302ce18b53d0");
    StartSessionPostAuthorizationExecutionTuple postTuple =
        postTuple(tuple, reservationOwner, 1L, FINGERPRINT);
    repository.acquire(tuple, reservationOwner, NOW_EPOCH_MILLIS, NOW_EPOCH_MILLIS + 30_000L);
    repository.markAuthorizationPending(
        tuple, tuple.mutationDigest(), reservationOwner, 1L, NOW_EPOCH_MILLIS + 1_000L);
    repository.completeAuthorization(
        tuple,
        tuple.mutationDigest(),
        reservationOwner,
        1L,
        reservationOwner,
        1L,
        postTuple.canonicalJson(),
        NOW_EPOCH_MILLIS + 2_000L);

    int contenders = 2;
    ExecutorService executor = Executors.newFixedThreadPool(contenders);
    CountDownLatch ready = new CountDownLatch(contenders);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<StartSessionPreAuthorizationReservationRepository.OwnerExecutionTransitionResult>>
        futures = new ArrayList<>();
    try {
      for (int index = 0; index < contenders; index++) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  try {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                      throw new IllegalStateException("owner handoff gate timed out");
                    }
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                  }
                  return repository.beginOwnerExecution(
                      tuple,
                      tuple.mutationDigest(),
                      reservationOwner,
                      1L,
                      reservationOwner,
                      1L,
                      postTuple.canonicalJson(),
                      handoffId,
                      NOW_EPOCH_MILLIS + 3_000L);
                }));
      }
      boolean allReady;
      try {
        allReady = ready.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw interrupted;
      }
      assertThat(allReady).isTrue();
      start.countDown();
      List<StartSessionPreAuthorizationReservationRepository.OwnerExecutionTransitionResult>
          results = new ArrayList<>();
      try {
        for (Future<
                StartSessionPreAuthorizationReservationRepository.OwnerExecutionTransitionResult>
            future : futures) {
          results.add(future.get(10, TimeUnit.SECONDS));
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw interrupted;
      }
      assertThat(results)
          .filteredOn(
              StartSessionPreAuthorizationReservationRepository.OwnerExecutionTransitionResult
                  ::mayDispatch)
          .hasSize(1);
      assertThat(results).filteredOn(result -> !result.mayDispatch()).hasSize(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void currentClaimReadReturnsExactIssueAndRecoverySnapshotsAndRejectsStaleInputs() {
    String requestId = "13c00ed3-c8e7-4fac-9d0b-129b3a7d09dc";
    StartSessionPreAuthorizationReservationTuple tuple =
        tuple(requestId, "current-claim read fixture");
    UUID originalOwner = UUID.fromString("a5b1f46b-e8e4-420a-b904-226f941d1f19");
    UUID recoveryOwner = UUID.fromString("510c4b0e-fb34-43fb-839a-7cb641d0419e");
    repository.acquire(tuple, originalOwner, NOW_EPOCH_MILLIS, NOW_EPOCH_MILLIS + 30_000L);
    repository.markAuthorizationPending(
        tuple, tuple.mutationDigest(), originalOwner, 1L, NOW_EPOCH_MILLIS + 1_000L);

    var issueRead =
        repository
            .readCurrentClaim(
                tuple, originalOwner, 1L, originalOwner, 1L, NOW_EPOCH_MILLIS + 2_000L)
            .orElseThrow();
    assertThat(issueRead.snapshot().state()).isEqualTo(State.AUTHORIZATION_PENDING);
    assertThat(issueRead.snapshot().tuple().canonicalJson()).isEqualTo(tuple.canonicalJson());
    assertThat(issueRead.snapshot().mutationDigest()).isEqualTo(tuple.mutationDigest());
    assertThat(issueRead.reservationOwnerId()).isEqualTo(originalOwner);
    assertThat(issueRead.currentClaimOwnerId()).isEqualTo(originalOwner);

    repository.expireClaim(
        tuple,
        tuple.mutationDigest(),
        originalOwner,
        1L,
        Phase.ACCOUNT_AUTHORIZATION,
        State.AUTHORIZATION_PENDING,
        NOW_EPOCH_MILLIS + 30_000L);
    var recoveredClaim =
        repository
            .acquireRecoveryClaim(
                tuple, recoveryOwner, NOW_EPOCH_MILLIS + 30_001L, NOW_EPOCH_MILLIS + 60_001L)
            .orElseThrow();
    assertThat(recoveredClaim.snapshot().reservationClaimFence()).isEqualTo(1L);
    assertThat(recoveredClaim.snapshot().claimFence()).isEqualTo(3L);

    var recoveryRead =
        repository
            .readCurrentClaim(
                tuple, originalOwner, 1L, recoveryOwner, 3L, NOW_EPOCH_MILLIS + 30_002L)
            .orElseThrow();
    assertThat(recoveryRead.snapshot().tuple().canonicalJson()).isEqualTo(tuple.canonicalJson());
    assertThat(recoveryRead.snapshot().mutationDigest()).isEqualTo(tuple.mutationDigest());
    assertThat(recoveryRead.reservationOwnerId()).isEqualTo(originalOwner);
    assertThat(recoveryRead.currentClaimOwnerId()).isEqualTo(recoveryOwner);
    assertThat(recoveryRead.snapshot().claimFence()).isEqualTo(3L);

    assertThatThrownBy(
            () ->
                repository.readCurrentClaim(
                    tuple, originalOwner, 1L, originalOwner, 1L, NOW_EPOCH_MILLIS + 30_002L))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.StaleReservationClaimException.class);
    assertThatThrownBy(
            () ->
                repository.readCurrentClaim(
                    tuple, originalOwner, 1L, recoveryOwner, 3L, NOW_EPOCH_MILLIS + 60_001L))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.StaleReservationClaimException.class);

    StartSessionPreAuthorizationReservationTuple tamperedTuple =
        tuple(requestId, "tampered tuple fixture");
    assertThatThrownBy(
            () ->
                repository.readCurrentClaim(
                    tamperedTuple,
                    originalOwner,
                    1L,
                    recoveryOwner,
                    3L,
                    NOW_EPOCH_MILLIS + 30_002L))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.IdempotencyConflictException.class);
  }

  @Test
  void immutableDigestGuardRejectsMutationAndReservationEvidenceRemainsReadable() {
    StartSessionPreAuthorizationReservationTuple tuple =
        tuple("postgres-integrity-04", "integrity fixture");
    acquire(tuple, UUID.randomUUID());
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE start_session_pre_authorization_reservations SET mutation_digest = ? "
                        + "WHERE control_plane_request_id = ?",
                    "0".repeat(64),
                    tuple.controlPlaneRequestId()))
        .isInstanceOf(RuntimeException.class);
    assertThat(repository.find(tuple.controlPlaneRequestId()).orElseThrow().mutationDigest())
        .isEqualTo(tuple.mutationDigest());
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

  private static StartSessionPostAuthorizationExecutionTuple postTuple(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID reservationOwner,
      long reservationFence,
      String fingerprint) {
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
            .fromCanonicalJson(tuple.canonicalJson()),
        WORKLOAD,
        fingerprint,
        reservationOwner,
        reservationFence,
        authorityEvidenceBundle(tuple),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityEvidenceBundle(
      StartSessionPreAuthorizationReservationTuple tuple) {
    String tenantId = TENANT_ID.toString();
    Map<String, Object> value =
        Map.of(
            "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
                Map.of(
                    "scope", Map.of("tenantId", tenantId, "targetNamespace", "world-runtime"),
                    "actionFamily", tuple.actionFamily(),
                    "applicableAccountId", ACTOR_ID.toString(),
                    "applicableTenantId", tenantId),
            "accountProjectionEvidence",
                Map.of(
                    "sourceType",
                    "ACCOUNT",
                    "sourceEvidenceId",
                    "sha256:" + "a".repeat(64),
                    "sourceEvidenceVersion",
                    "17",
                    "projectionStatus",
                    "CURRENT",
                    "evaluatedAt",
                    Instant.ofEpochMilli(NOW_EPOCH_MILLIS - 1_000L).toString(),
                    "expiresAt",
                    Instant.ofEpochMilli(NOW_EPOCH_MILLIS + 300_000L).toString()),
            "issuanceOperationIdentity",
                Map.of(
                    "issuanceOperationId", ISSUANCE_ID.toString(),
                    "controlPlaneRequestId", tuple.controlPlaneRequestId(),
                    "actionFamilyRequestIdentity",
                        Map.of(
                            "requestIdentityKind",
                            "controlPlaneRequestId",
                            "requestId",
                            tuple.controlPlaneRequestId()),
                    "mutationDigest", tuple.mutationDigest()),
            "issuanceKind", "human_operator",
            "authorityTuple",
                Map.of(
                    "issuerAuthGeneration", 1L,
                    "accountAuthorityGeneration", 2L,
                    "tenantAuthorityGeneration", Map.of(tenantId, 3L),
                    "membershipAuthorityGeneration", Map.of(tenantId, 4L),
                    "privateRealmGrantVersions", List.of()),
            "membershipVersion", Map.of(tenantId, 5L),
            "issuanceFence", "23",
            "issuanceEvidence",
                Map.of(
                    "evidenceType",
                    StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                    "actorAccountId",
                    ACTOR_ID.toString(),
                    "controlUiTokenJti",
                    TOKEN_JTI.toString(),
                    "role",
                    "tenantAdmin",
                    "accountGeneration",
                    "2",
                    "tenantGeneration",
                    "3"));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException exception) {
      throw new IllegalStateException("could not encode test authority bundle", exception);
    }
  }
}
