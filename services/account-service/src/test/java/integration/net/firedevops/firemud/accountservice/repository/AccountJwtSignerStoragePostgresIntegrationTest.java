package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.account.v1.RecordGenerationResultRequest;
import net.firedevops.firemud.account.v1.RecordGenerationResultResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.EnrollmentIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.security.AccountJwtSignerMaterializerTlsInterceptor;
import net.firedevops.firemud.accountservice.service.impl.AccountJwtSignerMaterializationService;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Physical PostgreSQL proof for Account-owned signer desired-state and materialization receipts.
 */
class AccountJwtSignerStoragePostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "jwt_signer_owner_storage";
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();
  private static final Binding BINDING =
      new Binding(
          "prod",
          "prod-cluster-1",
          "firemud-prod",
          CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
  private static final String CLUSTER_UID = "11111111-1111-4111-8111-111111111111";
  private static final String NAMESPACE_UID = "22222222-2222-4222-8222-222222222222";
  private static final String SECRET_UID = "33333333-3333-4333-8333-333333333333";
  private static final String TRUST_DIGEST = "b".repeat(64);
  private static final String TRUST_REVISION = "trust-revision-1";
  private static final String API_DIGEST = "d".repeat(64);
  private static final String API_REVISION = "api-revision-1";
  private static final String CONFIG_MAP_UID = "44444444-4444-4444-8444-444444444444";
  private static final String INITIAL_CONFIG_MAP_SNAPSHOT_DIGEST = "e".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Set<String> schemas = ConcurrentHashMap.newKeySet();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @AfterEach
  void dropRunOwnedSchemas() {
    JdbcTemplate jdbc = new JdbcTemplate(POSTGRES.dataSource());
    for (String schema : schemas) {
      if (!schema.startsWith(SCHEMA_PREFIX + "_") || !schema.matches("[a-z][a-z0-9_]{0,62}")) {
        throw new IllegalStateException("Refusing to clean an unowned PostgreSQL schema");
      }
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
    schemas.clear();
  }

  @Test
  void materializationReadbackIsDurableAndExactReplaysRejectChangedOrStaleEvidence()
      throws Exception {
    TestContext context = newTestContext(null);
    AccountJwtSignerDesiredStateRepository desired =
        new AccountJwtSignerDesiredStateRepository(context.dsl());
    TrustFence trust = trust();

    var initialized =
        inTransaction(context, () -> desired.initialize(BINDING, enrollmentIdentity()));
    assertThat(initialized.recordVersion()).isEqualTo(1L);
    assertThat(initialized.durableActive()).isEmpty();
    assertThat(initialized.publishedActive()).isEmpty();
    assertThat(initialized.generationOperationId()).isEmpty();
    assertThat(initialized.preparedOperationId()).isEmpty();

    GenerationRequest request =
        inTransaction(context, () -> desired.ensureCurrentGenerationRequest(BINDING, trust));
    assertThat(request.targetGeneration()).isEqualTo("1");
    assertThat(request.targetKid()).startsWith("jwt-1-");
    assertThat(request.publicJwkJson()).isEmpty();
    assertThat(request.publicKeyFingerprint()).isEmpty();

    String observedResourceVersion = "r".repeat(256);
    GenerationRequest observation =
        inTransaction(
            context,
            () ->
                desired.recordSecretObservation(
                    BINDING,
                    trust,
                    request.operationId(),
                    request.operationDigest(),
                    SECRET_UID,
                    observedResourceVersion));
    GenerationRequest observationReplay =
        inTransaction(
            context,
            () ->
                desired.recordSecretObservation(
                    BINDING,
                    trust,
                    request.operationId(),
                    request.operationDigest(),
                    SECRET_UID,
                    observedResourceVersion));
    assertThat(observationReplay).isEqualTo(observation);
    assertThat(observation.phase())
        .isEqualTo(AccountJwtSignerDesiredStateRepository.GenerationPhase.GENERATE_PENDING);
    assertThat(observation.generationRequestDigest()).hasSize(64);
    assertThat(observation.expectedSecretResourceVersion()).isEqualTo(observedResourceVersion);
    assertThat(observation.secretUid()).isEqualTo(SECRET_UID);

    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        desired.recordSecretObservation(
                            BINDING,
                            trust,
                            request.operationId(),
                            request.operationDigest(),
                            SECRET_UID,
                            "13")))
        .isInstanceOf(AccountJwtSignerDesiredStateRepository.IdempotencyConflictException.class);

    PublicJwk key = publicJwk(request.targetKid());
    GenerationResult result =
        inTransaction(
            context,
            () ->
                desired.recordGenerationResult(
                    BINDING,
                    trust,
                    request.operationId(),
                    observation.generationRequestDigest(),
                    SECRET_UID,
                    observedResourceVersion,
                    "13",
                    key.json()));
    GenerationResult resultReplay =
        inTransaction(
            context,
            () ->
                desired.recordGenerationResult(
                    BINDING,
                    trust,
                    request.operationId(),
                    observation.generationRequestDigest(),
                    SECRET_UID,
                    observedResourceVersion,
                    "13",
                    key.json()));
    assertThat(resultReplay).isEqualTo(result);
    assertThat(result.publicKeyFingerprint()).isEqualTo(key.fingerprint());
    assertThat(result.publicJwkJson()).doesNotContain("\"d\"");

    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        desired.recordGenerationResult(
                            BINDING,
                            trust,
                            request.operationId(),
                            observation.generationRequestDigest(),
                            SECRET_UID,
                            observedResourceVersion,
                            "14",
                            key.json())))
        .isInstanceOf(AccountJwtSignerDesiredStateRepository.IdempotencyConflictException.class);

    TrustFence staleTrust =
        new TrustFence(CLUSTER_UID, NAMESPACE_UID, "a".repeat(64), TRUST_REVISION);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        desired.recordGenerationResult(
                            BINDING,
                            staleTrust,
                            request.operationId(),
                            observation.generationRequestDigest(),
                            SECRET_UID,
                            observedResourceVersion,
                            "13",
                            key.json())))
        .isInstanceOf(AccountJwtSignerDesiredStateRepository.BindingMismatchException.class);

    AccountJwtSignerDesiredStateRepository restartedRepository =
        new AccountJwtSignerDesiredStateRepository(context.dsl());
    var persistedState = inTransaction(context, () -> restartedRepository.read(BINDING));
    GenerationRequest persistedRequest =
        inTransaction(
            context, () -> restartedRepository.readCurrentGenerationRequest(BINDING, trust));
    GenerationResult persistedResult =
        inTransaction(
            context, () -> restartedRepository.readCurrentGenerationResult(BINDING, trust));

    assertThat(persistedState.recordVersion()).isEqualTo(2L);
    assertThat(persistedState.durableActive()).isEmpty();
    assertThat(persistedState.publishedActive()).isEmpty();
    assertThat(persistedState.preparedOperationId()).isEmpty();
    assertThat(persistedState.generationOperationId()).contains(request.operationId());
    assertThat(persistedRequest.phase())
        .isEqualTo(AccountJwtSignerDesiredStateRepository.GenerationPhase.GENERATION_RECORDED);
    assertThat(persistedRequest.generationReceiptDigest()).isEqualTo(result.receiptDigest());
    assertThat(persistedRequest.publicJwkJson()).isEqualTo(key.json());
    assertThat(persistedResult).isEqualTo(result);

    assertThat(count(context, "account_jwt_signer_desired_states")).isEqualTo(1L);
    assertThat(count(context, "account_jwt_signer_generation_operations")).isEqualTo(1L);
    assertThat(count(context, "account_jwt_signer_secret_observations")).isEqualTo(1L);
    assertThat(count(context, "account_jwt_signer_generation_results")).isEqualTo(1L);
  }

  @Test
  void unpreparedGenerationAbortIsAnImmutableOwnerCasAndExactReplayAcrossRepositoryRestart()
      throws Exception {
    TestContext context = newTestContext(null);
    AccountJwtSignerDesiredStateRepository desired =
        new AccountJwtSignerDesiredStateRepository(context.dsl());
    TrustFence trust = trust();
    inTransaction(context, () -> desired.initialize(BINDING, enrollmentIdentity()));
    GenerationRequest request =
        inTransaction(context, () -> desired.ensureCurrentGenerationRequest(BINDING, trust));
    GenerationRequest observation =
        inTransaction(
            context,
            () ->
                desired.recordSecretObservation(
                    BINDING,
                    trust,
                    request.operationId(),
                    request.operationDigest(),
                    SECRET_UID,
                    "12"));
    PublicJwk key = publicJwk(request.targetKid());
    GenerationResult result =
        inTransaction(
            context,
            () ->
                desired.recordGenerationResult(
                    BINDING,
                    trust,
                    request.operationId(),
                    observation.generationRequestDigest(),
                    SECRET_UID,
                    "12",
                    "13",
                    key.json()));

    GenerationResult changedResult =
        new GenerationResult(
            result.operationId(),
            result.binding(),
            result.operationDigest(),
            result.generationRequestDigest(),
            result.desiredStateVersion(),
            result.trustFence(),
            result.privateSecretName(),
            result.secretUid(),
            result.expectedPriorResourceVersion(),
            result.observedResourceVersion(),
            result.targetGeneration(),
            result.targetKid(),
            result.targetAlgorithm(),
            result.publicKeyFingerprint(),
            result.publicJwkJson(),
            "f".repeat(64));
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () -> desired.abortUnpreparedGeneration(BINDING, trust, changedResult)))
        .isInstanceOf(AccountJwtSignerDesiredStateRepository.IdempotencyConflictException.class);

    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_jwt_signer_desired_states "
                                    + "SET record_version = record_version + 1, "
                                    + "generation_operation_id = NULL "
                                    + "WHERE environment_id = ?",
                                BINDING.environmentId())))
        .isInstanceOf(DataAccessException.class);

    var receipt =
        inTransaction(context, () -> desired.abortUnpreparedGeneration(BINDING, trust, result));
    assertThat(receipt.operationId()).isEqualTo(request.operationId());
    assertThat(receipt.operationDigest()).isEqualTo(request.operationDigest());
    assertThat(receipt.generationRequestDigest()).isEqualTo(result.generationRequestDigest());
    assertThat(receipt.generationReceiptDigest()).isEqualTo(result.receiptDigest());
    assertThat(receipt.expectedStateVersion()).isEqualTo(2L);
    assertThat(receipt.resultingStateVersion()).isEqualTo(3L);
    assertThat(receipt.durableActive()).isEmpty();
    assertThat(receipt.publishedActive()).isEmpty();

    AccountJwtSignerDesiredStateRepository restarted =
        new AccountJwtSignerDesiredStateRepository(context.dsl());
    assertThat(
            inTransaction(
                context, () -> restarted.abortUnpreparedGeneration(BINDING, trust, result)))
        .isEqualTo(receipt);
    var state = inTransaction(context, () -> restarted.read(BINDING));
    assertThat(state.recordVersion()).isEqualTo(3L);
    assertThat(state.generationOperationId()).isEmpty();
    assertThat(state.preparedOperationId()).isEmpty();
    assertThat(state.durableActive()).isEmpty();
    assertThat(state.publishedActive()).isEmpty();
    assertThat(count(context, "account_jwt_signer_generation_abort_receipts")).isEqualTo(1L);

    GenerationRequest next =
        inTransaction(context, () -> restarted.ensureCurrentGenerationRequest(BINDING, trust));
    assertThat(next.targetGeneration()).isEqualTo("2");
    assertThat(next.operationId()).isNotEqualTo(request.operationId());
    assertThat(next.expectedActive()).isEmpty();
    assertThat(next.expectedPublishedActive()).isEmpty();
  }

  @Test
  void parentV98UpgradePreservesRetainedAccountWithoutInventingSignerEnrollment() {
    TestContext context = newTestContext("98");
    String suffix = UUID.randomUUID().toString().replace("-", "");
    Long accountId =
        new JdbcTemplate(context.dataSource())
            .queryForObject(
                "INSERT INTO accounts (username, email, password_hash, role) "
                    + "VALUES (?, ?, ?, 'player') RETURNING id",
                Long.class,
                "signer-retained-" + suffix,
                "signer-retained-" + suffix + "@example.test",
                "retained-test-password");
    UUID accountUuid =
        new JdbcTemplate(context.dataSource())
            .queryForObject(
                "SELECT account_uuid FROM accounts WHERE id = ?", UUID.class, accountId);

    migrateLatest(context);

    assertThat(
            new JdbcTemplate(context.dataSource())
                .queryForObject(
                    "SELECT account_uuid FROM accounts WHERE id = ?", UUID.class, accountId))
        .isEqualTo(accountUuid);
    assertThat(
            new JdbcTemplate(context.dataSource())
                .queryForObject(
                    "SELECT count(*) FROM account_jwt_signer_desired_states", Long.class))
        .isZero();
    AccountJwtSignerDesiredStateRepository desired =
        new AccountJwtSignerDesiredStateRepository(context.dsl());
    assertThat(inTransaction(context, () -> desired.readEnrollmentState(BINDING))).isEmpty();
  }

  @Test
  void independentSqlEnrollmentInsertsRejectPartialAndMalformedProtectedIdentity() {
    TestContext context = newTestContext(null);
    AccountJwtSignerDesiredStateRepository desired =
        new AccountJwtSignerDesiredStateRepository(context.dsl());
    String validRow =
        context
            .transaction()
            .execute(
                status -> {
                  desired.initialize(BINDING, enrollmentIdentity());
                  String row = signerRow(context, "account_jwt_signer_desired_states");
                  status.setRollbackOnly();
                  return row;
                });
    assertThat(validRow).isNotNull();
    for (String field :
        List.of(
            "enrollment_cluster_incarnation_uid",
            "enrollment_namespace_uid",
            "enrollment_materializer_binding_digest",
            "enrollment_materializer_config_revision",
            "enrollment_api_binding_digest",
            "enrollment_api_config_revision",
            "enrollment_public_config_map_uid",
            "enrollment_public_config_map_resource_version",
            "enrollment_public_config_map_snapshot_digest")) {
      assertThatThrownBy(
              () ->
                  inTransaction(
                      context,
                      () ->
                          insertSignerRow(
                              context,
                              "account_jwt_signer_desired_states",
                              validRow,
                              "jsonb_build_object(?, NULL)",
                              field)))
          .as("independent partial enrollment INSERT: %s", field)
          .isInstanceOf(DataAccessException.class)
          .satisfies(failure -> assertThat(signerSqlState(failure)).isEqualTo("23514"));
      assertThat(count(context, "account_jwt_signer_desired_states")).isZero();
    }
    for (String field :
        List.of(
            "enrollment_materializer_binding_digest",
            "enrollment_api_binding_digest",
            "enrollment_public_config_map_snapshot_digest")) {
      assertThatThrownBy(
              () ->
                  inTransaction(
                      context,
                      () ->
                          insertSignerRow(
                              context,
                              "account_jwt_signer_desired_states",
                              validRow,
                              "jsonb_build_object(?, ?)",
                              field,
                              "not-a-sha256")))
          .as("independent malformed enrollment INSERT: %s", field)
          .isInstanceOf(DataAccessException.class)
          .satisfies(failure -> assertThat(signerSqlState(failure)).isEqualTo("23514"));
      assertThat(count(context, "account_jwt_signer_desired_states")).isZero();
    }
    inTransaction(context, () -> desired.initialize(BINDING, enrollmentIdentity()));
    assertThat(count(context, "account_jwt_signer_desired_states")).isEqualTo(1L);
  }

  @Test
  void rawResourceVersionWritesAndImmutableGenerationEvidenceMutationsAreRefused()
      throws Exception {
    TestContext context = newTestContext(null);
    AccountJwtSignerDesiredStateRepository desired =
        new AccountJwtSignerDesiredStateRepository(context.dsl());
    inTransaction(context, () -> desired.initialize(BINDING, enrollmentIdentity()));
    GenerationRequest request =
        inTransaction(context, () -> desired.ensureCurrentGenerationRequest(BINDING, trust()));
    String observationRow =
        context
            .transaction()
            .execute(
                status -> {
                  desired.recordSecretObservation(
                      BINDING,
                      trust(),
                      request.operationId(),
                      request.operationDigest(),
                      SECRET_UID,
                      "12");
                  String row = signerRow(context, "account_jwt_signer_secret_observations");
                  status.setRollbackOnly();
                  return row;
                });
    assertThat(observationRow).isNotNull();
    for (String invalid : List.of("", "r\n", "r\u0001", "r".repeat(257))) {
      assertThatThrownBy(
              () ->
                  inTransaction(
                      context,
                      () ->
                          insertSignerRow(
                              context,
                              "account_jwt_signer_secret_observations",
                              observationRow,
                              "jsonb_build_object('observed_resource_version', ?)",
                              invalid)))
          .isInstanceOf(DataAccessException.class)
          .satisfies(
              failure ->
                  assertThat(signerSqlState(failure))
                      .isEqualTo(invalid.length() > 256 ? "22001" : "23514"));
      assertThat(count(context, "account_jwt_signer_secret_observations")).isZero();
    }
    GenerationRequest observed =
        inTransaction(
            context,
            () ->
                desired.recordSecretObservation(
                    BINDING,
                    trust(),
                    request.operationId(),
                    request.operationDigest(),
                    SECRET_UID,
                    "12"));
    PublicJwk key = publicJwk(request.targetKid());
    String resultRow =
        context
            .transaction()
            .execute(
                status -> {
                  desired.recordGenerationResult(
                      BINDING,
                      trust(),
                      request.operationId(),
                      observed.generationRequestDigest(),
                      SECRET_UID,
                      "12",
                      "13",
                      key.json());
                  String row = signerRow(context, "account_jwt_signer_generation_results");
                  status.setRollbackOnly();
                  return row;
                });
    assertThat(resultRow).isNotNull();
    for (String field : List.of("expected_prior_resource_version", "observed_resource_version")) {
      for (String invalid : List.of("", "r\n", "r\u0001", "r".repeat(257))) {
        assertThatThrownBy(
                () ->
                    inTransaction(
                        context,
                        () ->
                            insertSignerRow(
                                context,
                                "account_jwt_signer_generation_results",
                                resultRow,
                                "jsonb_build_object(?, ?)",
                                field,
                                invalid)))
            .as("raw result resource version: %s", field)
            .isInstanceOf(DataAccessException.class)
            .satisfies(
                failure ->
                    assertThat(signerSqlState(failure))
                        .isEqualTo(invalid.length() > 256 ? "22001" : "23514"));
        assertThat(count(context, "account_jwt_signer_generation_results")).isZero();
      }
    }
    inTransaction(
        context,
        () ->
            desired.recordGenerationResult(
                BINDING,
                trust(),
                request.operationId(),
                observed.generationRequestDigest(),
                SECRET_UID,
                "12",
                "13",
                key.json()));
    Map<String, String> mutations =
        Map.of(
            "account_jwt_signer_generation_operations", "target_kid = 'changed'",
            "account_jwt_signer_secret_observations", "trust_config_revision = 'changed'",
            "account_jwt_signer_generation_results",
                "public_key_fingerprint = '" + "0".repeat(64) + "'");
    Map<String, String> retained = new LinkedHashMap<>();
    mutations.keySet().forEach(table -> retained.put(table, signerRow(context, table)));
    for (var mutation : mutations.entrySet()) {
      for (String sql :
          List.of(
              "UPDATE " + mutation.getKey() + " SET " + mutation.getValue(),
              "DELETE FROM " + mutation.getKey(),
              "TRUNCATE TABLE " + mutation.getKey(),
              "TRUNCATE TABLE " + mutation.getKey() + " CASCADE")) {
        assertThatThrownBy(() -> inTransaction(context, () -> context.dsl().execute(sql)))
            .as("immutable signer evidence: %s", sql)
            .isInstanceOf(DataAccessException.class)
            .satisfies(failure -> assertThat(signerSqlState(failure)).isIn("23514", "0A000"));
        retained.forEach((table, row) -> assertThat(signerRow(context, table)).isEqualTo(row));
      }
    }
  }

  @Test
  void rawGenerationResultTriggerRetainsOwnerLocksUntilTransactionCompletion() throws Exception {
    TestContext context = newTestContext(null);
    AccountJwtSignerDesiredStateRepository desired =
        new AccountJwtSignerDesiredStateRepository(context.dsl());
    inTransaction(context, () -> desired.initialize(BINDING, enrollmentIdentity()));
    GenerationRequest request =
        inTransaction(context, () -> desired.ensureCurrentGenerationRequest(BINDING, trust()));
    GenerationRequest observed =
        inTransaction(
            context,
            () ->
                desired.recordSecretObservation(
                    BINDING,
                    trust(),
                    request.operationId(),
                    request.operationDigest(),
                    SECRET_UID,
                    "12"));
    PublicJwk key = publicJwk(request.targetKid());
    String resultRow =
        context
            .transaction()
            .execute(
                status -> {
                  desired.recordGenerationResult(
                      BINDING,
                      trust(),
                      request.operationId(),
                      observed.generationRequestDigest(),
                      SECRET_UID,
                      "12",
                      "13",
                      key.json());
                  String row = signerRow(context, "account_jwt_signer_generation_results");
                  status.setRollbackOnly();
                  return row;
                });
    assertThat(resultRow).isNotNull();
    // Raw INSERT takes no repository locks: these locks must come from the result trigger.
    inTransaction(
        context,
        () -> {
          insertSignerRow(
              context, "account_jwt_signer_generation_results", resultRow, "'{}'::jsonb");
          for (String table :
              List.of(
                  "account_jwt_signer_desired_states",
                  "account_jwt_signer_generation_operations",
                  "account_jwt_signer_secret_observations")) {
            assertThatThrownBy(
                    () -> {
                      try (var connection = context.dataSource().getConnection()) {
                        DSL.using(connection, SQLDialect.POSTGRES)
                            .fetch("SELECT * FROM " + table + " FOR UPDATE NOWAIT");
                      }
                    })
                .as("result trigger retains %s on an independent physical connection", table)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("could not obtain lock");
          }
          return null;
        });
    try (var connection = context.dataSource().getConnection()) {
      assertThat(
              DSL.using(connection, SQLDialect.POSTGRES)
                  .fetch("SELECT * FROM account_jwt_signer_desired_states FOR UPDATE NOWAIT"))
          .hasSize(1);
    }
    assertThat(count(context, "account_jwt_signer_generation_results")).isEqualTo(1L);
  }

  @Test
  void protectedTrustMovementBeforeCommitRollsBackActualGenerationResult() throws Exception {
    TestContext context = newTestContext(null);
    AccountJwtSignerDesiredStateRepository desired =
        new AccountJwtSignerDesiredStateRepository(context.dsl());
    byte[] peerSpki = "controlled-materializer-peer".getBytes(StandardCharsets.US_ASCII);
    String peerUri = "spiffe://firemud/ns/firemud-prod/sa/jwt-signer-materializer";
    String pin = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(peerSpki));
    String digest =
        AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
            TRUST_REVISION,
            BINDING.environmentId(),
            BINDING.clusterId(),
            BINDING.namespace(),
            CLUSTER_UID,
            NAMESPACE_UID,
            peerUri,
            List.of(pin));
    var initial =
        new AccountJwtSignerMaterializerTrustBinding.Binding(
            BINDING.environmentId(),
            BINDING.clusterId(),
            BINDING.namespace(),
            CLUSTER_UID,
            NAMESPACE_UID,
            peerUri,
            List.of(pin),
            TRUST_REVISION,
            digest);
    var changed =
        new AccountJwtSignerMaterializerTrustBinding.Binding(
            BINDING.environmentId(),
            BINDING.clusterId(),
            BINDING.namespace(),
            CLUSTER_UID,
            NAMESPACE_UID,
            peerUri,
            List.of(pin),
            "trust-r2",
            AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
                "trust-r2",
                BINDING.environmentId(),
                BINDING.clusterId(),
                BINDING.namespace(),
                CLUSTER_UID,
                NAMESPACE_UID,
                peerUri,
                List.of(pin)));
    TrustFence initialTrust = new TrustFence(CLUSTER_UID, NAMESPACE_UID, digest, TRUST_REVISION);
    inTransaction(
        context,
        () ->
            desired.initialize(
                BINDING,
                new EnrollmentIdentity(
                    CLUSTER_UID,
                    NAMESPACE_UID,
                    digest,
                    TRUST_REVISION,
                    API_DIGEST,
                    API_REVISION,
                    CONFIG_MAP_UID,
                    "12",
                    INITIAL_CONFIG_MAP_SNAPSHOT_DIGEST)));
    GenerationRequest request =
        inTransaction(context, () -> desired.ensureCurrentGenerationRequest(BINDING, initialTrust));
    GenerationRequest observed =
        inTransaction(
            context,
            () ->
                desired.recordSecretObservation(
                    BINDING,
                    initialTrust,
                    request.operationId(),
                    request.operationDigest(),
                    SECRET_UID,
                    "12"));
    String retainedState = signerRow(context, "account_jwt_signer_desired_states");
    var provider = mock(AccountJwtSignerMaterializerTrustBinding.class);
    when(provider.current())
        .thenReturn(Optional.of(initial), Optional.of(initial))
        .thenAnswer(
            invocation -> {
              assertThat(count(context, "account_jwt_signer_generation_results"))
                  .as("the real INSERT is visible inside the transaction before trust moves")
                  .isEqualTo(1L);
              return Optional.of(changed);
            });
    var service =
        new AccountJwtSignerMaterializationService(
            desired,
            new AccountJwtReadinessProbeRepository(
                context.dsl(),
                desired,
                new AccountJwtJwksPublicationRepository(context.dsl(), desired)),
            provider,
            new DataSourceTransactionManager(context.dataSource()));
    PublicJwk key = publicJwk(request.targetKid());
    var rpcRequest =
        RecordGenerationResultRequest.newBuilder()
            .setSchemaVersion(1)
            .setOperationId(request.operationId().toString())
            .setGenerationRequestDigest(observed.generationRequestDigest())
            .setSecretUid(SECRET_UID)
            .setExpectedPriorResourceVersion("12")
            .setObservedResourceVersion("13")
            .setPublicJwkJson(key.json())
            .build();
    boolean[] delivered = {false};
    boolean[] completed = {false};
    Status.Code[] error = {null};
    StreamObserver<RecordGenerationResultResponse> response =
        new StreamObserver<>() {
          @Override
          public void onNext(RecordGenerationResultResponse value) {
            delivered[0] = true;
          }

          @Override
          public void onError(Throwable failure) {
            error[0] = Status.fromThrowable(failure).getCode();
          }

          @Override
          public void onCompleted() {
            completed[0] = true;
          }
        };
    @SuppressWarnings("unchecked")
    ServerCall<String, String> call = mock(ServerCall.class);
    SSLSession session = mock(SSLSession.class);
    var certificate = mock(java.security.cert.X509Certificate.class);
    var publicKey = mock(java.security.PublicKey.class);
    when(publicKey.getEncoded()).thenReturn(peerSpki);
    when(certificate.getPublicKey()).thenReturn(publicKey);
    when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, peerUri)));
    when(session.getPeerCertificates())
        .thenReturn(new java.security.cert.Certificate[] {certificate});
    when(call.getAttributes())
        .thenReturn(Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build());
    new AccountJwtSignerMaterializerTlsInterceptor(provider)
        .interceptCall(
            call,
            new Metadata(),
            (intercepted, headers) -> {
              service.recordGenerationResult(rpcRequest, response);
              return new ServerCall.Listener<>() {};
            });
    assertThat(error[0]).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(delivered[0]).isFalse();
    assertThat(completed[0]).isFalse();
    verify(provider, times(3)).current();
    assertThat(count(context, "account_jwt_signer_generation_results")).isZero();
    assertThat(count(context, "account_jwt_signer_secret_observations")).isEqualTo(1L);
    assertThat(signerRow(context, "account_jwt_signer_desired_states")).isEqualTo(retainedState);
    // The same exact receipt can still be recorded after the rolled-back attempt.
    inTransaction(
        context,
        () ->
            desired.recordGenerationResult(
                BINDING,
                initialTrust,
                request.operationId(),
                observed.generationRequestDigest(),
                SECRET_UID,
                "12",
                "13",
                key.json()));
    assertThat(count(context, "account_jwt_signer_generation_results")).isEqualTo(1L);
  }

  private static String signerRow(TestContext context, String table) {
    return java.util.Objects.requireNonNull(
        java.util.Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne("SELECT to_jsonb(retained)::text FROM " + table + " AS retained"))
            .get(0, String.class));
  }

  private static String signerSqlState(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof java.sql.SQLException sqlFailure) {
        return sqlFailure.getSQLState();
      }
    }
    return null;
  }

  private static int insertSignerRow(
      TestContext context, String table, String row, String patchSql, Object... patchValues) {
    Object[] bindings = new Object[patchValues.length + 1];
    bindings[0] = row;
    System.arraycopy(patchValues, 0, bindings, 1, patchValues.length);
    return context
        .dsl()
        .execute(
            "INSERT INTO "
                + table
                + " SELECT * FROM jsonb_populate_record(NULL::"
                + table
                + ", ?::jsonb || "
                + patchSql
                + ")",
            bindings);
  }

  private TestContext newTestContext(String targetVersion) {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
    schemas.add(schema);
    var dataSource = POSTGRES.dataSource(schema);
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (targetVersion != null) {
      configuration.target(targetVersion);
    }
    configuration.load().migrate();
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new TestContext(schema, dataSource, transaction, dsl);
  }

  private void migrateLatest(TestContext context) {
    Flyway.configure()
        .dataSource(context.dataSource())
        .schemas(context.schema())
        .defaultSchema(context.schema())
        .placeholders(Map.of("serviceSchema", context.schema()))
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  private static <T> T inTransaction(TestContext context, Supplier<T> operation) {
    return context.transaction().execute(status -> operation.get());
  }

  private static long count(TestContext context, String table) {
    return java.util.Objects.requireNonNull(
        context.dsl().resultQuery("SELECT count(*) FROM " + table).fetchOne(0, Long.class),
        "Count query returned no row.");
  }

  private static TrustFence trust() {
    return new TrustFence(CLUSTER_UID, NAMESPACE_UID, TRUST_DIGEST, TRUST_REVISION);
  }

  private static EnrollmentIdentity enrollmentIdentity() {
    return new EnrollmentIdentity(
        CLUSTER_UID,
        NAMESPACE_UID,
        TRUST_DIGEST,
        TRUST_REVISION,
        API_DIGEST,
        API_REVISION,
        CONFIG_MAP_UID,
        "12",
        INITIAL_CONFIG_MAP_SNAPSHOT_DIGEST);
  }

  private static PublicJwk publicJwk(String kid) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    RSAPublicKey key = (RSAPublicKey) generator.generateKeyPair().getPublic();
    String modulus = encodeUnsigned(key.getModulus());
    String exponent = encodeUnsigned(key.getPublicExponent());
    Map<String, Object> jwk = new LinkedHashMap<>();
    jwk.put("alg", "RS256");
    jwk.put("e", exponent);
    jwk.put("key_ops", List.of("verify"));
    jwk.put("kid", kid);
    jwk.put("kty", "RSA");
    jwk.put("n", modulus);
    jwk.put("use", "sig");
    String json = canonicalJson(jwk);
    String fingerprint =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        Rfc8785CanonicalJson.canonicalizeUtf8(
                            JSON.writeValueAsString(
                                Map.of("e", exponent, "kty", "RSA", "n", modulus)))));
    return new PublicJwk(json, fingerprint);
  }

  private static String canonicalJson(Map<String, Object> value) throws Exception {
    return new String(
        Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)),
        StandardCharsets.UTF_8);
  }

  private static String encodeUnsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    int offset = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
    return java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(java.util.Arrays.copyOfRange(bytes, offset, bytes.length));
  }

  private record PublicJwk(String json, String fingerprint) {}

  private record TestContext(
      String schema,
      javax.sql.DataSource dataSource,
      TransactionTemplate transaction,
      DSLContext dsl) {}
}
