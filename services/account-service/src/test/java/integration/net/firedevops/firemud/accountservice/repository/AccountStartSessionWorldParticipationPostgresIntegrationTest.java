package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiOriginalOrderFixture;
import net.firedevops.firemud.accountservice.service.session.AccountOriginalStartSessionAuthorizationProofFixture;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationAcquisitionService.AcquisitionRequest;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationHistoricalReadService;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationHistoricalReadEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationHistoricalReadRequest;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proof for Account World participation guards, storage, and original-tuple recovery.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountStartSessionWorldParticipationPostgresIntegrationTest {
  private static final String NAMESPACE = "control-ui-owner-proof";
  private static final String WORLD_PEER_URI =
      "spiffe://firemud/ns/control-ui-owner-proof/sa/world-management-service";
  private static final long GAME_TEMPLATE_ID = 42L;
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("world-participation-primary")
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always");

  @Container
  static final GenericContainer<?> replica =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .dependsOn(redis)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always",
              "--replicaof",
              "world-participation-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void originalTupleRecoversCommittedParticipationAfterLostResponseAndLeaseExpiry()
      throws Exception {
    try (var controlUi =
        new AccountControlUiOriginalOrderFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            temporary)) {
      var proof =
          new AccountOriginalStartSessionAuthorizationProofFixture(controlUi, temporary, NAMESPACE);
      String requestId = "tuple-recovery-" + UUID.randomUUID();
      UUID reservationOwnerId = UUID.randomUUID();
      UUID gameSessionAttemptId = UUID.randomUUID();
      UUID gameSessionMutationId = UUID.randomUUID();
      UUID gameInstanceId = UUID.randomUUID();
      long reservationClaimFence = 31L;
      long gameSessionOwnerFence = 47L;
      var action =
          new StartSessionOperatorAction(
              StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
              StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
              new StartSessionOperatorAction.Scope(controlUi.tenantId(), NAMESPACE),
              new StartSessionOperatorAction.Target(GAME_TEMPLATE_ID, proof.creatorAccountId()),
              StartSessionOperatorAction.ExpectedVersion.ABSENT,
              new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
              "test-only lost World participation response recovery");
      var original =
          proof.issueAndRedeemOriginal(
              requestId,
              action,
              reservationOwnerId,
              reservationClaimFence,
              gameSessionAttemptId,
              gameSessionOwnerFence);
      byte[] originalTupleBytes = original.originalTuple().canonicalBytes();
      String preparationInput =
          preparationInput(
              NAMESPACE,
              controlUi.tenantId(),
              requestId,
              gameInstanceId,
              "tuple-recovery-world",
              GAME_TEMPLATE_ID);
      String preparationDigest = digest(preparationInput);
      // Bounded synthetic headroom lets initial retention reach its currentness gate; later reads
      // use this same expiry.
      Instant leaseExpiresAt =
          Instant.ofEpochMilli(databaseNow(proof.sourceDsl()).toEpochMilli()).plusSeconds(10L);
      // The Game Session point-in-time observation is stipulated; Account's original issue,
      // redemption, source capture, acquisition gates, and PostgreSQL retention are real.
      var acquisition =
          proof.worldParticipationAcquisitionService(
              request ->
                  new Result(
                      request,
                      leaseExpiresAt,
                      StartSessionAccountRedemptionProjection.fromOriginalTuple(
                          original.originalTuple())));

      // Discard the owner response to model transport loss. The database row is used only as a
      // physical proof oracle; it is not supplied to the historical lookup request.
      withWorldPeer(
          () ->
              acquisition.acquire(
                  new AcquisitionRequest(
                      originalTupleBytes,
                      gameSessionMutationId,
                      gameSessionAttemptId,
                      gameSessionOwnerFence,
                      gameInstanceId,
                      preparationInput,
                      preparationDigest)));

      var sql =
          physicalSql(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), proof);
      awaitDatabaseExpiry(sql.dsl(), leaseExpiresAt);
      var committedRow =
          Objects.requireNonNull(
              sql.dsl()
                  .fetchOne(
                      "SELECT participation_id, participation_fence "
                          + "FROM account_start_session_world_participations "
                          + "WHERE control_plane_request_id = ?",
                      requestId),
              "Committed participation must remain physically retained");
      UUID committedParticipationId = committedRow.get("participation_id", UUID.class);
      long committedParticipationFence = committedRow.get("participation_fence", Long.class);
      var transaction = new TransactionTemplate(sql.transactionManager());
      transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      var attemptRepository =
          new AccountStartSessionWorldOriginalAttemptEvidenceRepository(sql.dsl());
      var expiredParticipation =
          transaction.execute(
              status ->
                  new AccountStartSessionWorldParticipationRepository(sql.dsl())
                      .findHistoricalExact(committedParticipationId, committedParticipationFence)
                      .orElseThrow());
      var expiredAttemptRequest =
          new Request(
              UUID.randomUUID(),
              NAMESPACE,
              originalTupleBytes,
              gameSessionAttemptId,
              gameSessionMutationId,
              gameSessionOwnerFence);
      assertThatThrownBy(
              () ->
                  transaction.execute(
                      status ->
                          attemptRepository
                              .findCurrentExact(expiredParticipation, expiredAttemptRequest)
                              .orElseThrow()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("unavailable");
      var retainedAttempt =
          transaction.execute(
              status ->
                  attemptRepository
                      .findHistoricalExact(expiredParticipation, expiredAttemptRequest)
                      .orElseThrow());
      assertThat(retainedAttempt.originalLeaseExpiresAt()).isEqualTo(leaseExpiresAt);
      assertThat(retainedAttempt.result().request().canonicalPostAuthorizationTuple())
          .containsExactly(originalTupleBytes);

      var historicalService =
          new AccountStartSessionWorldParticipationHistoricalReadService(
              new AccountStartSessionWorldParticipationRepository(sql.dsl()),
              sql.transactionManager(),
              NAMESPACE);
      var historicalRequest =
          new AccountStartSessionWorldParticipationHistoricalReadRequest(
              UUID.randomUUID(), NAMESPACE, originalTupleBytes);
      AccountStartSessionWorldParticipationHistoricalReadEvidence recovered =
          withWorldPeer(() -> historicalService.read(historicalRequest));
      assertThat(recovered.accountWorldParticipationId()).isEqualTo(committedParticipationId);
      assertThat(recovered.accountWorldParticipationFence()).isEqualTo(committedParticipationFence);
      assertThat(recovered.gameSessionOwnerAttemptId()).isEqualTo(gameSessionAttemptId);
      assertThat(recovered.gameSessionOwnerFence()).isEqualTo(gameSessionOwnerFence);
      assertThat(recovered.canonicalGameInstanceId()).isEqualTo(gameInstanceId);
      assertThat(recovered.preparationInputJson()).isEqualTo(preparationInput);
      assertThat(recovered.preparationInputDigest()).isEqualTo(preparationDigest);
      assertThat(historicalRequest.readRequestId())
          .isNotEqualTo(original.originalTuple().preAuthorizationTuple().actor().accountId());
      assertThat(
              sql.dsl()
                  .fetchCount(
                      DSL.table("account_start_session_world_participation_sources"),
                      DSL.field("participation_id").eq(committedParticipationId)))
          .isPositive();

      var changedTuple =
          StartSessionPostAuthorizationExecutionTuple.createHuman(
              original.originalTuple().preAuthorizationTuple(),
              original.originalTuple().authenticatedWorkloadIdentity(),
              changedFingerprint(original.originalTuple().authorizationReferenceFingerprint()),
              original.originalTuple().reservationOwnerId(),
              original.originalTuple().reservationClaimFence(),
              original.originalTuple().authorityEvidenceBundleBytes(),
              original.originalTuple().bundleReference());
      var changedTupleRequest =
          new AccountStartSessionWorldParticipationHistoricalReadRequest(
              UUID.randomUUID(), NAMESPACE, changedTuple.canonicalBytes());
      assertThatThrownBy(() -> withWorldPeer(() -> historicalService.read(changedTupleRequest)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Exact StartSession World participation evidence unavailable");

      var absentOriginal =
          proof.issueAndRedeemOriginal(
              "tuple-recovery-absent-" + UUID.randomUUID(),
              action,
              UUID.randomUUID(),
              reservationClaimFence + 1L,
              UUID.randomUUID(),
              gameSessionOwnerFence + 1L);
      var missingTupleRequest =
          new AccountStartSessionWorldParticipationHistoricalReadRequest(
              UUID.randomUUID(), NAMESPACE, absentOriginal.originalTuple().canonicalBytes());
      assertThatThrownBy(() -> withWorldPeer(() -> historicalService.read(missingTupleRequest)))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
    }
  }

  @Test
  void latestMigrationsInstallParticipationGuardsAndPreserveAssembledSourceExclusions() {
    String schema = "ss_world_part_" + UUID.randomUUID().toString().replace("-", "");
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
    DSLContext dsl = DSL.using(source, SQLDialect.POSTGRES);

    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM unnest(ARRAY["
                            + "'account_start_session_world_participations', "
                            + "'account_start_session_world_participation_sources', "
                            + "'account_start_session_world_participation_settlements']::TEXT[]) "
                            + "AS names(name) WHERE to_regclass(name) IS NOT NULL"),
                    "participation table inventory query must return a row")
                .get(0, Long.class))
        .isEqualTo(3L);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT attidentity FROM pg_attribute "
                            + "WHERE attrelid = 'account_start_session_world_participations'::regclass "
                            + "AND attname = 'participation_fence' AND NOT attisdropped"),
                    "participation fence column must exist")
                .get(0, String.class))
        .isEqualTo("a");
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND conrelid IN ("
                            + "'account_start_session_world_participations'::regclass, "
                            + "'account_start_session_world_participation_sources'::regclass, "
                            + "'account_start_session_world_participation_settlements'::regclass)"),
                    "participation foreign-key query must return a row")
                .get(0, Long.class))
        .isEqualTo(6L);

    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal "
                            + "AND tgname = 'account_start_session_world_participation_complete' "
                            + "AND tgrelid = 'account_start_session_world_participations'::regclass "
                            + "AND tgconstraint <> 0 AND tgdeferrable AND tginitdeferred"),
                    "deferred participation completion trigger query must return a row")
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal "
                            + "AND tgrelid IN ('account_start_session_world_participations'::regclass, "
                            + "'account_start_session_world_participation_sources'::regclass, "
                            + "'account_start_session_world_participation_settlements'::regclass) "
                            + "AND tgname IN ("
                            + "'account_start_session_world_participation_validate', "
                            + "'account_start_session_world_participation_immutable', "
                            + "'account_start_session_world_participation_no_truncate', "
                            + "'account_start_session_world_participation_sources_insert', "
                            + "'account_start_session_world_participation_sources_immutable', "
                            + "'account_start_session_world_participation_sources_no_truncate', "
                            + "'account_start_session_world_participation_complete', "
                            + "'account_start_session_world_participation_settlement_validate', "
                            + "'account_start_session_world_participation_settlements_immutable', "
                            + "'account_start_session_world_settlement_no_truncate')"),
                    "participation protection trigger query must return a row")
                .get(0, Long.class))
        .isEqualTo(10L);

    String holdGuard = functionDefinition(dsl, "account_control_ui_hold_required_sources(text[])");
    assertThat(holdGuard)
        .contains(
            "account_draft_authorization_sources",
            "account_draft_authorization_is_settled(operation_value)",
            "account_selected_publication_sources",
            "account_selected_publication_authorizations",
            "account_game_logic_intake_sources",
            "account_game_logic_intake_authorizations",
            "account_game_logic_intake_source_read_sources",
            "account_game_logic_intake_source_read_is_pending(s.operation_id)",
            "account_start_session_world_participations participation",
            "account_start_session_world_participation_is_settled(participation.participation_id)",
            "FOR UPDATE OF participation NOWAIT");
    assertThat(holdGuard.indexOf("FOR UPDATE NOWAIT"))
        .isLessThan(holdGuard.indexOf("FROM account_start_session_world_participations"));

    String sourceChangeGuard =
        functionDefinition(dsl, "account_draft_authorization_source_change_guard()");
    assertThat(sourceChangeGuard)
        .contains(
            "account_draft_authorization_changed_scopes",
            "account_draft_authorization_is_settled(operation)",
            "account_start_session_world_participation_is_settled(participation.participation_id)",
            "Pending StartSession World participation excludes source-change completion");

    String disclosureGuard =
        functionDefinition(dsl, "account_hosted_terms_disclosure_handoff_guard()");
    assertThat(disclosureGuard)
        .contains(
            "OLD.status IN ('PREPARED', 'AMBIGUOUS')",
            "NEW.status = 'DISPATCH_AUTHORIZED'",
            "OLD.status = 'DISPATCH_AUTHORIZED' AND NEW.status = 'AMBIGUOUS'",
            "account_hosted_terms_disclosure_sources",
            "account_start_session_world_participation_is_settled(participation.participation_id)",
            "Pending StartSession World participation excludes hosted-terms dispatch");

    String acquisitionExclusion =
        functionDefinition(
            dsl, "account_start_session_world_participation_assert_exclusive(text[],jsonb)");
    assertThat(acquisitionExclusion)
        .contains(
            "change.status = 'WAITING'",
            "source_key_value LIKE 'HOSTED_TERMS:%'",
            "handoff.source_key = source_key_value",
            "handoff_source.source_key = handoff.source_key",
            "handoff_source.source_evidence = evidence_value",
            "handoff.status IN ('DISPATCH_AUTHORIZED', 'AMBIGUOUS', 'DISCLOSED')");
    assertThat(acquisitionExclusion).doesNotContain("handoff.source_key = ANY(source_keys)");

    for (String table :
        new String[] {
          "account_start_session_world_participations",
          "account_start_session_world_participation_sources",
          "account_start_session_world_participation_settlements"
        }) {
      assertGuardRejected(
          () -> dsl.execute("TRUNCATE " + table + " CASCADE"),
          "StartSession World participation evidence is append-only");
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne("SELECT count(*) FROM " + table),
                      "post-TRUNCATE table state query must return a row")
                  .get(0, Long.class))
          .as("rejected TRUNCATE must leave the empty table unchanged")
          .isZero();
    }
  }

  private static void assertGuardRejected(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String expectedDiagnostic) {
    assertThatThrownBy(action)
        .satisfies(
            failure -> {
              SQLException sqlFailure = findSqlException(failure);
              assertThat(sqlFailure.getSQLState()).isEqualTo("23514");
              if (!(sqlFailure instanceof org.postgresql.util.PSQLException postgresFailure)) {
                throw new AssertionError(
                    "Participation guard rejection must be a PostgreSQL exception", sqlFailure);
              }
              var serverError = postgresFailure.getServerErrorMessage();
              assertThat(serverError).isNotNull();
              assertThat(serverError.getMessage()).isEqualTo(expectedDiagnostic);
            });
  }

  private static SQLException findSqlException(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException) return sqlException;
    }
    throw new AssertionError("Guard rejection must retain its SQL exception", failure);
  }

  private static String functionDefinition(DSLContext dsl, String signature) {
    return Objects.requireNonNull(
            dsl.fetchOne("SELECT pg_get_functiondef(?::regprocedure)", signature),
            "function definition query must return a row")
        .get(0, String.class);
  }

  private static PhysicalSql physicalSql(
      String jdbcUrl,
      String username,
      String password,
      AccountOriginalStartSessionAuthorizationProofFixture proof) {
    String schema =
        Objects.requireNonNull(
                proof.sourceDsl().fetchOne("SELECT current_schema() AS schema_name"),
                "Account test schema must be available")
            .get("schema_name", String.class);
    DriverManagerDataSource source = new DriverManagerDataSource(jdbcUrl, username, password);
    source.setSchema(schema);
    return new PhysicalSql(
        new DataSourceTransactionManager(source),
        DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES));
  }

  private record PhysicalSql(DataSourceTransactionManager transactionManager, DSLContext dsl) {}

  private static Instant databaseNow(DSLContext dsl) {
    return Objects.requireNonNull(
            dsl.fetchOne("SELECT clock_timestamp() AS observed_at"),
            "PostgreSQL clock must return the current instant")
        .get("observed_at", OffsetDateTime.class)
        .toInstant();
  }

  private static void awaitDatabaseExpiry(DSLContext dsl, Instant expiresAt) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15L);
    while (!databaseNow(dsl).isAfter(expiresAt)) {
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException("PostgreSQL clock did not pass the retained lease expiry");
      }
      Thread.sleep(25L);
    }
  }

  private static <T> T withWorldPeer(Supplier<T> action) {
    Context current = Context.current();
    Context scoped =
        current.withValue(
            GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(WORLD_PEER_URI).orElseThrow());
    Context previous = scoped.attach();
    try {
      return action.get();
    } finally {
      scoped.detach(previous);
    }
  }

  private static String changedFingerprint(String fingerprint) {
    char finalCharacter = fingerprint.charAt(fingerprint.length() - 1);
    return fingerprint.substring(0, fingerprint.length() - 1) + (finalCharacter == '0' ? '1' : '0');
  }

  private static String digest(String value) {
    try {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static String preparationInput(
      String namespace,
      UUID tenantId,
      String requestId,
      UUID gameInstanceId,
      String worldSlug,
      long gameTemplateId)
      throws Exception {
    var json = tools.jackson.databind.json.JsonMapper.builder().build();
    String descriptorJson = json.writeValueAsString(Map.of("gameTemplateId", gameTemplateId));
    return json.writeValueAsString(
        Map.of(
            "schemaVersion",
            1,
            "identity",
            Map.of(
                "targetNamespace",
                namespace,
                "canonicalTenantId",
                tenantId.toString(),
                "controlPlaneRequestId",
                requestId,
                "canonicalGameInstanceId",
                gameInstanceId.toString(),
                "worldSlug",
                worldSlug),
            "gameSessionReadRequest",
            Map.of(
                "targetNamespace",
                namespace,
                "canonicalTenantId",
                tenantId.toString(),
                "controlPlaneRequestId",
                requestId,
                "canonicalGameInstanceId",
                gameInstanceId.toString(),
                "worldSlug",
                worldSlug),
            "gameSessionReadEvidence",
            Map.of(
                "targetNamespace",
                namespace,
                "canonicalTenantId",
                tenantId.toString(),
                "controlPlaneRequestId",
                requestId,
                "canonicalGameInstanceId",
                gameInstanceId.toString(),
                "worldSlug",
                worldSlug,
                "descriptorJson",
                descriptorJson),
            "launchBinding",
            Map.of(
                "targetNamespace",
                namespace,
                "canonicalTenantId",
                tenantId.toString(),
                "controlPlaneRequestId",
                requestId,
                "worldSlug",
                worldSlug)));
  }
}
