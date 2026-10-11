package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.EnrollmentIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
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
