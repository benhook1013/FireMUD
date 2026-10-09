package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
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
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptClaim;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.ReservationDisposition;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.ReservationResult;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.StaleStartSessionOperatorAttemptClaimException;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL proof of the immutable, non-replayable StartSession owner-attempt reservation. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionStartSessionOperatorAttemptRepositoryIntegrationTest {
  private static final UUID TENANT = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID RESERVATION_OWNER =
      UUID.fromString("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = UUID.fromString("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = UUID.fromString("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final String NAMESPACE = "world-runtime";
  private static final String WORKLOAD =
      "spiffe://firemud/ns/world-runtime/sa/logging-admin-service";
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void oneConcurrentClaimWinsAndExactReplayReturnsOnlyTheOriginalSnapshot() throws Exception {
    Fixture fixture = fixture(Duration.ofSeconds(30));
    StartSessionPostAuthorizationExecutionTuple tuple = tuple("operator-attempt-concurrent");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<ReservationResult> first =
          executor.submit(() -> contender(fixture, tuple, ready, start));
      Future<ReservationResult> second =
          executor.submit(() -> contender(fixture, tuple, ready, start));
      assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      ReservationResult firstResult = first.get(20, TimeUnit.SECONDS);
      ReservationResult secondResult = second.get(20, TimeUnit.SECONDS);

      assertThat(List.of(firstResult.disposition(), secondResult.disposition()))
          .containsExactlyInAnyOrder(
              ReservationDisposition.CLAIM_CREATED, ReservationDisposition.EXACT_REPLAY);
      ReservationResult created =
          firstResult.disposition() == ReservationDisposition.CLAIM_CREATED
              ? firstResult
              : secondResult;
      ReservationResult replay =
          firstResult.disposition() == ReservationDisposition.EXACT_REPLAY
              ? firstResult
              : secondResult;
      assertThat(created.claim()).isPresent();
      assertThat(replay.claim()).isEmpty();
      assertThat(replay.snapshot().ownerAttemptId()).isEqualTo(created.snapshot().ownerAttemptId());
      assertThat(replay.snapshot().ownerMutationId())
          .isEqualTo(created.snapshot().ownerMutationId());
      assertThat(replay.snapshot().ownerFence()).isEqualTo(created.snapshot().ownerFence());
      assertThat(replay.snapshot().phaseState()).isEqualTo("OWNER_EXECUTION_PENDING");
      assertThat(fixture.attemptCount()).isEqualTo(1);
      assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_instances")))).isZero();
      assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("gameplay_admission_pointer"))))
          .isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void exactAccountProjectionAttachesOnceWhileFingerprintAndSourceSubstitutionsConflict() {
    Fixture fixture = fixture(Duration.ofSeconds(30));
    StartSessionPostAuthorizationExecutionTuple tuple = tuple("operator-attempt-projection");
    ReservationResult reservation = fixture.reserve(tuple);
    AttemptClaim claim = reservation.claim().orElseThrow();
    byte[] exactBundle = tuple.authorityEvidenceBundleBytes();
    AccountRedemptionProjection exact = projection(FINGERPRINT, exactBundle, ISSUANCE_ID, 23L);

    fixture.transactions.executeWithoutResult(
        status -> {
          fixture.repository.attachAccountRedemptionProjection(claim, exact);
          status.setRollbackOnly();
        });
    assertThat(fixture.reserve(tuple).snapshot().accountRedemptionProjection()).isNull();

    AttemptSnapshot attached = fixture.attach(claim, exact);
    AttemptSnapshot replayed = fixture.attach(claim, exact);
    assertThat(attached.accountRedemptionProjection()).isNotEmpty();
    assertThat(replayed.accountRedemptionProjection())
        .containsExactly(attached.accountRedemptionProjection());

    AccountRedemptionProjection changedFingerprint =
        projection("arfp/v1/other-key/" + "c".repeat(64), exactBundle, ISSUANCE_ID, 23L);
    assertThatThrownBy(() -> fixture.attach(claim, changedFingerprint))
        .isInstanceOf(
            GameSessionStartSessionOperatorAttemptRepository
                .StartSessionOperatorAttemptConflictException.class);

    byte[] changedSourceBundle =
        bundle(tuple.preAuthorizationTuple(), "18", "sha256:" + "c".repeat(64));
    AccountRedemptionProjection changedSource =
        projection(FINGERPRINT, changedSourceBundle, ISSUANCE_ID, 23L);
    assertThatThrownBy(() -> fixture.attach(claim, changedSource))
        .isInstanceOf(
            GameSessionStartSessionOperatorAttemptRepository
                .StartSessionOperatorAttemptConflictException.class);
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("game_session_start_session_operator_attempt"))))
        .isEqualTo(1);
  }

  @Test
  void changedTupleAndExpiredClaimNeverCreateOrTransferAnotherOwnerAttempt() throws Exception {
    Fixture fixture = fixture(Duration.ofMillis(50));
    StartSessionPostAuthorizationExecutionTuple original =
        tuple("operator-attempt-conflict", "original action");
    ReservationResult reservation = fixture.reserve(original);
    StartSessionPostAuthorizationExecutionTuple changed =
        tuple("operator-attempt-conflict", "changed action");
    StartSessionPostAuthorizationExecutionTuple changedReservation =
        tuple(
            "operator-attempt-conflict",
            "original action",
            UUID.fromString("53ced645-bf72-4e55-91ba-c9fef2541a72"),
            20L);

    assertThatThrownBy(() -> fixture.reserve(changed))
        .isInstanceOf(
            GameSessionStartSessionOperatorAttemptRepository
                .StartSessionOperatorAttemptConflictException.class);
    assertThatThrownBy(() -> fixture.reserve(changedReservation))
        .isInstanceOf(
            GameSessionStartSessionOperatorAttemptRepository
                .StartSessionOperatorAttemptConflictException.class);
    assertThat(fixture.attemptCount()).isEqualTo(1);

    Thread.sleep(150L);
    assertThatThrownBy(() -> fixture.validate(reservation.claim().orElseThrow()))
        .isInstanceOf(StaleStartSessionOperatorAttemptClaimException.class)
        .hasMessageContaining("expired");
    ReservationResult expiredReplay = fixture.reserve(original);
    assertThat(expiredReplay.disposition()).isEqualTo(ReservationDisposition.EXACT_REPLAY);
    assertThat(expiredReplay.claim()).isEmpty();
    assertThat(expiredReplay.snapshot().ownerAttemptId())
        .isEqualTo(reservation.snapshot().ownerAttemptId());
    assertThat(fixture.attemptCount()).isEqualTo(1);
  }

  @Test
  void rollbackLeavesNoAttemptRecord() {
    Fixture fixture = fixture(Duration.ofSeconds(30));
    StartSessionPostAuthorizationExecutionTuple tuple = tuple("operator-attempt-rollback");

    fixture.transactions.executeWithoutResult(
        status -> {
          fixture.repository.reserve(tuple);
          status.setRollbackOnly();
        });

    assertThat(fixture.attemptCount()).isZero();
  }

  @Test
  void persistsTheMaximumCanonicalAuthorizationFingerprintLength() {
    Fixture fixture = fixture(Duration.ofSeconds(30));
    String maximumFingerprint = "arfp/v1/" + "k".repeat(64) + "/" + "d".repeat(64);
    assertThat(maximumFingerprint).hasSize(137);

    ReservationResult reservation =
        fixture.reserve(
            tuple(
                "operator-attempt-max-fingerprint",
                "canonical StartSession owner attempt",
                RESERVATION_OWNER,
                19L,
                maximumFingerprint));

    assertThat(reservation.disposition()).isEqualTo(ReservationDisposition.CLAIM_CREATED);
    assertThat(reservation.snapshot().ownerAttemptId()).isNotNull();
    assertThat(fixture.attemptCount()).isEqualTo(1);
  }

  private static ReservationResult contender(
      Fixture fixture,
      StartSessionPostAuthorizationExecutionTuple tuple,
      CountDownLatch ready,
      CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(15, TimeUnit.SECONDS)) {
      throw new IllegalStateException("concurrent StartSession contender did not start");
    }
    return fixture.reserve(tuple);
  }

  private static StartSessionPostAuthorizationExecutionTuple tuple(String requestId) {
    return tuple(requestId, "canonical StartSession owner attempt");
  }

  private static StartSessionPostAuthorizationExecutionTuple tuple(
      String requestId, String auditReason) {
    return tuple(requestId, auditReason, RESERVATION_OWNER, 19L);
  }

  private static StartSessionPostAuthorizationExecutionTuple tuple(
      String requestId, String auditReason, UUID reservationOwner, long reservationFence) {
    return tuple(requestId, auditReason, reservationOwner, reservationFence, FINGERPRINT);
  }

  private static StartSessionPostAuthorizationExecutionTuple tuple(
      String requestId,
      String auditReason,
      UUID reservationOwner,
      long reservationFence,
      String fingerprint) {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
            new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            auditReason);
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(requestId, ACTOR, action);
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        WORKLOAD,
        fingerprint,
        reservationOwner,
        reservationFence,
        bundle(pre, "17"),
        reference("17"));
  }

  private static AccountRedemptionProjection projection(
      String fingerprint, byte[] bundle, UUID issuanceOperationId, long issuanceFence) {
    return new AccountRedemptionProjection(fingerprint, bundle, issuanceOperationId, issuanceFence);
  }

  private static byte[] bundle(StartSessionPreAuthorizationReservationTuple tuple, String version) {
    return bundle(tuple, version, "sha256:" + "a".repeat(64));
  }

  private static byte[] bundle(
      StartSessionPreAuthorizationReservationTuple tuple, String version, String sourceEvidenceId) {
    String tenantId = TENANT.toString();
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", sourceEvidenceId,
            "sourceEvidenceVersion", version,
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2026-10-09T00:00:00Z",
            "expiresAt", "2026-10-09T00:05:00Z");
    Map<String, Object> identity =
        Map.of(
            "issuanceOperationId", ISSUANCE_ID.toString(),
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    Map<String, Object> authority =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 2L,
            "tenantAuthorityGeneration", Map.of(tenantId, 3L),
            "membershipAuthorityGeneration", Map.of(tenantId, 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> evidence =
        Map.of(
            "evidenceType",
            StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId",
            ACTOR.toString(),
            "controlUiTokenJti",
            TOKEN_JTI.toString(),
            "role",
            "tenantAdmin",
            "accountGeneration",
            "2",
            "tenantGeneration",
            "3");
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenantId, "targetNamespace", NAMESPACE),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", tenantId),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            identity,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(tenantId, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            evidence);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static StartSessionAuthorityEvidenceBundle.BundleReference reference(String version) {
    return new StartSessionAuthorityEvidenceBundle.BundleReference(
        StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, version, "23", "18446744073709551615");
  }

  private static Fixture fixture(Duration claimLease) {
    String schema = "gs_operator_attempt_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();

    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new Fixture(
        dsl, transactions, new GameSessionStartSessionOperatorAttemptRepository(dsl, claimLease));
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate transactions,
      GameSessionStartSessionOperatorAttemptRepository repository) {
    ReservationResult reserve(StartSessionPostAuthorizationExecutionTuple tuple) {
      return Objects.requireNonNull(
          transactions.execute(status -> repository.reserve(tuple)),
          "StartSession attempt reservation result");
    }

    AttemptSnapshot attach(AttemptClaim claim, AccountRedemptionProjection projection) {
      return Objects.requireNonNull(
          transactions.execute(
              status -> repository.attachAccountRedemptionProjection(claim, projection)),
          "StartSession attempt projection snapshot");
    }

    AttemptSnapshot validate(AttemptClaim claim) {
      return Objects.requireNonNull(
          transactions.execute(status -> repository.validateCurrentClaim(claim)),
          "StartSession attempt claim snapshot");
    }

    int attemptCount() {
      return dsl.fetchCount(DSL.table(DSL.name("game_session_start_session_operator_attempt")));
    }
  }
}
