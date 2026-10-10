package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository.StoredEvidence;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle.BundleReference;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proof for exact retry, lock-wait expiry, pending-parent, and immutable-row behavior.
 *
 * <p>The fixture deliberately inserts a synthetic Account participation with triggers disabled and
 * supplies a synthetic Game Session observation. It proves database/repository binding only; it
 * does not claim authenticated cross-owner source evidence or a genuine Game Session producer.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountStartSessionWorldOriginalAttemptEvidencePostgresIntegrationTest {
  private static final UUID OWNER_MUTATION_ID =
      UUID.fromString("f7811486-2bd4-41a0-9bf7-a351ce3a0c8f");
  private static final UUID OWNER_ATTEMPT_ID =
      UUID.fromString("c0df9691-cba5-4274-a49d-0bc7b2158ef7");
  private static final long OWNER_FENCE = 47L;
  private static final String NAMESPACE = "world-runtime";

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void exactRetryPreservesFirstBytesAndExpiryAfterLockWaitCannotCreateOrRefreshEvidence()
      throws Exception {
    String schema = "ss_world_attempt_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    AccountStartSessionWorldOriginalAttemptEvidenceRepository repository =
        new AccountStartSessionWorldOriginalAttemptEvidenceRepository(dsl);
    Fixture fixture = fixture();
    StoredParticipation parent =
        transaction.execute(
            status -> insertSyntheticParent(dsl, fixture, UUID.randomUUID(), false));
    assertThat(parent).isNotNull();

    Instant retryExpiry = databaseNow(dsl).plusMillis(6_000L);
    Result firstObservation = observation(fixture, UUID.randomUUID(), retryExpiry);
    StoredEvidence first =
        transaction.execute(status -> repository.retainCurrentExact(parent, firstObservation));
    assertThat(first).isNotNull();
    assertThat(first.gameSessionOwnerMutationId()).isEqualTo(OWNER_MUTATION_ID);

    Request freshCorrelation = request(fixture, UUID.randomUUID());
    Result exactRetryObservation =
        new Result(freshCorrelation, first.originalLeaseExpiresAt(), fixture.accountProjection());
    StoredEvidence exactRetry =
        transaction.execute(status -> repository.retainCurrentExact(parent, exactRetryObservation));
    assertThat(exactRetry.originalResponseBytes()).containsExactly(first.originalResponseBytes());
    assertThat(exactRetry.originalResponseDigest()).isEqualTo(first.originalResponseDigest());
    assertThat(exactRetry.result().request().readRequestId())
        .isEqualTo(first.result().request().readRequestId());
    StoredEvidence current =
        transaction.execute(
            status ->
                repository
                    .findCurrentExact(parent, request(fixture, UUID.randomUUID()))
                    .orElseThrow());
    assertThat(current.originalResponseBytes()).containsExactly(first.originalResponseBytes());
    assertThat(current.originalResponseDigest()).isEqualTo(first.originalResponseDigest());

    Result changedMutation =
        new Result(
            new Request(
                UUID.randomUUID(),
                NAMESPACE,
                fixture.tuple().canonicalBytes(),
                OWNER_ATTEMPT_ID,
                UUID.fromString("1f2d6f4e-1202-4bdb-bcd4-f3c14e4cc764"),
                OWNER_FENCE),
            retryExpiry,
            fixture.accountProjection());
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status -> repository.retainCurrentExact(parent, changedMutation)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts");

    assertAppendOnly(dsl, parent.participationId());
    assertLeaseExpiresWhileParentLocked(
        dsl, transaction, repository, parent, fixture, exactRetryObservation);

    assertThatThrownBy(
            () ->
                transaction.execute(
                    status -> repository.findCurrentExact(parent, freshCorrelation).orElseThrow()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");
    StoredEvidence historical =
        transaction.execute(
            status ->
                repository
                    .findHistoricalExact(parent, request(fixture, UUID.randomUUID()))
                    .orElseThrow());
    assertThat(historical.originalLeaseExpiresAt()).isEqualTo(retryExpiry);
    assertThat(historical.originalResponseBytes()).containsExactly(first.originalResponseBytes());
  }

  @Test
  void freshInsertLeaseIsRecheckedAfterWaitingForTheExactParentLock() throws Exception {
    String schema = "ss_world_attempt_wait_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    AccountStartSessionWorldOriginalAttemptEvidenceRepository repository =
        new AccountStartSessionWorldOriginalAttemptEvidenceRepository(dsl);
    Fixture fixture = fixture();
    StoredParticipation parent =
        transaction.execute(
            status -> insertSyntheticParent(dsl, fixture, UUID.randomUUID(), false));
    assertThat(parent).isNotNull();

    Instant expiry = databaseNow(dsl).plusMillis(3_000L);
    Result observation = observation(fixture, UUID.randomUUID(), expiry);
    assertLeaseExpiresWhileParentLocked(dsl, transaction, repository, parent, fixture, observation);
    Record evidenceCount =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT count(*) FROM account_start_session_world_original_attempt_evidence "
                    + "WHERE participation_id = ?",
                parent.participationId()),
            "evidence count query returned no row");
    assertThat(evidenceCount.get(0, Long.class)).isZero();

    Instant expiredAt = databaseNow(dsl).minusSeconds(1L);
    Result expiredObservation = observation(fixture, UUID.randomUUID(), expiredAt);
    byte[] expiredResponse =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(expiredObservation)
            .toByteArray();
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO account_start_session_world_original_attempt_evidence "
                        + "(participation_id, target_namespace, game_session_owner_attempt_id, "
                        + "game_session_owner_mutation_id, game_session_owner_fence, "
                        + "original_lease_expires_at, original_response_bytes, original_response_digest) "
                        + "VALUES (?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), ?, ?)",
                    parent.participationId(),
                    NAMESPACE,
                    fixture.ownerAttemptId(),
                    OWNER_MUTATION_ID,
                    OWNER_FENCE,
                    OffsetDateTime.ofInstant(expiredAt, ZoneOffset.UTC),
                    expiredResponse,
                    "sha256:" + sha256Hex(expiredResponse)))
        .isInstanceOf(RuntimeException.class);
    Record expiredEvidenceCount =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT count(*) FROM account_start_session_world_original_attempt_evidence "
                    + "WHERE participation_id = ?",
                parent.participationId()),
            "evidence count query returned no row");
    assertThat(expiredEvidenceCount.get(0, Long.class)).isZero();
  }

  @Test
  void settledParticipationCannotReceiveNewObservation() {
    String schema = "ss_world_attempt_settled_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    AccountStartSessionWorldOriginalAttemptEvidenceRepository repository =
        new AccountStartSessionWorldOriginalAttemptEvidenceRepository(dsl);
    Fixture fixture = fixture();
    UUID participationId = UUID.randomUUID();
    StoredParticipation parent =
        transaction.execute(
            status -> {
              StoredParticipation inserted =
                  insertSyntheticParent(dsl, fixture, participationId, true);
              return inserted;
            });

    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        repository.retainCurrentExact(
                            parent,
                            observation(
                                fixture, UUID.randomUUID(), databaseNow(dsl).plusSeconds(30)))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");
  }

  private static void assertAppendOnly(DSLContext dsl, UUID participationId) {
    String table = "account_start_session_world_original_attempt_evidence";
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE "
                        + table
                        + " SET original_response_digest = original_response_digest "
                        + "WHERE participation_id = ?",
                    participationId))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM " + table + " WHERE participation_id = ?", participationId))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> dsl.execute("TRUNCATE " + table)).isInstanceOf(RuntimeException.class);
    Record evidenceCount =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT count(*) FROM " + table + " WHERE participation_id = ?", participationId),
            "append-only evidence count query returned no row");
    assertThat(evidenceCount.get(0, Long.class)).isEqualTo(1L);
  }

  private static void assertLeaseExpiresWhileParentLocked(
      DSLContext dsl,
      TransactionTemplate transaction,
      AccountStartSessionWorldOriginalAttemptEvidenceRepository repository,
      StoredParticipation parent,
      Fixture fixture,
      Result observation)
      throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch parentLocked = new CountDownLatch(1);
    CountDownLatch releaseParent = new CountDownLatch(1);
    try {
      Future<?> holder =
          executor.submit(
              () ->
                  transaction.execute(
                      status -> {
                        dsl.fetchOne(
                            "SELECT participation_id FROM account_start_session_world_participations "
                                + "WHERE participation_id = ? FOR UPDATE",
                            parent.participationId());
                        parentLocked.countDown();
                        await(releaseParent);
                        return null;
                      }));
      assertTrue(parentLocked.await(5, TimeUnit.SECONDS), "parent lock holder did not start");
      CountDownLatch writerStarted = new CountDownLatch(1);
      Future<StoredEvidence> writer =
          executor.submit(
              () -> {
                writerStarted.countDown();
                return transaction.execute(
                    status -> repository.retainCurrentExact(parent, observation));
              });
      assertTrue(writerStarted.await(5, TimeUnit.SECONDS), "evidence writer did not start");
      awaitBlockedOnParentLock(dsl);
      while (observation.originalLeaseExpiresAt().isAfter(databaseNow(dsl))) {
        Thread.sleep(25L);
      }
      releaseParent.countDown();
      holder.get(5, TimeUnit.SECONDS);
      assertThatThrownBy(() -> writer.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("Exact original StartSession attempt evidence unavailable");
    } finally {
      releaseParent.countDown();
      executor.shutdownNow();
    }
  }

  private static void awaitBlockedOnParentLock(DSLContext dsl) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      Record row =
          dsl.fetchOne(
              "SELECT count(*) FROM pg_stat_activity "
                  + "WHERE cardinality(pg_blocking_pids(pid)) > 0 "
                  + "AND query ILIKE '%account_start_session_world_participations%' ");
      if (row != null && row.get(0, Long.class) > 0L) return;
      Thread.sleep(20L);
    }
    throw new AssertionError("evidence writer did not block behind the Account participation lock");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("test lock release was not signaled");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("test lock holder was interrupted", interrupted);
    }
  }

  private static StoredParticipation insertSyntheticParent(
      DSLContext dsl, Fixture fixture, UUID participationId, boolean settled) {
    dsl.execute("SET LOCAL session_replication_role = replica");
    dsl.execute(
        "INSERT INTO account_start_session_world_participations "
            + "(participation_id, participation_fence, control_plane_request_id, "
            + "original_post_authorization_tuple, target_namespace, canonical_tenant_id, "
            + "canonical_game_instance_id, game_session_owner_attempt_id, game_session_owner_fence, "
            + "preparation_input_json, preparation_input_digest, created_at) "
            + "OVERRIDING SYSTEM VALUE VALUES (?, 41, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ)) ",
        participationId,
        fixture.tuple().controlPlaneRequestId(),
        fixture.tuple().canonicalBytes(),
        NAMESPACE,
        fixture.tenantId(),
        fixture.gameInstanceId(),
        OWNER_ATTEMPT_ID,
        OWNER_FENCE,
        fixture.preparationJson(),
        fixture.preparationDigest(),
        OffsetDateTime.ofInstant(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC));
    if (settled) {
      byte[] terminal = "synthetic settled parent".getBytes(StandardCharsets.UTF_8);
      dsl.execute(
          "INSERT INTO account_start_session_world_participation_settlements "
              + "(participation_id, outcome, world_execution_fence, terminal_bytes, terminal_digest) "
              + "VALUES (?, 'ABORTED', 1, ?, ?)",
          participationId,
          terminal,
          "sha256:" + sha256Hex(terminal));
    }
    dsl.execute("SET LOCAL session_replication_role = origin");
    Record row =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT * FROM account_start_session_world_participations WHERE participation_id = ?",
                participationId),
            "synthetic Account participation insert produced no row");
    return new StoredParticipation(
        UUID.fromString(row.get("participation_id", UUID.class).toString()),
        row.get("participation_fence", Long.class),
        row.get("control_plane_request_id", String.class),
        row.get("original_post_authorization_tuple", byte[].class),
        row.get("target_namespace", String.class),
        row.get("canonical_tenant_id", UUID.class),
        row.get("canonical_game_instance_id", UUID.class),
        row.get("game_session_owner_attempt_id", UUID.class),
        row.get("game_session_owner_fence", Long.class),
        row.get("preparation_input_json", String.class),
        row.get("preparation_input_digest", String.class),
        row.get("producer_xid", Long.class),
        row.get("created_at", OffsetDateTime.class),
        List.of());
  }

  private static Fixture fixture() {
    UUID actorId = UUID.fromString("d888ddc4-4a62-4b25-9bab-c4f94860c2ca");
    UUID tenantId = UUID.fromString("6d1e5ce5-6127-4d35-88b8-7a6f40692038");
    UUID targetAccountId = UUID.fromString("1df91ae6-5125-4e47-9f1d-2e45eab8a4a0");
    UUID reservationOwnerId = UUID.fromString("ca1b63bf-f09f-4a55-96bf-a1fd2e22349e");
    String requestId = "start-session/original-attempt/" + UUID.randomUUID();
    StartSessionPreAuthorizationReservationTuple preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            requestId,
            actorId,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(tenantId, NAMESPACE),
                new StartSessionOperatorAction.Target(17L, targetAccountId),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "Synthetic Account participation integration proof"));
    byte[] bundle = authorityBundle(preTuple, actorId, tenantId, requestId);
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            preTuple,
            "spiffe://firemud/ns/world-runtime/sa/logging-admin-service",
            "arfp/v1/key-1/" + "a".repeat(64),
            reservationOwnerId,
            7L,
            bundle,
            new BundleReference("authorityEvidenceBundle/v1", "11", "13", "17"));
    String preparationJson = "{\"identity\":{\"worldSlug\":\"synthetic-arena\"}}";
    return new Fixture(
        tuple,
        tenantId,
        UUID.fromString("51c67424-324e-486d-b3ad-45b6e85c1a0d"),
        OWNER_ATTEMPT_ID,
        preparationJson,
        "sha256:" + sha256Hex(preparationJson.getBytes(StandardCharsets.UTF_8)),
        accountProjection(tuple));
  }

  private static byte[] authorityBundle(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID actorId,
      UUID tenantId,
      String requestId) {
    String tenant = tenantId.toString();
    String actor = actorId.toString();
    Map<String, Object> authorityTuple =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 1L,
            "tenantAuthorityGeneration", Map.of(tenant, 1L),
            "membershipAuthorityGeneration", Map.of(tenant, 1L),
            "privateRealmGrantVersions", List.of());
    return AccountControlUiAuthority.canonical(
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope",
                Map.of("tenantId", tenant, "targetNamespace", NAMESPACE),
                "actionFamily",
                "StartSession",
                "applicableAccountId",
                actor,
                "applicableTenantId",
                tenant),
            "accountProjectionEvidence",
            Map.of(
                "sourceType",
                "ACCOUNT",
                "sourceEvidenceId",
                "sha256:" + "c".repeat(64),
                "sourceEvidenceVersion",
                "11",
                "projectionStatus",
                "CURRENT",
                "evaluatedAt",
                "2026-10-09T00:00:00Z",
                "expiresAt",
                "2026-10-09T00:03:00Z"),
            "issuanceOperationIdentity",
            Map.of(
                "issuanceOperationId",
                "44444444-4444-4444-8444-444444444444",
                "controlPlaneRequestId",
                requestId,
                "actionFamilyRequestIdentity",
                Map.of("requestIdentityKind", "controlPlaneRequestId", "requestId", requestId),
                "mutationDigest",
                tuple.mutationDigest()),
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authorityTuple,
            "membershipVersion",
            Map.of(tenant, 2L),
            "issuanceFence",
            "9",
            "issuanceEvidence",
            Map.of(
                "evidenceType",
                StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                "actorAccountId",
                actor,
                "controlUiTokenJti",
                "55555555-5555-4555-8555-555555555555",
                "role",
                "tenantAdmin",
                "accountGeneration",
                "1",
                "tenantGeneration",
                "1")));
  }

  private static Result observation(Fixture fixture, UUID readId, Instant expiry) {
    return new Result(request(fixture, readId), expiry, fixture.accountProjection());
  }

  private static Request request(Fixture fixture, UUID readId) {
    return new Request(
        readId,
        NAMESPACE,
        fixture.tuple().canonicalBytes(),
        fixture.ownerAttemptId(),
        OWNER_MUTATION_ID,
        OWNER_FENCE);
  }

  private static byte[] accountProjection(StartSessionPostAuthorizationExecutionTuple tuple) {
    StartSessionAuthorityEvidenceBundle bundle =
        StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
    return AccountControlUiAuthority.canonical(
        Map.of(
            "projectionSchemaId",
            "accountStartSessionRedemptionProjection",
            "projectionSchemaVersion",
            "1",
            "authorizationReferenceFingerprint",
            tuple.authorizationReferenceFingerprint(),
            "authorityEvidenceBundle",
            bundle.jsonValue(),
            "issuanceOperationId",
            bundle.issuanceOperationId().toString(),
            "issuanceFence",
            9L));
  }

  private static Instant databaseNow(DSLContext dsl) {
    Record row =
        Objects.requireNonNull(
            dsl.fetchOne("SELECT clock_timestamp()"), "database clock query returned no row");
    return row.get(0, OffsetDateTime.class).toInstant();
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private record Fixture(
      StartSessionPostAuthorizationExecutionTuple tuple,
      UUID tenantId,
      UUID gameInstanceId,
      UUID ownerAttemptId,
      String preparationJson,
      String preparationDigest,
      byte[] accountProjection) {
    private Fixture {
      accountProjection = accountProjection.clone();
    }

    @Override
    public byte[] accountProjection() {
      return accountProjection.clone();
    }
  }
}
