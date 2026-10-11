package integration.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.gamesession.client.StartSessionOperatorRedemptionClient;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptClaim;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.ReservationResult;
import net.firedevops.firemud.gamesession.service.GameSessionStartSessionOperatorAuthorizationCoordinator;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL proof of coordinator ordering, exact retry, ambiguity, and stale-claim rejection. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionStartSessionOperatorAuthorizationCoordinatorIntegrationTest {
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
  private static final String OPAQUE_REFERENCE = "A".repeat(43);
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void postgresClaimIsCommittedBeforeAccountAndExactRetryDoesNotRedeemAgain() throws Exception {
    Fixture fixture = fixture(Duration.ofSeconds(30));
    StartSessionPostAuthorizationExecutionTuple tuple = tuple("coordinator-committed-retry");
    StartSessionOperatorRedemptionClient redemptionClient = mockRedemptionClient();
    AtomicInteger accountCalls = new AtomicInteger();
    when(redemptionClient.redeem(any(), eq(OPAQUE_REFERENCE), any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(fixture.attemptCount())
                  .as("the claim must be visible from an independent PostgreSQL connection")
                  .isEqualTo(1);
              accountCalls.incrementAndGet();
              StartSessionPostAuthorizationExecutionTuple deliveredTuple =
                  invocation.getArgument(0);
              AttemptClaim claim = invocation.getArgument(2);
              return new StartSessionOperatorRedemptionClient.RedemptionResult(
                  claim, projection(deliveredTuple), false);
            });
    GameSessionStartSessionOperatorAuthorizationCoordinator coordinator =
        coordinator(fixture, redemptionClient);

    var first = withLoggingPeer(() -> coordinator.authorize(tuple, OPAQUE_REFERENCE));

    assertThat(first.progress())
        .isEqualTo(
            GameSessionStartSessionOperatorAuthorizationCoordinator.Progress
                .ACCOUNT_PROJECTION_ATTACHED);
    assertThat(fixture.attemptCount()).isEqualTo(1);
    AttemptSnapshot persisted = fixture.reserve(tuple).snapshot();
    assertThat(persisted.accountRedemptionProjection())
        .containsExactly(projection(tuple).canonicalBytes());

    var retry = withLoggingPeer(() -> coordinator.authorize(tuple, OPAQUE_REFERENCE));

    assertThat(retry.progress())
        .isEqualTo(GameSessionStartSessionOperatorAuthorizationCoordinator.Progress.EXACT_REPLAY);
    assertThat(retry.claim()).isEmpty();
    assertThat(retry.snapshot().ownerAttemptId()).isEqualTo(first.snapshot().ownerAttemptId());
    assertThat(accountCalls.get()).isEqualTo(1);
    verify(redemptionClient, times(1)).redeem(eq(tuple), eq(OPAQUE_REFERENCE), any());
    assertThat(fixture.attemptCount()).isEqualTo(1);
  }

  @Test
  void ambiguousAccountTransportStaysPendingAndExactRetryDoesNotCallAccountAgain()
      throws Exception {
    Fixture fixture = fixture(Duration.ofSeconds(30));
    StartSessionPostAuthorizationExecutionTuple tuple = tuple("coordinator-ambiguous");
    StartSessionOperatorRedemptionClient redemptionClient = mockRedemptionClient();
    AtomicInteger accountCalls = new AtomicInteger();
    when(redemptionClient.redeem(any(), eq(OPAQUE_REFERENCE), any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(fixture.attemptCount()).isEqualTo(1);
              accountCalls.incrementAndGet();
              throw new StatusRuntimeException(Status.DEADLINE_EXCEEDED);
            });
    GameSessionStartSessionOperatorAuthorizationCoordinator coordinator =
        coordinator(fixture, redemptionClient);

    var first = withLoggingPeer(() -> coordinator.authorize(tuple, OPAQUE_REFERENCE));

    assertThat(first.progress())
        .isEqualTo(
            GameSessionStartSessionOperatorAuthorizationCoordinator.Progress
                .ACCOUNT_OUTCOME_AMBIGUOUS);
    assertThat(first.snapshot().accountRedemptionProjection()).isNull();
    assertThat(fixture.attemptCount()).isEqualTo(1);
    AttemptSnapshot persisted = fixture.reserve(tuple).snapshot();
    assertThat(persisted.accountRedemptionProjection()).isNull();

    var retry = withLoggingPeer(() -> coordinator.authorize(tuple, OPAQUE_REFERENCE));

    assertThat(retry.progress())
        .isEqualTo(GameSessionStartSessionOperatorAuthorizationCoordinator.Progress.EXACT_REPLAY);
    assertThat(retry.snapshot().accountRedemptionProjection()).isNull();
    assertThat(accountCalls.get()).isEqualTo(1);
    verify(redemptionClient, times(1)).redeem(eq(tuple), eq(OPAQUE_REFERENCE), any());
    assertThat(fixture.attemptCount()).isEqualTo(1);
  }

  @Test
  void expiredRealPostgresClaimCannotPersistTheReturnedAccountProjection() throws Exception {
    Duration lease = Duration.ofSeconds(2);
    Fixture fixture = fixture(lease);
    StartSessionPostAuthorizationExecutionTuple tuple = tuple("coordinator-stale-claim");
    StartSessionOperatorRedemptionClient redemptionClient = mockRedemptionClient();
    AtomicInteger accountCalls = new AtomicInteger();
    when(redemptionClient.redeem(any(), eq(OPAQUE_REFERENCE), any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(fixture.attemptCount()).isEqualTo(1);
              accountCalls.incrementAndGet();
              StartSessionPostAuthorizationExecutionTuple deliveredTuple =
                  invocation.getArgument(0);
              AttemptClaim claim = invocation.getArgument(2);
              fixture.awaitClaimLeaseExpired(claim);
              return new StartSessionOperatorRedemptionClient.RedemptionResult(
                  claim, projection(deliveredTuple), false);
            });
    GameSessionStartSessionOperatorAuthorizationCoordinator coordinator =
        coordinator(fixture, redemptionClient);

    assertThatThrownBy(() -> withLoggingPeer(() -> coordinator.authorize(tuple, OPAQUE_REFERENCE)))
        .isInstanceOf(
            GameSessionStartSessionOperatorAttemptRepository
                .StaleStartSessionOperatorAttemptClaimException.class)
        .hasMessageContaining("expired");

    assertThat(accountCalls.get()).isEqualTo(1);
    AttemptSnapshot persisted = fixture.reserve(tuple).snapshot();
    assertThat(persisted.accountRedemptionProjection()).isNull();
    assertThat(fixture.attemptCount()).isEqualTo(1);

    var retry = withLoggingPeer(() -> coordinator.authorize(tuple, OPAQUE_REFERENCE));
    assertThat(retry.progress())
        .isEqualTo(GameSessionStartSessionOperatorAuthorizationCoordinator.Progress.EXACT_REPLAY);
    assertThat(retry.snapshot().accountRedemptionProjection()).isNull();
    assertThat(accountCalls.get()).isEqualTo(1);
    verify(redemptionClient, times(1)).redeem(eq(tuple), eq(OPAQUE_REFERENCE), any());
  }

  private static GameSessionStartSessionOperatorAuthorizationCoordinator coordinator(
      Fixture fixture, StartSessionOperatorRedemptionClient redemptionClient) {
    return new GameSessionStartSessionOperatorAuthorizationCoordinator(
        fixture.repository, redemptionClient, fixture.transactionManager, NAMESPACE);
  }

  private static StartSessionOperatorRedemptionClient mockRedemptionClient() {
    return mock(StartSessionOperatorRedemptionClient.class);
  }

  private static StartSessionPostAuthorizationExecutionTuple tuple(String requestId) {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
            new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "Game Session operator authorization coordinator PostgreSQL proof");
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(requestId, ACTOR, action);
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        WORKLOAD,
        FINGERPRINT,
        RESERVATION_OWNER,
        19L,
        authorityBundle(pre),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    String tenantId = TENANT.toString();
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
            Map.of(
                "sourceType", "ACCOUNT",
                "sourceEvidenceId", "sha256:" + "a".repeat(64),
                "sourceEvidenceVersion", "17",
                "projectionStatus", "CURRENT",
                "evaluatedAt", "2026-10-09T00:00:00Z",
                "expiresAt", "2026-10-09T00:05:00Z"),
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
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            Map.of(
                "issuerAuthGeneration", 1L,
                "accountAuthorityGeneration", 2L,
                "tenantAuthorityGeneration", Map.of(tenantId, 3L),
                "membershipAuthorityGeneration", Map.of(tenantId, 4L),
                "privateRealmGrantVersions", List.of()),
            "membershipVersion",
            Map.of(tenantId, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
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
                "3"));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException exception) {
      throw new IllegalStateException(
          "could not create canonical test authority bundle", exception);
    }
  }

  private static AccountRedemptionProjection projection(
      StartSessionPostAuthorizationExecutionTuple tuple) {
    StartSessionAuthorityEvidenceBundle bundle =
        StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
    return new AccountRedemptionProjection(
        tuple.authorizationReferenceFingerprint(),
        tuple.authorityEvidenceBundleBytes(),
        bundle.issuanceOperationId(),
        Long.parseLong(tuple.issuanceFence()));
  }

  private static GrpcPeerIdentity peer() {
    return GrpcPeerIdentity.parseUri(WORKLOAD).orElseThrow();
  }

  private static <T> T withLoggingPeer(ThrowingSupplier<T> work) throws Exception {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer());
    Context previous = context.attach();
    try {
      return work.get();
    } finally {
      context.detach(previous);
    }
  }

  private static Fixture fixture(Duration claimLease) {
    String schema = "gs_operator_coordinator_" + UUID.randomUUID().toString().replace("-", "");
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
        dsl,
        transactionManager,
        transactions,
        new GameSessionStartSessionOperatorAttemptRepository(dsl, claimLease));
  }

  @FunctionalInterface
  private interface ThrowingSupplier<T> {
    T get() throws Exception;
  }

  private record Fixture(
      DSLContext dsl,
      DataSourceTransactionManager transactionManager,
      TransactionTemplate transactions,
      GameSessionStartSessionOperatorAttemptRepository repository) {
    ReservationResult reserve(StartSessionPostAuthorizationExecutionTuple tuple) {
      return Objects.requireNonNull(
          transactions.execute(status -> repository.reserve(tuple)),
          "StartSession owner-attempt reservation result");
    }

    int attemptCount() {
      return dsl.fetchCount(DSL.table(DSL.name("game_session_start_session_operator_attempt")));
    }

    void awaitClaimLeaseExpired(AttemptClaim claim) throws InterruptedException {
      long deadlineNanos = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (!claimLeaseExpired(claim) && System.nanoTime() < deadlineNanos) {
        Thread.sleep(10L);
      }
      assertThat(claimLeaseExpired(claim))
          .as("PostgreSQL must report the exact original owner claim lease expired")
          .isTrue();
    }

    private boolean claimLeaseExpired(AttemptClaim claim) {
      Record validity =
          dsl.fetchOne(
              "SELECT lease_expires_at <= clock_timestamp() AS expired "
                  + "FROM game_session_start_session_operator_attempt "
                  + "WHERE target_namespace = ? AND control_plane_request_id = ? "
                  + "AND owner_attempt_id = ? AND owner_mutation_id = ? "
                  + "AND claim_owner_id = ? AND owner_fence = ?",
              claim.targetNamespace(),
              claim.controlPlaneRequestId(),
              claim.ownerAttemptId(),
              claim.ownerMutationId(),
              claim.claimOwnerId(),
              claim.ownerFence());
      return validity != null && Boolean.TRUE.equals(validity.get("expired", Boolean.class));
    }
  }
}
