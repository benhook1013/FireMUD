package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptClaim;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.ReservationResult;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.StaleStartSessionOperatorAttemptClaimException;
import net.firedevops.firemud.test.TestContainerImages;
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

/** PostgreSQL proof of the exact, observation-only current original StartSession attempt read. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class OriginalStartSessionCurrentAttemptReadPostgresIntegrationTest {
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
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void currentOriginalAttemptReadReturnsExactAttachedSnapshotWithoutChangingPersistedRow() {
    Fixture fixture = fixture(Duration.ofSeconds(30));
    StartSessionPostAuthorizationExecutionTuple tuple = tuple("current-attempt-read-exact");
    AttemptClaim claim = fixture.reserve(tuple).claim().orElseThrow();
    AttemptSnapshot attached =
        fixture.attach(
            claim,
            projection(
                tuple.authorizationReferenceFingerprint(),
                tuple.authorityEvidenceBundleBytes(),
                ISSUANCE_ID,
                23L));
    Request request = currentAttemptRequest(tuple, claim);
    String before = fixture.ownerAttemptRowJson(tuple.controlPlaneRequestId());

    AttemptSnapshot current = fixture.currentOriginalAttempt(request);

    assertThat(current.targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(current.controlPlaneRequestId()).isEqualTo(tuple.controlPlaneRequestId());
    assertThat(current.canonicalTenantId()).isEqualTo(TENANT);
    assertThat(current.ownerAttemptId()).isEqualTo(claim.ownerAttemptId());
    assertThat(current.ownerMutationId()).isEqualTo(claim.ownerMutationId());
    assertThat(current.ownerFence()).isEqualTo(claim.ownerFence());
    assertThat(current.phaseState()).isEqualTo("OWNER_EXECUTION_PENDING");
    assertThat(current.leaseExpiresAt()).isEqualTo(attached.leaseExpiresAt());
    assertThat(current.postAuthorizationExecutionTuple()).containsExactly(tuple.canonicalBytes());
    assertThat(current.accountRedemptionProjection())
        .containsExactly(attached.accountRedemptionProjection());
    assertThat(fixture.ownerAttemptRowJson(tuple.controlPlaneRequestId())).isEqualTo(before);
  }

  @Test
  void currentOriginalAttemptReadRejectsExpiredAndUnattachedOrMismatchedEvidenceWithoutMutation()
      throws Exception {
    Fixture unattached = fixture(Duration.ofSeconds(30));
    StartSessionPostAuthorizationExecutionTuple unattachedTuple =
        tuple("current-attempt-read-unattached");
    AttemptClaim unattachedClaim = unattached.reserve(unattachedTuple).claim().orElseThrow();
    String unattachedBefore =
        unattached.ownerAttemptRowJson(unattachedTuple.controlPlaneRequestId());
    assertThatThrownBy(
            () ->
                unattached.currentOriginalAttempt(
                    currentAttemptRequest(unattachedTuple, unattachedClaim)))
        .isInstanceOf(StaleStartSessionOperatorAttemptClaimException.class)
        .hasMessageContaining("no attached Account projection");
    assertThat(unattached.ownerAttemptRowJson(unattachedTuple.controlPlaneRequestId()))
        .isEqualTo(unattachedBefore);

    Fixture mismatched = fixture(Duration.ofSeconds(30));
    StartSessionPostAuthorizationExecutionTuple tuple = tuple("current-attempt-read-mismatch");
    AttemptClaim claim = mismatched.reserve(tuple).claim().orElseThrow();
    mismatched.attach(
        claim,
        projection(
            tuple.authorizationReferenceFingerprint(),
            tuple.authorityEvidenceBundleBytes(),
            ISSUANCE_ID,
            23L));
    String before = mismatched.ownerAttemptRowJson(tuple.controlPlaneRequestId());
    assertThatThrownBy(
            () ->
                mismatched.currentOriginalAttempt(
                    currentAttemptRequest(
                        tuple,
                        UUID.fromString("1819a1ce-04d2-4e3f-bb64-4a672d4eb711"),
                        claim.ownerMutationId(),
                        claim.ownerFence())))
        .isInstanceOf(StaleStartSessionOperatorAttemptClaimException.class);
    assertThatThrownBy(
            () ->
                mismatched.currentOriginalAttempt(
                    currentAttemptRequest(
                        tuple,
                        claim.ownerAttemptId(),
                        UUID.fromString("2819a1ce-04d2-4e3f-bb64-4a672d4eb711"),
                        claim.ownerFence())))
        .isInstanceOf(StaleStartSessionOperatorAttemptClaimException.class);
    assertThatThrownBy(
            () ->
                mismatched.currentOriginalAttempt(
                    currentAttemptRequest(
                        tuple,
                        claim.ownerAttemptId(),
                        claim.ownerMutationId(),
                        claim.ownerFence() + 1L)))
        .isInstanceOf(StaleStartSessionOperatorAttemptClaimException.class);
    StartSessionPostAuthorizationExecutionTuple changedTuple =
        tuple(tuple.controlPlaneRequestId(), "different complete action");
    assertThatThrownBy(
            () -> mismatched.currentOriginalAttempt(currentAttemptRequest(changedTuple, claim)))
        .isInstanceOf(
            GameSessionStartSessionOperatorAttemptRepository
                .StartSessionOperatorAttemptConflictException.class);
    assertThat(mismatched.ownerAttemptRowJson(tuple.controlPlaneRequestId())).isEqualTo(before);

    Fixture expired = fixture(Duration.ofMillis(500));
    StartSessionPostAuthorizationExecutionTuple expiredTuple =
        tuple("current-attempt-read-lease-expired");
    AttemptClaim expiredClaim = expired.reserve(expiredTuple).claim().orElseThrow();
    expired.attach(
        expiredClaim,
        projection(
            expiredTuple.authorizationReferenceFingerprint(),
            expiredTuple.authorityEvidenceBundleBytes(),
            ISSUANCE_ID,
            23L));
    Thread.sleep(750L);
    String expiredBefore = expired.ownerAttemptRowJson(expiredTuple.controlPlaneRequestId());
    assertThat(expired.leaseExpired(expiredTuple.controlPlaneRequestId())).isTrue();
    assertThatThrownBy(
            () -> expired.currentOriginalAttempt(currentAttemptRequest(expiredTuple, expiredClaim)))
        .isInstanceOf(StaleStartSessionOperatorAttemptClaimException.class)
        .hasMessageContaining("expired");
    assertThat(expired.ownerAttemptRowJson(expiredTuple.controlPlaneRequestId()))
        .isEqualTo(expiredBefore);
  }

  @Test
  void currentOriginalAttemptReadRejectsExpiredAccountReferenceWithLiveClaimWithoutMutation() {
    Fixture fixture = fixture(Duration.ofMinutes(1));
    StartSessionPostAuthorizationExecutionTuple tuple =
        tuple(
            "current-attempt-read-reference-expired",
            "canonical StartSession owner attempt",
            RESERVATION_OWNER,
            19L,
            FINGERPRINT,
            Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(5));
    AttemptClaim claim = fixture.reserve(tuple).claim().orElseThrow();
    fixture.attach(
        claim,
        projection(
            tuple.authorizationReferenceFingerprint(),
            tuple.authorityEvidenceBundleBytes(),
            ISSUANCE_ID,
            23L));
    String before = fixture.ownerAttemptRowJson(tuple.controlPlaneRequestId());
    assertThat(fixture.leaseExpired(tuple.controlPlaneRequestId())).isFalse();

    assertThatThrownBy(() -> fixture.currentOriginalAttempt(currentAttemptRequest(tuple, claim)))
        .isInstanceOf(StaleStartSessionOperatorAttemptClaimException.class)
        .hasMessageContaining("authorization reference expired");

    assertThat(fixture.leaseExpired(tuple.controlPlaneRequestId())).isFalse();
    assertThat(fixture.ownerAttemptRowJson(tuple.controlPlaneRequestId())).isEqualTo(before);
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
    return tuple(
        requestId,
        auditReason,
        reservationOwner,
        reservationFence,
        fingerprint,
        Instant.parse("2099-01-01T00:05:00Z"));
  }

  private static StartSessionPostAuthorizationExecutionTuple tuple(
      String requestId,
      String auditReason,
      UUID reservationOwner,
      long reservationFence,
      String fingerprint,
      Instant authorityExpiresAt) {
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
        bundle(pre, "17", "sha256:" + "a".repeat(64), authorityExpiresAt),
        reference("17"));
  }

  private static AccountRedemptionProjection projection(
      String fingerprint, byte[] bundle, UUID issuanceOperationId, long issuanceFence) {
    return new AccountRedemptionProjection(fingerprint, bundle, issuanceOperationId, issuanceFence);
  }

  private static Request currentAttemptRequest(
      StartSessionPostAuthorizationExecutionTuple tuple, AttemptClaim claim) {
    return currentAttemptRequest(
        tuple, claim.ownerAttemptId(), claim.ownerMutationId(), claim.ownerFence());
  }

  private static Request currentAttemptRequest(
      StartSessionPostAuthorizationExecutionTuple tuple,
      UUID expectedAttemptId,
      UUID expectedMutationId,
      long expectedFence) {
    return new Request(
        UUID.fromString("01111111-1111-4111-8111-111111111111"),
        NAMESPACE,
        tuple.canonicalBytes(),
        expectedAttemptId,
        expectedMutationId,
        expectedFence);
  }

  private static byte[] bundle(
      StartSessionPreAuthorizationReservationTuple tuple,
      String version,
      String sourceEvidenceId,
      Instant authorityExpiresAt) {
    String tenantId = tuple.action().scope().tenantId().toString();
    String targetNamespace = tuple.action().scope().targetNamespace();
    Map<String, Object> projection =
        Map.of(
            "sourceType",
            "ACCOUNT",
            "sourceEvidenceId",
            sourceEvidenceId,
            "sourceEvidenceVersion",
            version,
            "projectionStatus",
            "CURRENT",
            "evaluatedAt",
            "1999-01-01T00:00:00Z",
            "expiresAt",
            authorityExpiresAt.toString());
    Map<String, Object> identity =
        Map.of(
            "issuanceOperationId",
            ISSUANCE_ID.toString(),
            "controlPlaneRequestId",
            tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
            Map.of(
                "requestIdentityKind",
                "controlPlaneRequestId",
                "requestId",
                tuple.controlPlaneRequestId()),
            "mutationDigest",
            tuple.mutationDigest());
    Map<String, Object> authority =
        Map.of(
            "issuerAuthGeneration",
            1L,
            "accountAuthorityGeneration",
            2L,
            "tenantAuthorityGeneration",
            Map.of(tenantId, 3L),
            "membershipAuthorityGeneration",
            Map.of(tenantId, 4L),
            "privateRealmGrantVersions",
            List.of());
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
                "scope", Map.of("tenantId", tenantId, "targetNamespace", targetNamespace),
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
    String schema = "gs_current_attempt_read_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history")
            .locations(MIGRATION_LOCATION)
            .load();
    flyway.migrate();

    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    String latestVersion =
        Objects.requireNonNull(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT version FROM flyway_schema_history WHERE success "
                            + "ORDER BY installed_rank DESC LIMIT 1"),
                    "Game Session migration history has no successful version")
                .get("version", String.class));
    assertThat(latestVersion).isEqualTo("35");

    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    GameSessionStartSessionOperatorAttemptRepository repository =
        new GameSessionStartSessionOperatorAttemptRepository(dsl, claimLease);
    return new Fixture(dsl, transactions, repository);
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

    AttemptSnapshot currentOriginalAttempt(Request request) {
      return Objects.requireNonNull(
          transactions.execute(status -> repository.readCurrentOriginalAttempt(request)),
          "Current original StartSession attempt snapshot");
    }

    String ownerAttemptRowJson(String requestId) {
      org.jooq.Record row =
          dsl.fetchOne(
              "SELECT to_jsonb(attempt)::text FROM "
                  + "game_session_start_session_operator_attempt AS attempt "
                  + "WHERE target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              requestId);
      return Objects.requireNonNull(
          Objects.requireNonNull(row, "persisted owner attempt row").get(0, String.class),
          "persisted owner attempt row JSON");
    }

    boolean leaseExpired(String requestId) {
      var row =
          dsl.fetchOne(
              "SELECT lease_expires_at <= clock_timestamp() AS expired "
                  + "FROM game_session_start_session_operator_attempt "
                  + "WHERE target_namespace = ? AND control_plane_request_id = ?",
              NAMESPACE,
              requestId);
      if (row == null) {
        throw new IllegalStateException("persisted owner attempt row missing while checking lease");
      }
      return Boolean.TRUE.equals(row.get("expired", Boolean.class));
    }
  }
}
