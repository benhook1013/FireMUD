package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
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

class AccountJwtJwksPublicationPersistenceIntegrationTest {
  private static final String SCHEMA_PREFIX = "jwt_jwks_publication_proof";
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();
  private static final Binding PROD_BINDING =
      new Binding(
          "prod",
          "prod-cluster-1",
          "firemud-prod",
          CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
  private static final String CLUSTER_UID = "11111111-1111-4111-8111-111111111111";
  private static final String NAMESPACE_UID = "22222222-2222-4222-8222-222222222222";
  private static final String SECRET_UID = "33333333-3333-4333-8333-333333333333";
  private static final String CONFIG_MAP_UID = "44444444-4444-4444-8444-444444444444";
  private static final String TRUST_DIGEST = "a".repeat(64);
  private static final String TRUST_REVISION = "trust-r1";
  private static final String API_DIGEST = "b".repeat(64);
  private static final String API_REVISION = "api-r1";
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
  void exactIntentReadbackAndMountedObservationPersistAndRejectEveryMutationPath()
      throws Exception {
    TestContext context = newTestContext();
    AccountJwtSignerDesiredStateRepository desiredRepository =
        new AccountJwtSignerDesiredStateRepository(context.dsl());
    AccountJwtJwksPublicationRepository publicationRepository =
        new AccountJwtJwksPublicationRepository(context.dsl(), desiredRepository);
    String expectedSnapshotDigest =
        AccountJwtJwksPublicationRepository.snapshotDigest(
            Map.of("jwks.json", "{\"keys\":[]}", "unrelated.txt", "preserve"));
    EnrollmentIdentity enrollment =
        new EnrollmentIdentity(
            CLUSTER_UID,
            NAMESPACE_UID,
            TRUST_DIGEST,
            TRUST_REVISION,
            API_DIGEST,
            API_REVISION,
            CONFIG_MAP_UID,
            "41",
            expectedSnapshotDigest);
    inTransaction(context, () -> desiredRepository.initialize(PROD_BINDING, enrollment));

    GenerationRequest operation =
        inTransaction(
            context, () -> desiredRepository.ensureCurrentGenerationRequest(PROD_BINDING, trust()));
    GenerationRequest observed =
        inTransaction(
            context,
            () ->
                desiredRepository.recordSecretObservation(
                    PROD_BINDING,
                    trust(),
                    operation.operationId(),
                    operation.operationDigest(),
                    SECRET_UID,
                    "12"));
    PublicJwk jwk = publicJwk(operation.targetKid());
    GenerationResult result =
        inTransaction(
            context,
            () ->
                desiredRepository.recordGenerationResult(
                    PROD_BINDING,
                    trust(),
                    operation.operationId(),
                    observed.generationRequestDigest(),
                    SECRET_UID,
                    "12",
                    "13",
                    jwk.json()));

    String retainedJwk = publicJwk("retained-key").jsonWithoutKeyOps();
    String desiredJwks = jwks(retainedJwk, result.publicJwkJson());
    String marker = marker(result, operation);
    String invalidBindingMarker =
        marker.replaceFirst("\"binding\":\\{[^{}]*}", "\"binding\":\"binding\"");
    GenerationRequest currentRequest =
        inTransaction(
            context, () -> desiredRepository.readCurrentGenerationRequest(PROD_BINDING, trust()));
    String invalidMarker = marker.replace("\"pending\":{", "\"pending\":{\"unexpected\":true,");
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        publicationRepository.recordPrepublicationIntent(
                            PROD_BINDING,
                            trust(),
                            result,
                            currentRequest,
                            API_DIGEST,
                            API_REVISION,
                            CONFIG_MAP_UID,
                            "41",
                            expectedSnapshotDigest,
                            desiredJwks,
                            invalidMarker)))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        publicationRepository.recordPrepublicationIntent(
                            PROD_BINDING,
                            trust(),
                            result,
                            currentRequest,
                            API_DIGEST,
                            API_REVISION,
                            CONFIG_MAP_UID,
                            "41",
                            expectedSnapshotDigest,
                            desiredJwks,
                            invalidBindingMarker)))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        publicationRepository.recordPrepublicationIntent(
                            PROD_BINDING,
                            trust(),
                            result,
                            currentRequest,
                            API_DIGEST,
                            API_REVISION,
                            CONFIG_MAP_UID,
                            "41",
                            expectedSnapshotDigest,
                            jwks(
                                mutateJwk(retainedJwk, "key_ops", List.of("sign")),
                                result.publicJwkJson()),
                            marker)))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        publicationRepository.recordPrepublicationIntent(
                            PROD_BINDING,
                            trust(),
                            result,
                            currentRequest,
                            API_DIGEST,
                            API_REVISION,
                            CONFIG_MAP_UID,
                            "41",
                            expectedSnapshotDigest,
                            jwks(
                                mutateJwk(retainedJwk, "unexpected", "field"),
                                result.publicJwkJson()),
                            marker)))
        .isInstanceOf(DataAccessException.class);
    var intent =
        inTransaction(
            context,
            () ->
                publicationRepository.recordPrepublicationIntent(
                    PROD_BINDING,
                    trust(),
                    result,
                    currentRequest,
                    API_DIGEST,
                    API_REVISION,
                    CONFIG_MAP_UID,
                    "41",
                    expectedSnapshotDigest,
                    desiredJwks,
                    marker));
    assertThat(intent.operationId()).isEqualTo(result.operationId());
    assertThat(intent.generationReceiptDigest()).isEqualTo(result.receiptDigest());
    assertThat(intent.publicKeyFingerprint()).isEqualTo(result.publicKeyFingerprint());
    assertThat(intent.jwksJson()).doesNotContain("privateKey", "\"d\"");

    var receipt =
        inTransaction(
            context,
            () ->
                publicationRepository.recordPublicationReceipt(
                    PROD_BINDING,
                    trust(),
                    result.operationId(),
                    API_DIGEST,
                    API_REVISION,
                    CONFIG_MAP_UID,
                    "42",
                    desiredJwks,
                    marker));
    assertThat(receipt.intentDigest()).isEqualTo(intent.intentDigest());
    assertThat(receipt.expectedResourceVersion()).isEqualTo("41");
    assertThat(receipt.observedResourceVersion()).isEqualTo("42");

    var mountObservation =
        inTransaction(
            context,
            () ->
                publicationRepository.recordMountedCorrespondence(
                    PROD_BINDING,
                    trust(),
                    result.operationId(),
                    sha256(marker.getBytes(StandardCharsets.UTF_8)),
                    AccountJwtJwksPublicationRepository.publicDataDigest(desiredJwks, marker),
                    result.publicKeyFingerprint()));
    assertThat(mountObservation.publicationReceiptDigest()).isEqualTo(receipt.receiptDigest());
    assertThat(mountObservation.publicKeyFingerprint()).isEqualTo(result.publicKeyFingerprint());

    assertThat(count(context, "account_jwt_jwks_prepublication_intents")).isEqualTo(1L);
    assertThat(count(context, "account_jwt_jwks_publication_receipts")).isEqualTo(1L);
    assertThat(count(context, "account_jwt_jwks_mount_observations")).isEqualTo(1L);

    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_jwt_jwks_prepublication_intents "
                                    + "SET public_data_digest = ? WHERE operation_id = ?",
                                "f".repeat(64),
                                result.operationId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "DELETE FROM account_jwt_jwks_publication_receipts "
                                    + "WHERE operation_id = ?",
                                result.operationId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () -> context.dsl().execute("TRUNCATE account_jwt_jwks_mount_observations")))
        .isInstanceOf(DataAccessException.class);

    assertThat(count(context, "account_jwt_jwks_prepublication_intents")).isEqualTo(1L);
    assertThat(count(context, "account_jwt_jwks_publication_receipts")).isEqualTo(1L);
    assertThat(count(context, "account_jwt_jwks_mount_observations")).isEqualTo(1L);
    assertThat(
            inTransaction(
                context, () -> publicationRepository.readCurrentPublication(PROD_BINDING, trust())))
        .isPresent()
        .get()
        .satisfies(
            evidence -> {
              assertThat(evidence.intent()).isEqualTo(intent);
              assertThat(evidence.receipt()).contains(receipt);
              assertThat(evidence.mountedCorrespondence()).contains(mountObservation);
            });
  }

  private TestContext newTestContext() {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
    schemas.add(schema);
    var dataSource = POSTGRES.dataSource(schema);
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
    return new TestContext(new TransactionTemplate(transactionManager), dsl);
  }

  private static <T> T inTransaction(
      TestContext context, java.util.function.Supplier<T> operation) {
    return context.transaction().execute(status -> operation.get());
  }

  private static long count(TestContext context, String table) {
    return java.util.Objects.requireNonNull(
        context.dsl().resultQuery("SELECT COUNT(*) FROM " + table).fetchOne(0, Long.class),
        "COUNT query must return a scalar row");
  }

  private static TrustFence trust() {
    return new TrustFence(CLUSTER_UID, NAMESPACE_UID, TRUST_DIGEST, TRUST_REVISION);
  }

  private static String marker(GenerationResult result, GenerationRequest request)
      throws Exception {
    Map<String, Object> marker = new LinkedHashMap<>();
    marker.put("schemaVersion", 1);
    marker.put("phase", "PREPUBLISHED");
    marker.put("operationId", result.operationId().toString());
    marker.put("operationDigest", result.operationDigest());
    marker.put("generationRequestDigest", result.generationRequestDigest());
    marker.put("generationReceiptDigest", result.receiptDigest());
    Map<String, Object> binding = new LinkedHashMap<>();
    binding.put("environmentId", result.binding().environmentId());
    binding.put("clusterId", result.binding().clusterId());
    binding.put("namespace", result.binding().namespace());
    binding.put(
        "expectedClusterIncarnationUid", result.trustFence().expectedClusterIncarnationUid());
    binding.put("expectedNamespaceUid", result.trustFence().expectedNamespaceUid());
    binding.put("trustBindingDigest", result.trustFence().bindingDigest());
    binding.put("trustConfigRevision", result.trustFence().configRevision());
    binding.put("apiBindingDigest", API_DIGEST);
    binding.put("apiConfigRevision", API_REVISION);
    marker.put("binding", binding);
    marker.put("publicConfigMap", Map.of("name", "jwt-jwks"));
    marker.put("expectedDurableActive", Map.of("present", false));
    marker.put("expectedPublishedActive", Map.of("present", false));
    marker.put(
        "pending",
        Map.of(
            "generation",
            result.targetGeneration(),
            "kid",
            result.targetKid(),
            "algorithm",
            result.targetAlgorithm(),
            "publicKeyFingerprint",
            result.publicKeyFingerprint()));
    return new String(
        Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(marker)),
        StandardCharsets.UTF_8);
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
            Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(jwk)),
            StandardCharsets.UTF_8);
    Map<String, Object> withoutKeyOps = new LinkedHashMap<>(jwk);
    withoutKeyOps.remove("key_ops");
    String jsonWithoutKeyOps =
        new String(
            Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(withoutKeyOps)),
            StandardCharsets.UTF_8);
    String fingerprint =
        sha256(
            Rfc8785CanonicalJson.canonicalizeUtf8(
                JSON.writeValueAsString(Map.of("e", exponent, "kty", "RSA", "n", modulus))));
    return new PublicJwk(json, jsonWithoutKeyOps, fingerprint);
  }

  private static String jwks(String retainedJwk, String targetJwk) {
    return "{\"keys\":[" + retainedJwk + "," + targetJwk + "]}";
  }

  private static String mutateJwk(String jwkJson, String fieldName, Object value) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> jwk = JSON.readValue(jwkJson, Map.class);
      jwk.put(fieldName, value);
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(jwk)),
          StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new IllegalStateException("Test JWK could not be updated", ex);
    }
  }

  private static String encodeUnsigned(java.math.BigInteger value) {
    byte[] bytes = value.toByteArray();
    int offset = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(java.util.Arrays.copyOfRange(bytes, offset, bytes.length));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required for the persistence fixture", ex);
    }
  }

  private record PublicJwk(String json, String jsonWithoutKeyOps, String fingerprint) {}

  private record TestContext(TransactionTemplate transaction, DSLContext dsl) {}
}
