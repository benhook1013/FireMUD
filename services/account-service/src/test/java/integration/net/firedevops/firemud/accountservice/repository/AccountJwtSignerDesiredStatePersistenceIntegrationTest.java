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
import io.grpc.ServerCallHandler;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PromotionPreparation;
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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

class AccountJwtSignerDesiredStatePersistenceIntegrationTest {
  private static final String SCHEMA_PREFIX = "jwt_signer_generation_proof";
  private static final String EXTERNAL_POSTGRES_URL_ENV =
      "FIREMUD_ACCOUNT_SIGNER_TEST_POSTGRES_URL";
  private static final Binding PROD_BINDING =
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
  private static final String MATERIALIZER_URI =
      "spiffe://firemud/ns/firemud-prod/sa/jwt-signer-materializer";
  private static final byte[] PEER_SPKI =
      "authenticated materializer integration key".getBytes(StandardCharsets.US_ASCII);

  private static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");
  private static String testJdbcUrl;
  private static String testJdbcUsername;
  private static String testJdbcPassword;
  private static boolean startedOwnedContainer;

  @Test
  void v49RejectsPartialEnrollmentPinsDigestsAndResourceUid() throws Exception {
    TestContext context = newTestContext();

    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "INSERT INTO account_jwt_signer_desired_states "
                            + "(environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                            + "enrollment_cluster_incarnation_uid) "
                            + "VALUES ('partial-pin', 'prod-cluster-1', 'firemud-prod', "
                            + "'INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK', ?)",
                        UUID.fromString(CLUSTER_UID)))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "INSERT INTO account_jwt_signer_desired_states "
                            + "(environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                            + "enrollment_cluster_incarnation_uid, enrollment_namespace_uid, "
                            + "enrollment_materializer_binding_digest, "
                            + "enrollment_materializer_config_revision, "
                            + "enrollment_api_config_revision, enrollment_public_config_map_uid, "
                            + "enrollment_public_config_map_resource_version, "
                            + "enrollment_public_config_map_snapshot_digest) "
                            + "VALUES ('partial-digest', 'prod-cluster-1', 'firemud-prod', "
                            + "'INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK', ?, ?, ?, ?, ?, ?, ?, ?)",
                        UUID.fromString(CLUSTER_UID),
                        UUID.fromString(NAMESPACE_UID),
                        TRUST_DIGEST,
                        TRUST_REVISION,
                        "api-revision-1",
                        UUID.fromString("44444444-4444-4444-8444-444444444444"),
                        "12",
                        "c".repeat(64)))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "INSERT INTO account_jwt_signer_desired_states "
                            + "(environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                            + "enrollment_cluster_incarnation_uid, enrollment_namespace_uid, "
                            + "enrollment_materializer_binding_digest, "
                            + "enrollment_materializer_config_revision, enrollment_api_binding_digest, "
                            + "enrollment_api_config_revision, enrollment_public_config_map_resource_version, "
                            + "enrollment_public_config_map_snapshot_digest) "
                            + "VALUES ('partial-resource-uid', 'prod-cluster-1', 'firemud-prod', "
                            + "'INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK', ?, ?, ?, ?, ?, ?, ?, ?)",
                        UUID.fromString(CLUSTER_UID),
                        UUID.fromString(NAMESPACE_UID),
                        TRUST_DIGEST,
                        TRUST_REVISION,
                        "d".repeat(64),
                        "api-revision-1",
                        "12",
                        "c".repeat(64)))
        .isInstanceOf(DataAccessException.class);

    AccountJwtSignerDesiredStateRepository repository = repository(context);
    EnrollmentIdentity enrollment = enrollmentIdentity();
    inTransaction(context, () -> repository.initialize(PROD_BINDING, enrollment));
    GenerationRequest current =
        inTransaction(
            context, () -> repository.ensureCurrentGenerationRequest(PROD_BINDING, trust()));

    assertRejectedPartialPromotionInsert(context, current, "materializer_trust_binding_digest");
    assertRejectedPartialPromotionInsert(context, current, "api_config_revision");
    assertRejectedPartialPromotionInsert(context, current, "expected_public_config_map_uid");
  }

  @Test
  void directPromotionCommitWithoutOwnerCreatedReadinessProofIsRejected() throws Exception {
    TestContext context = newTestContext();
    AccountJwtSignerDesiredStateRepository repository = repository(context);

    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        repository.commitPreparedPromotion(
                            PROD_BINDING, trust(), UUID.randomUUID(), null)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("readiness proof");
    assertThat(count(context, "account_jwt_signer_desired_states")).isZero();
  }

  @BeforeAll
  static void configureDatabase() {
    String externalUrl = System.getenv(EXTERNAL_POSTGRES_URL_ENV);
    if (externalUrl != null) {
      testJdbcUrl = validateExternalLoopbackPostgresUrl(externalUrl);
      testJdbcUsername = "postgres";
      testJdbcPassword = "";
      return;
    }

    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "PostgreSQL integration proof requires the explicit loopback tunnel or an available Docker daemon");
    postgres.start();
    startedOwnedContainer = true;
    testJdbcUrl = postgres.getJdbcUrl();
    testJdbcUsername = postgres.getUsername();
    testJdbcPassword = postgres.getPassword();
  }

  @AfterAll
  static void stopOwnedContainer() {
    if (startedOwnedContainer) {
      postgres.stop();
    }
  }

  private static String validateExternalLoopbackPostgresUrl(String jdbcUrl) {
    final URI uri;
    try {
      if (!jdbcUrl.startsWith("jdbc:")) {
        throw new IllegalArgumentException("not a JDBC URL");
      }
      uri = URI.create(jdbcUrl.substring("jdbc:".length()));
    } catch (RuntimeException ex) {
      throw new IllegalStateException(
          EXTERNAL_POSTGRES_URL_ENV + " must be a loopback PostgreSQL JDBC URL", ex);
    }
    if (!"postgresql".equals(uri.getScheme())
        || !"127.0.0.1".equals(uri.getHost())
        || uri.getPort() < 1
        || uri.getPort() > 65535
        || !"/postgres".equals(uri.getPath())
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw new IllegalStateException(
          EXTERNAL_POSTGRES_URL_ENV + " must target jdbc:postgresql://127.0.0.1:<port>/postgres");
    }
    return jdbcUrl;
  }

  @Test
  void explicitInitializationAndGenerationReceiptPersistOnlyNonSecretOwnerEvidence()
      throws Exception {
    TestContext context = newTestContext();
    AccountJwtSignerDesiredStateRepository repository = repository(context);
    String resourceVersionAtMaximumLength = "r".repeat(256);
    assertThat(count(context, "account_jwt_signer_desired_states")).isZero();

    var state =
        inTransaction(context, () -> repository.initialize(PROD_BINDING, enrollmentIdentity()));
    assertThat(state.recordVersion()).isEqualTo(1);
    assertThat(state.durableActive()).isEmpty();
    assertThat(state.publishedActive()).isEmpty();
    assertThat(state.generationOperationId()).isEmpty();
    assertThat(state.preparedOperationId()).isEmpty();
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () -> repository.read(binding("stage", "prod-cluster-1", "firemud-prod"))))
        .isInstanceOf(AccountJwtSignerDesiredStateRepository.MissingDesiredStateException.class);
    assertThat(count(context, "account_jwt_signer_desired_states")).isEqualTo(1L);

    GenerationRequest operation =
        inTransaction(
            context, () -> repository.ensureCurrentGenerationRequest(PROD_BINDING, trust()));
    assertThat(operation.targetGeneration()).isEqualTo("1");
    assertThat(operation.targetKid()).startsWith("jwt-1-");
    assertThat(operation.phase())
        .isEqualTo(AccountJwtSignerDesiredStateRepository.GenerationPhase.OBSERVE_PRIVATE_SECRET);
    assertThat(operation.publicKeyFingerprint()).isEmpty();
    assertThat(operation.publicJwkJson()).isEmpty();

    GenerationRequest observation =
        inTransaction(
            context,
            () ->
                repository.recordSecretObservation(
                    PROD_BINDING,
                    trust(),
                    operation.operationId(),
                    operation.operationDigest(),
                    SECRET_UID,
                    resourceVersionAtMaximumLength));
    GenerationRequest observationRetry =
        inTransaction(
            context,
            () ->
                repository.recordSecretObservation(
                    PROD_BINDING,
                    trust(),
                    operation.operationId(),
                    operation.operationDigest(),
                    SECRET_UID,
                    resourceVersionAtMaximumLength));
    assertThat(observationRetry).isEqualTo(observation);
    assertThat(observation.phase())
        .isEqualTo(AccountJwtSignerDesiredStateRepository.GenerationPhase.GENERATE_PENDING);
    assertThat(observation.generationRequestDigest()).hasSize(64);
    assertThat(observation.expectedSecretResourceVersion())
        .isEqualTo(resourceVersionAtMaximumLength);
    assertThat(observation.secretUid()).isEqualTo(SECRET_UID);

    PublicJwk jwk = publicJwk(operation.targetKid());
    GenerationResult result =
        inTransaction(
            context,
            () ->
                repository.recordGenerationResult(
                    PROD_BINDING,
                    trust(),
                    operation.operationId(),
                    observation.generationRequestDigest(),
                    SECRET_UID,
                    resourceVersionAtMaximumLength,
                    "13",
                    jwk.json()));
    GenerationResult exactRetry =
        inTransaction(
            context,
            () ->
                repository.recordGenerationResult(
                    PROD_BINDING,
                    trust(),
                    operation.operationId(),
                    observation.generationRequestDigest(),
                    SECRET_UID,
                    resourceVersionAtMaximumLength,
                    "13",
                    jwk.json()));
    assertThat(exactRetry).isEqualTo(result);
    assertThat(result.publicKeyFingerprint()).isEqualTo(jwk.fingerprint());
    assertThat(result.expectedPriorResourceVersion()).isEqualTo(resourceVersionAtMaximumLength);
    assertThat(result.observedResourceVersion()).isEqualTo("13");
    assertThat(result.publicJwkJson()).doesNotContain("\"d\"");

    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        repository.prepareCurrentGeneration(
                            PROD_BINDING, trust(), callerSuppliedPreparation(result), null)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Account-verified readiness proof is required");
    assertThat(count(context, "account_jwt_signer_generation_operations")).isEqualTo(1L);
    assertThat(count(context, "account_jwt_signer_secret_observations")).isEqualTo(1L);
    assertThat(count(context, "account_jwt_signer_generation_results")).isEqualTo(1L);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT record_version, generation_operation_id, prepared_operation_id, "
                        + "durable_active_generation, published_active_generation "
                        + "FROM account_jwt_signer_desired_states WHERE environment_id = 'prod'")
                .fetchOne())
        .satisfies(
            row -> {
              assertThat(row.get("record_version", Long.class)).isEqualTo(2L);
              assertThat(row.get("generation_operation_id", UUID.class))
                  .isEqualTo(operation.operationId());
              assertThat(row.get("prepared_operation_id", UUID.class)).isNull();
              assertThat(row.get("durable_active_generation", Long.class)).isNull();
              assertThat(row.get("published_active_generation", Long.class)).isNull();
            });
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT custody_mode, expected_cluster_incarnation_uid, expected_namespace_uid, "
                        + "trust_binding_digest, trust_config_revision, private_secret_name, "
                        + "secret_uid, observed_resource_version, generation_request_digest "
                        + "FROM account_jwt_signer_secret_observations")
                .fetchOne())
        .satisfies(
            row -> {
              assertThat(row.get("custody_mode", String.class))
                  .isEqualTo("INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK");
              assertThat(row.get("expected_cluster_incarnation_uid", UUID.class))
                  .isEqualTo(UUID.fromString(CLUSTER_UID));
              assertThat(row.get("expected_namespace_uid", UUID.class))
                  .isEqualTo(UUID.fromString(NAMESPACE_UID));
              assertThat(row.get("trust_binding_digest", String.class)).isEqualTo(TRUST_DIGEST);
              assertThat(row.get("trust_config_revision", String.class)).isEqualTo(TRUST_REVISION);
              assertThat(row.get("private_secret_name", String.class))
                  .isEqualTo("jwt-signing-keys");
              assertThat(row.get("secret_uid", UUID.class)).isEqualTo(UUID.fromString(SECRET_UID));
              assertThat(row.get("observed_resource_version", String.class))
                  .isEqualTo(resourceVersionAtMaximumLength);
              assertThat(row.get("generation_request_digest", String.class))
                  .isEqualTo(observation.generationRequestDigest());
            });
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT expected_prior_resource_version, observed_resource_version, "
                        + "target_generation, target_kid, target_algorithm, public_key_fingerprint, "
                        + "public_jwk_json, receipt_digest FROM account_jwt_signer_generation_results")
                .fetchOne())
        .satisfies(
            row -> {
              assertThat(row.get("expected_prior_resource_version", String.class))
                  .isEqualTo(resourceVersionAtMaximumLength);
              assertThat(row.get("observed_resource_version", String.class)).isEqualTo("13");
              assertThat(row.get("target_generation", Long.class)).isEqualTo(1L);
              assertThat(row.get("target_kid", String.class)).isEqualTo(operation.targetKid());
              assertThat(row.get("target_algorithm", String.class)).isEqualTo("RS256");
              assertThat(row.get("public_key_fingerprint", String.class))
                  .isEqualTo(jwk.fingerprint());
              assertThat(row.get("public_jwk_json", String.class)).isEqualTo(jwk.json());
              assertThat(row.get("receipt_digest", String.class)).isEqualTo(result.receiptDigest());
            });

    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "INSERT INTO account_jwt_signer_secret_observations "
                            + "SELECT operation_id, environment_id, cluster_id, "
                            + "kubernetes_namespace, custody_mode, operation_digest_version, "
                            + "operation_digest, expected_record_version, "
                            + "expected_cluster_incarnation_uid, expected_namespace_uid, "
                            + "trust_binding_digest, trust_config_revision, private_secret_name, "
                            + "secret_uid, {0}, observation_digest_version, observation_digest, "
                            + "generation_request_digest FROM account_jwt_signer_secret_observations",
                        "r".repeat(257)))
        .isInstanceOf(DataAccessException.class);
    for (String invalidResourceVersion : List.of("r\n", "r\u0001")) {
      assertThatThrownBy(
              () ->
                  context
                      .dsl()
                      .execute(
                          "INSERT INTO account_jwt_signer_secret_observations "
                              + "SELECT operation_id, environment_id, cluster_id, "
                              + "kubernetes_namespace, custody_mode, operation_digest_version, "
                              + "operation_digest, expected_record_version, "
                              + "expected_cluster_incarnation_uid, expected_namespace_uid, "
                              + "trust_binding_digest, trust_config_revision, private_secret_name, "
                              + "secret_uid, {0}, observation_digest_version, observation_digest, "
                              + "generation_request_digest FROM account_jwt_signer_secret_observations",
                          invalidResourceVersion))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("account_jwt_signer_observation_resource_version_check");
    }

    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        repository.recordSecretObservation(
                            PROD_BINDING,
                            trust(),
                            operation.operationId(),
                            operation.operationDigest(),
                            SECRET_UID,
                            "14")))
        .isInstanceOf(AccountJwtSignerDesiredStateRepository.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        repository.recordGenerationResult(
                            PROD_BINDING,
                            trust(),
                            operation.operationId(),
                            observation.generationRequestDigest(),
                            SECRET_UID,
                            resourceVersionAtMaximumLength,
                            "14",
                            jwk.json())))
        .isInstanceOf(AccountJwtSignerDesiredStateRepository.IdempotencyConflictException.class);

    assertThatThrownBy(
            () ->
                insertResult(
                    context, result, result.desiredStateVersion() + 1, result.receiptDigest()))
        .isInstanceOf(DataAccessException.class);
    assertThat(count(context, "account_jwt_signer_generation_results")).isEqualTo(1L);

    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_jwt_signer_generation_operations SET target_kid = 'changed' "
                            + "WHERE operation_id = ?",
                        operation.operationId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "DELETE FROM account_jwt_signer_generation_operations WHERE operation_id = ?",
                        operation.operationId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_jwt_signer_secret_observations SET trust_config_revision = 'changed' "
                            + "WHERE operation_id = ?",
                        operation.operationId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "DELETE FROM account_jwt_signer_secret_observations WHERE operation_id = ?",
                        operation.operationId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_jwt_signer_generation_results SET public_key_fingerprint = ? "
                            + "WHERE operation_id = ?",
                        "0".repeat(64),
                        operation.operationId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "DELETE FROM account_jwt_signer_generation_results WHERE operation_id = ?",
                        operation.operationId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> context.dsl().execute("TRUNCATE account_jwt_signer_generation_operations"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> context.dsl().execute("TRUNCATE account_jwt_signer_secret_observations"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> context.dsl().execute("TRUNCATE account_jwt_signer_generation_results"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "UPDATE account_jwt_signer_desired_states SET prepared_operation_id = ? "
                            + "WHERE environment_id = 'prod'",
                        UUID.randomUUID()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> context.dsl().execute("TRUNCATE account_jwt_signer_desired_states"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void resultTriggerHoldsDesiredStateLockUntilAccountTransactionCommits() throws Exception {
    TestContext context = newTestContext();
    AccountJwtSignerDesiredStateRepository repository = repository(context);
    inTransaction(context, () -> repository.initialize(PROD_BINDING, enrollmentIdentity()));
    GenerationRequest operation =
        inTransaction(
            context, () -> repository.ensureCurrentGenerationRequest(PROD_BINDING, trust()));
    GenerationRequest observation =
        inTransaction(
            context,
            () ->
                repository.recordSecretObservation(
                    PROD_BINDING,
                    trust(),
                    operation.operationId(),
                    operation.operationDigest(),
                    SECRET_UID,
                    "12"));
    PublicJwk jwk = publicJwk(operation.targetKid());
    CountDownLatch resultInserted = new CountDownLatch(1);
    CountDownLatch allowCommit = new CountDownLatch(1);
    CountDownLatch competingSelectStarted = new CountDownLatch(1);
    CountDownLatch competingSelectCompleted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> resultTransaction =
          executor.submit(
              () ->
                  context
                      .transaction()
                      .execute(
                          status -> {
                            repository.recordGenerationResult(
                                PROD_BINDING,
                                trust(),
                                operation.operationId(),
                                observation.generationRequestDigest(),
                                SECRET_UID,
                                "12",
                                "13",
                                jwk.json());
                            resultInserted.countDown();
                            await(
                                allowCommit, "JWT generation receipt transaction was not released");
                            return null;
                          }));
      assertThat(resultInserted.await(10, TimeUnit.SECONDS)).isTrue();
      Future<?> competingTransaction =
          executor.submit(
              () ->
                  context
                      .transaction()
                      .execute(
                          status -> {
                            competingSelectStarted.countDown();
                            context
                                .dsl()
                                .fetchOne(
                                    "SELECT record_version FROM account_jwt_signer_desired_states "
                                        + "WHERE environment_id = 'prod' FOR UPDATE");
                            competingSelectCompleted.countDown();
                            return null;
                          }));
      assertThat(competingSelectStarted.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(competingSelectCompleted.await(250, TimeUnit.MILLISECONDS)).isFalse();
      allowCommit.countDown();
      resultTransaction.get(10, TimeUnit.SECONDS);
      competingTransaction.get(10, TimeUnit.SECONDS);
      assertThat(competingSelectCompleted.getCount()).isZero();
    } finally {
      allowCommit.countDown();
      executor.shutdownNow();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void changedProtectedTrustBeforeCommitRollsBackGenerationReceipt() throws Exception {
    TestContext context = newTestContext();
    AccountJwtSignerDesiredStateRepository repository = repository(context);
    inTransaction(context, () -> repository.initialize(PROD_BINDING, enrollmentIdentity()));
    String peerPin = sha256(PEER_SPKI);
    AccountJwtSignerMaterializerTrustBinding.Binding initial =
        materializerTrust(peerPin, TRUST_REVISION);
    TrustFence initialTrust =
        new TrustFence(
            initial.expectedClusterIncarnationUid(),
            initial.expectedNamespaceUid(),
            initial.bindingDigest(),
            initial.configRevision());
    GenerationRequest operation =
        inTransaction(
            context, () -> repository.ensureCurrentGenerationRequest(PROD_BINDING, initialTrust));
    GenerationRequest observation =
        inTransaction(
            context,
            () ->
                repository.recordSecretObservation(
                    PROD_BINDING,
                    initialTrust,
                    operation.operationId(),
                    operation.operationDigest(),
                    SECRET_UID,
                    "12"));

    AccountJwtSignerMaterializerTrustBinding provider =
        mock(AccountJwtSignerMaterializerTrustBinding.class);
    AccountJwtSignerMaterializerTrustBinding.Binding changed =
        materializerTrust(
            sha256("replacement peer key".getBytes(StandardCharsets.US_ASCII)), "revision-2");
    when(provider.current())
        .thenReturn(Optional.of(initial), Optional.of(initial), Optional.of(changed));
    AccountJwtSignerMaterializationService service =
        new AccountJwtSignerMaterializationService(
            repository,
            new AccountJwtReadinessProbeRepository(
                context.dsl(),
                repository,
                new AccountJwtJwksPublicationRepository(context.dsl(), repository)),
            provider,
            context.transactionManager());
    AccountJwtSignerMaterializerTlsInterceptor interceptor =
        new AccountJwtSignerMaterializerTlsInterceptor(provider);
    PublicJwk jwk = publicJwk(operation.targetKid());
    RecordGenerationResultRequest request =
        RecordGenerationResultRequest.newBuilder()
            .setSchemaVersion(1)
            .setOperationId(operation.operationId().toString())
            .setGenerationRequestDigest(observation.generationRequestDigest())
            .setSecretUid(SECRET_UID)
            .setExpectedPriorResourceVersion("12")
            .setObservedResourceVersion("13")
            .setPublicJwkJson(jwk.json())
            .build();
    RecordingObserver<RecordGenerationResultResponse> response = new RecordingObserver<>();

    invokeWithAuthenticatedPeer(interceptor, service, request, response, PEER_SPKI);

    assertThat(response.error.type()).isEqualTo(StatusRuntimeException.class.getName());
    assertThat(response.error.code()).isEqualTo(io.grpc.Status.Code.PERMISSION_DENIED);
    assertThat(response.error.message()).contains("PERMISSION_DENIED");
    assertThat(response.value).isNull();
    assertThat(response.completed).isFalse();
    verify(provider, times(3)).current();
    assertThat(count(context, "account_jwt_signer_generation_results")).isZero();
    assertThat(count(context, "account_jwt_signer_secret_observations")).isEqualTo(1L);
    assertThat(
            context
                .dsl()
                .resultQuery(
                    "SELECT record_version, generation_operation_id, prepared_operation_id "
                        + "FROM account_jwt_signer_desired_states WHERE environment_id = 'prod'")
                .fetchOne())
        .satisfies(
            row -> {
              assertThat(row.get("record_version", Long.class)).isEqualTo(2L);
              assertThat(row.get("generation_operation_id", UUID.class))
                  .isEqualTo(operation.operationId());
              assertThat(row.get("prepared_operation_id", UUID.class)).isNull();
            });
  }

  private static AccountJwtSignerDesiredStateRepository repository(TestContext context) {
    return new AccountJwtSignerDesiredStateRepository(context.dsl());
  }

  private static long count(TestContext context, String tableName) {
    return java.util.Objects.requireNonNull(
        context.dsl().resultQuery("SELECT COUNT(*) FROM " + tableName).fetchOne(0, Long.class),
        "COUNT query must return a scalar row");
  }

  private static void assertRejectedPartialPromotionInsert(
      TestContext context, GenerationRequest generation, String omittedLifecycleField) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("operation_id", UUID.randomUUID());
    values.put("environment_id", PROD_BINDING.environmentId());
    values.put("cluster_id", PROD_BINDING.clusterId());
    values.put("kubernetes_namespace", PROD_BINDING.namespace());
    values.put("custody_mode", PROD_BINDING.mode().value());
    values.put("request_digest_version", 1);
    values.put("request_digest", "a".repeat(64));
    values.put("expected_record_version", generation.desiredStateVersion());
    values.put("target_generation", Long.parseLong(generation.targetGeneration()));
    values.put("target_kid", generation.targetKid());
    values.put("target_algorithm", "RS256");
    values.put("target_public_key_fingerprint", "b".repeat(64));
    values.put("expected_private_secret_resource_version", "12");
    values.put("expected_public_jwks_resource_version", "13");
    values.put("operation_action", "PROMOTE_PENDING");
    values.put(
        "allowed_private_slots_canonical_bytes",
        "[\"current\",\"pending\",\"previous\"]".getBytes(StandardCharsets.US_ASCII));
    values.put("status", "PREPARED");
    values.put("generation_operation_id", generation.operationId());
    values.put("expected_cluster_incarnation_uid", UUID.fromString(CLUSTER_UID));
    values.put("expected_namespace_uid", UUID.fromString(NAMESPACE_UID));
    values.put("materializer_trust_binding_digest", TRUST_DIGEST);
    values.put("materializer_trust_config_revision", TRUST_REVISION);
    values.put("api_binding_digest", "d".repeat(64));
    values.put("api_config_revision", "api-revision-1");
    values.put("expected_private_secret_uid", UUID.fromString(SECRET_UID));
    values.put(
        "expected_public_config_map_uid", UUID.fromString("44444444-4444-4444-8444-444444444444"));
    values.put("prepublication_intent_digest", "a".repeat(64));
    values.put("prepublication_receipt_digest", "b".repeat(64));
    values.put("mounted_observation_digest", "c".repeat(64));
    values.put("readiness_plan_digest", "d".repeat(64));
    values.put("readiness_evidence_digest", "e".repeat(64));
    values.put("expected_public_jwks_json", "{\"keys\":[]}");
    values.put("expected_active_generation_marker_json", "{\"phase\":\"ACTIVE\"}");
    values.put(omittedLifecycleField, null);

    String columns = String.join(", ", values.keySet());
    String placeholders = String.join(", ", java.util.Collections.nCopies(values.size(), "?"));
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute(
                        "INSERT INTO account_jwt_signer_promotion_operations ("
                            + columns
                            + ") VALUES ("
                            + placeholders
                            + ")",
                        values.values().toArray()))
        .isInstanceOf(DataAccessException.class);
  }

  private static GenerationResult insertResult(
      TestContext context,
      GenerationResult result,
      long desiredStateVersion,
      String receiptDigest) {
    context
        .dsl()
        .execute(
            "INSERT INTO account_jwt_signer_generation_results "
                + "(operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                + "operation_digest, generation_request_digest, desired_state_version, "
                + "expected_cluster_incarnation_uid, expected_namespace_uid, trust_binding_digest, "
                + "trust_config_revision, private_secret_name, secret_uid, "
                + "expected_prior_resource_version, observed_resource_version, target_generation, "
                + "target_kid, target_algorithm, public_key_fingerprint, public_jwk_json, receipt_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'jwt-signing-keys', ?, ?, ?, ?, ?, "
                + "'RS256', ?, ?, ?)",
            result.operationId(),
            result.binding().environmentId(),
            result.binding().clusterId(),
            result.binding().namespace(),
            result.binding().mode().value(),
            result.operationDigest(),
            result.generationRequestDigest(),
            desiredStateVersion,
            UUID.fromString(result.trustFence().expectedClusterIncarnationUid()),
            UUID.fromString(result.trustFence().expectedNamespaceUid()),
            result.trustFence().bindingDigest(),
            result.trustFence().configRevision(),
            UUID.fromString(result.secretUid()),
            result.expectedPriorResourceVersion(),
            result.observedResourceVersion(),
            Long.parseLong(result.targetGeneration()),
            result.targetKid(),
            result.publicKeyFingerprint(),
            result.publicJwkJson(),
            receiptDigest);
    return result;
  }

  private static AccountJwtSignerDesiredStateRepository.TrustFence trust() {
    return new TrustFence(CLUSTER_UID, NAMESPACE_UID, TRUST_DIGEST, TRUST_REVISION);
  }

  private static EnrollmentIdentity enrollmentIdentity() {
    return new EnrollmentIdentity(
        CLUSTER_UID,
        NAMESPACE_UID,
        TRUST_DIGEST,
        TRUST_REVISION,
        "d".repeat(64),
        "api-revision-1",
        "44444444-4444-4444-8444-444444444444",
        "12",
        "e".repeat(64));
  }

  /** A bounded caller DTO paired with no owner proof; it is deliberately non-authorizing. */
  private static PromotionPreparation callerSuppliedPreparation(GenerationResult result) {
    EnrollmentIdentity enrollment = enrollmentIdentity();
    return new PromotionPreparation(
        result.operationId(),
        result,
        enrollment,
        enrollment.apiBindingDigest(),
        enrollment.apiConfigRevision(),
        enrollment.publicConfigMapUid(),
        "14",
        "a".repeat(64),
        "b".repeat(64),
        "c".repeat(64),
        "1".repeat(64),
        "2".repeat(64),
        "{\"keys\":[" + result.publicJwkJson() + "]}");
  }

  private static AccountJwtSignerMaterializerTrustBinding.Binding materializerTrust(
      String pin, String revision) {
    List<String> pins = List.of(pin);
    String digest =
        AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
            revision,
            PROD_BINDING.environmentId(),
            PROD_BINDING.clusterId(),
            PROD_BINDING.namespace(),
            CLUSTER_UID,
            NAMESPACE_UID,
            MATERIALIZER_URI,
            pins);
    return new AccountJwtSignerMaterializerTrustBinding.Binding(
        PROD_BINDING.environmentId(),
        PROD_BINDING.clusterId(),
        PROD_BINDING.namespace(),
        CLUSTER_UID,
        NAMESPACE_UID,
        MATERIALIZER_URI,
        pins,
        revision,
        digest);
  }

  private static Binding binding(String environment, String cluster, String namespace) {
    return new Binding(
        environment, cluster, namespace, CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
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
    String json =
        new String(
            Rfc8785CanonicalJson.canonicalizeUtf8(
                JsonMapper.builder().build().writeValueAsString(jwk)),
            StandardCharsets.UTF_8);
    String fingerprint =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        Rfc8785CanonicalJson.canonicalizeUtf8(
                            JsonMapper.builder()
                                .build()
                                .writeValueAsString(
                                    Map.of("e", exponent, "kty", "RSA", "n", modulus)))));
    return new PublicJwk(json, fingerprint);
  }

  private static String encodeUnsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    int offset = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
    return java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(java.util.Arrays.copyOfRange(bytes, offset, bytes.length));
  }

  private static void invokeWithAuthenticatedPeer(
      AccountJwtSignerMaterializerTlsInterceptor interceptor,
      AccountJwtSignerMaterializationService service,
      RecordGenerationResultRequest request,
      RecordingObserver<RecordGenerationResultResponse> response,
      byte[] peerSpki)
      throws Exception {
    @SuppressWarnings("unchecked")
    ServerCall<String, String> call = mock(ServerCall.class);
    SSLSession sslSession = mock(SSLSession.class);
    java.security.cert.X509Certificate certificate = mock(java.security.cert.X509Certificate.class);
    PublicKey publicKey = mock(PublicKey.class);
    when(publicKey.getEncoded()).thenReturn(peerSpki);
    when(certificate.getPublicKey()).thenReturn(publicKey);
    when(certificate.getSubjectAlternativeNames())
        .thenReturn(List.of(List.of(6, MATERIALIZER_URI)));
    when(sslSession.getPeerCertificates())
        .thenReturn(new java.security.cert.Certificate[] {certificate});
    when(call.getAttributes())
        .thenReturn(
            Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build());
    ServerCallHandler<String, String> next =
        (interceptedCall, headers) -> {
          service.recordGenerationResult(request, response);
          return new ServerCall.Listener<>() {};
        };
    interceptor.interceptCall(call, new Metadata(), next);
  }

  private TestContext newTestContext() {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = testJdbcUrl.contains("?") ? "&" : "?";
    dataSource.setUrl(testJdbcUrl + separator + "currentSchema=" + schema);
    dataSource.setUsername(testJdbcUsername);
    dataSource.setPassword(testJdbcPassword);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    return new TestContext(new TransactionTemplate(transactionManager), transactionManager, dsl);
  }

  private static <T> T inTransaction(
      TestContext context, java.util.function.Supplier<T> operation) {
    return context.transaction().execute(status -> operation.get());
  }

  private static PublicJwk publicJwkFor(GenerationRequest request) throws Exception {
    return publicJwk(request.targetKid());
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static void await(CountDownLatch latch, String message) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException(message);
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(message, ex);
    }
  }

  private static final class RecordingObserver<T> implements StreamObserver<T> {
    private T value;
    private ErrorDiagnostic error;
    private boolean completed;

    @Override
    public void onNext(T value) {
      this.value = value;
    }

    @Override
    public void onError(Throwable error) {
      this.error =
          new ErrorDiagnostic(
              error.getClass().getName(),
              error instanceof StatusRuntimeException statusError
                  ? statusError.getStatus().getCode()
                  : null,
              error.getMessage());
    }

    @Override
    public void onCompleted() {
      completed = true;
    }

    private record ErrorDiagnostic(String type, io.grpc.Status.Code code, String message) {}
  }

  private static List<Object> race(Callable<Object> left, Callable<Object> right) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<Object> leftResult = executor.submit(awaitStart(left, ready, start));
      Future<Object> rightResult = executor.submit(awaitStart(right, ready, start));
      if (!ready.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("JWT signer persistence workers did not become ready");
      }
      start.countDown();
      return List.of(leftResult.get(30, TimeUnit.SECONDS), rightResult.get(30, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  private static Callable<Object> awaitStart(
      Callable<Object> operation, CountDownLatch ready, CountDownLatch start) {
    return () -> {
      ready.countDown();
      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("JWT signer persistence race start was not released");
      }
      return operation.call();
    };
  }

  private record PublicJwk(String json, String fingerprint) {}

  private record TestContext(
      TransactionTemplate transaction,
      DataSourceTransactionManager transactionManager,
      DSLContext dsl) {}
}
