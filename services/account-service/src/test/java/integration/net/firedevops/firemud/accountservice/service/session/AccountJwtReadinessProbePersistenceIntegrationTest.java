package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.account.v1.ValidateReadinessProbeRequest;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeEntry;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeState;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessProbePlan;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.VerificationReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.EnrollmentIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.security.AccountJwtReadinessTlsInterceptor;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Real-PostgreSQL proof of readiness planning, exact signing, verification, and quarantine. */
class AccountJwtReadinessProbePersistenceIntegrationTest {
  private static final String SCHEMA_PREFIX = "jwt_readiness_probe_proof";
  private static final String EXTERNAL_POSTGRES_URL_ENV =
      "FIREMUD_ACCOUNT_SIGNER_TEST_POSTGRES_URL";
  private static final Binding BINDING =
      new Binding(
          "prod",
          "prod-cluster-1",
          "firemud-prod",
          CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
  private static final String CLUSTER_UID = "11111111-1111-4111-8111-111111111111";
  private static final String NAMESPACE_UID = "22222222-2222-4222-8222-222222222222";
  private static final String SECRET_UID = "33333333-3333-4333-8333-333333333333";
  private static final String CONFIG_MAP_UID = "44444444-4444-4444-8444-444444444444";
  private static final String TRUST_REVISION = "trust-r1";
  private static final String MATERIALIZER_URI =
      "spiffe://firemud/ns/firemud-prod/sa/jwt-signer-materializer";
  private static final List<String> MATERIALIZER_PINS = List.of("c".repeat(64));
  private static final String TRUST_DIGEST =
      AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
          TRUST_REVISION,
          BINDING.environmentId(),
          BINDING.clusterId(),
          BINDING.namespace(),
          CLUSTER_UID,
          NAMESPACE_UID,
          MATERIALIZER_URI,
          MATERIALIZER_PINS);
  private static final String API_DIGEST = "b".repeat(64);
  private static final String API_REVISION = "api-r1";
  private static final String INITIAL_CONFIG_MAP_RESOURCE_VERSION = "41";
  private static final Map<String, String> INITIAL_CONFIG_MAP_DATA =
      Map.of("jwks.json", "{\"keys\":[]}", "unrelated.txt", "preserve");
  private static final EnrollmentIdentity ENROLLMENT_IDENTITY =
      new EnrollmentIdentity(
          CLUSTER_UID,
          NAMESPACE_UID,
          TRUST_DIGEST,
          TRUST_REVISION,
          API_DIGEST,
          API_REVISION,
          CONFIG_MAP_UID,
          INITIAL_CONFIG_MAP_RESOURCE_VERSION,
          AccountJwtJwksPublicationRepository.snapshotDigest(INITIAL_CONFIG_MAP_DATA));
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @TempDir Path tempDirectory;

  private static String testJdbcUrl;
  private static String testJdbcUsername;
  private static String testJdbcPassword;
  private static volatile boolean startedOwnedContainer;

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
        "PostgreSQL proof requires the explicit loopback tunnel or an available Docker daemon");
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

  @Test
  void persistsFirstInstallPlanIssuesVerifiedReceiptsAndQuarantines() throws Exception {
    TestContext context = newTestContext();
    AccountJwtSignerDesiredStateRepository desired =
        new AccountJwtSignerDesiredStateRepository(context.dsl());
    AccountJwtJwksPublicationRepository publication =
        new AccountJwtJwksPublicationRepository(context.dsl(), desired);
    AccountJwtReadinessProbeRepository readiness =
        new AccountJwtReadinessProbeRepository(context.dsl(), desired, publication);
    TrustFence trust = trust();

    inTransaction(context, () -> desired.initialize(BINDING, ENROLLMENT_IDENTITY));
    GenerationRequest requested =
        inTransaction(context, () -> desired.ensureCurrentGenerationRequest(BINDING, trust));
    GenerationRequest observed =
        inTransaction(
            context,
            () ->
                desired.recordSecretObservation(
                    BINDING,
                    trust,
                    requested.operationId(),
                    requested.operationDigest(),
                    SECRET_UID,
                    "12"));

    KeyPair keyPair = generateRsa3072();
    PublicJwk targetJwk = publicJwk(requested.targetKid(), (RSAPublicKey) keyPair.getPublic());
    GenerationResult result =
        inTransaction(
            context,
            () ->
                desired.recordGenerationResult(
                    BINDING,
                    trust,
                    requested.operationId(),
                    observed.generationRequestDigest(),
                    SECRET_UID,
                    "12",
                    "13",
                    targetJwk.json()));

    KeyPair retainedPair = generateRsa3072();
    String retainedJwk =
        publicJwk("retained-key", (RSAPublicKey) retainedPair.getPublic()).jsonWithoutKeyOps();
    String desiredJwks = jwks(retainedJwk, result.publicJwkJson());
    String marker = marker(result);
    String expectedSnapshotDigest =
        AccountJwtJwksPublicationRepository.snapshotDigest(INITIAL_CONFIG_MAP_DATA);
    GenerationRequest currentRequest =
        inTransaction(context, () -> desired.readCurrentGenerationRequest(BINDING, trust));
    var intent =
        inTransaction(
            context,
            () ->
                publication.recordPrepublicationIntent(
                    BINDING,
                    trust,
                    result,
                    currentRequest,
                    API_DIGEST,
                    API_REVISION,
                    CONFIG_MAP_UID,
                    INITIAL_CONFIG_MAP_RESOURCE_VERSION,
                    expectedSnapshotDigest,
                    desiredJwks,
                    marker));
    var receipt =
        inTransaction(
            context,
            () ->
                publication.recordPublicationReceipt(
                    BINDING,
                    trust,
                    result.operationId(),
                    API_DIGEST,
                    API_REVISION,
                    CONFIG_MAP_UID,
                    "42",
                    desiredJwks,
                    marker));
    var mountObservation =
        inTransaction(
            context,
            () ->
                publication.recordMountedCorrespondence(
                    BINDING,
                    trust,
                    result.operationId(),
                    sha256(marker.getBytes(StandardCharsets.UTF_8)),
                    AccountJwtJwksPublicationRepository.publicDataDigest(desiredJwks, marker),
                    result.publicKeyFingerprint()));
    assertThat(receipt.intentDigest()).isEqualTo(intent.intentDigest());
    assertThat(mountObservation.publicationReceiptDigest()).isEqualTo(receipt.receiptDigest());

    long now =
        java.util.Objects.requireNonNull(
            context
                .dsl()
                .resultQuery("SELECT floor(extract(epoch FROM CURRENT_TIMESTAMP))::bigint")
                .fetchOne(0, Long.class),
            "Database timestamp query must return a scalar row");
    // Controlled historical-plan fixture to reach the post-cache lifecycle without waiting in
    // this persistence test. This is not evidence of a production 300-second cache convergence.
    InventorySnapshot inventorySnapshot = inventorySnapshot(Instant.ofEpochSecond(now - 301));
    ReadinessProbePlan plan =
        inTransaction(
            context,
            () ->
                readiness.planCurrent(
                    BINDING, trust, Instant.ofEpochSecond(now - 301), inventorySnapshot));
    ReadinessProbePlan retry =
        inTransaction(
            context, () -> readiness.planCurrent(BINDING, trust, Instant.ofEpochSecond(now)));
    assertThat(retry).isEqualTo(plan);
    assertThat(retry.planDigest()).isEqualTo(plan.planDigest());
    assertThat(retry.entries()).containsExactlyElementsOf(plan.entries());
    assertThat(retry.entries())
        .extracting(ProbeEntry::tokenProfile)
        .containsExactly(
            "account-jwt-readiness-canary",
            "control-ui",
            "player-bootstrap",
            "game-session-account-delegation");
    ReadinessProbePlan durableReadback =
        inTransaction(
            context, () -> readiness.readCurrentPlan(BINDING, trust, result.operationId()));
    assertThat(durableReadback.planDigest()).isEqualTo(plan.planDigest());
    assertThat(durableReadback.entries()).containsExactlyElementsOf(plan.entries());
    ReadinessProbePlan exactSnapshotRetry =
        inTransaction(
            context,
            () ->
                readiness.planCurrent(
                    BINDING, trust, Instant.ofEpochSecond(now - 301), inventorySnapshot));
    assertThat(exactSnapshotRetry).isEqualTo(plan);
    InventorySnapshot changedSnapshot = inventorySnapshot(Instant.ofEpochSecond(now - 300));
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        readiness.planCurrent(
                            BINDING, trust, Instant.ofEpochSecond(now - 301), changedSnapshot)))
        .isInstanceOf(AccountJwtReadinessProbeRepository.InventoryPlanConflictException.class)
        .hasNoCause();
    assertThat(plan.expectedFence().durableActive()).isEmpty();
    assertThat(plan.expectedFence().publishedActive()).isEmpty();
    assertThat(plan.validatorInventoryComplete()).isFalse();
    assertThat(plan.inventorySnapshotDigest()).contains(inventorySnapshot.digest());
    assertThat(plan.applicabilityMatrixJson()).contains("PARTIAL_UNCONFIRMED");
    assertThat(plan.applicabilityMatrixJson()).contains("game-session-account-delegation");
    assertThat(plan.maximumCacheAgeSeconds()).isEqualTo(300);
    assertThat(
            inTransaction(
                context,
                () ->
                    readiness.readCurrentPromotionPrerequisites(
                        BINDING, trust, result.operationId())))
        .isEmpty();
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () -> readiness.readPromotionProof(BINDING, trust, result.operationId())))
        .isInstanceOf(AccountJwtSignerDesiredStateRepository.NoPreparedPromotionException.class);
    assertThat(plan.entries())
        .hasSize(4)
        .allSatisfy(entry -> assertThat(entry.state()).isEqualTo(ProbeState.PLANNED));
    assertThat(plan.entries()).extracting(ProbeEntry::jti).doesNotHaveDuplicates();

    assertThat(count(context, "account_jwt_validator_inventory_snapshots")).isEqualTo(1L);
    assertThat(count(context, "account_jwt_readiness_probe_plans")).isEqualTo(1L);
    var retainedPlan =
        java.util.Objects.requireNonNull(
            context
                .dsl()
                .fetchOne(
                    "SELECT inventory_snapshot_digest FROM account_jwt_readiness_probe_plans "
                        + "WHERE rotation_operation_id = ?",
                    result.operationId()),
            "Readiness plan must remain present after planning");
    assertThat(retainedPlan.get("inventory_snapshot_digest", String.class))
        .isEqualTo(inventorySnapshot.digest());
    assertThatThrownBy(
            () ->
                context
                    .dsl()
                    .execute("TRUNCATE TABLE account_jwt_validator_inventory_snapshots CASCADE"))
        .isInstanceOf(DataAccessException.class);
    assertThat(count(context, "account_jwt_validator_inventory_snapshots")).isEqualTo(1L);
    assertThat(count(context, "account_jwt_readiness_probe_plans")).isEqualTo(1L);
    var retainedPlanAfterRejectedTruncate =
        java.util.Objects.requireNonNull(
            context
                .dsl()
                .fetchOne(
                    "SELECT inventory_snapshot_digest FROM account_jwt_readiness_probe_plans "
                        + "WHERE rotation_operation_id = ?",
                    result.operationId()),
            "Readiness plan must remain present after rejected truncation");
    assertThat(retainedPlanAfterRejectedTruncate.get("inventory_snapshot_digest", String.class))
        .isEqualTo(inventorySnapshot.digest());

    Path privateMount = Files.createDirectory(tempDirectory.resolve("private-mount"));
    Path publicMount = Files.createDirectory(tempDirectory.resolve("public-mount"));
    Files.writeString(
        privateMount.resolve("pending.key"), privateBundleJson(requested, result, keyPair));
    Files.writeString(publicMount.resolve("jwks.json"), desiredJwks);
    TransactionTemplate accountTransaction =
        new TransactionTemplate(new DataSourceTransactionManager(context.dataSource()));
    int[] transientDeliveries = {0};
    String[] transientFailurePayload = {null};
    String[] safeDeliveryStage = {"NOT_STARTED"};
    Map<String, byte[]> transientProbeBytes = new LinkedHashMap<>();
    AccountJwtReadinessProbeService earlyService =
        new AccountJwtReadinessProbeService(
            readiness,
            accountTransaction,
            Clock.fixed(
                Instant.ofEpochSecond(plan.notBeforeEpochSecond() - 1), java.time.ZoneOffset.UTC),
            privateMount,
            Path.of("pending.key"),
            publicMount,
            Path.of("jwks.json"));
    assertThatThrownBy(
            () -> earlyService.issueCurrentPlan(BINDING, trust, result.operationId(), null, null))
        .isInstanceOf(AccountJwtReadinessProbeRepository.CacheAgeNotElapsedException.class);
    assertThat(
            inTransaction(
                context,
                () -> readiness.readCurrentPlan(BINDING, trust, result.operationId()).entries()))
        .allSatisfy(
            entry -> {
              assertThat(entry.state()).isEqualTo(ProbeState.PLANNED);
              assertThat(entry.compactTokenSha256()).isEmpty();
            });

    // An application clock alone cannot force EXPIRED while PostgreSQL still considers a probe
    // live.
    AccountJwtReadinessProbeService futureClockService =
        new AccountJwtReadinessProbeService(
            readiness,
            accountTransaction,
            Clock.fixed(
                Instant.ofEpochSecond(plan.expiresAtEpochSecond()), java.time.ZoneOffset.UTC),
            privateMount,
            Path.of("pending.key"),
            publicMount,
            Path.of("jwks.json"));
    assertThatThrownBy(
            () ->
                futureClockService.issueCurrentPlan(
                    BINDING, trust, result.operationId(), null, null))
        .isInstanceOf(DataAccessException.class);

    ValidatorFixture validator = createValidatorFixture(context, readiness, desiredJwks);
    var deliveryClaim =
        raceForSingleDeliveryClaim(
            context,
            readiness,
            trust,
            plan,
            validator.caller().binding(),
            validator.caller().peer(),
            now);
    assertThat(count(context, "account_jwt_readiness_delivery_claims")).isEqualTo(1L);
    AccountJwtReadinessProbeService service =
        new AccountJwtReadinessProbeService(
            readiness,
            accountTransaction,
            Clock.fixed(Instant.ofEpochSecond(now), java.time.ZoneOffset.UTC),
            privateMount,
            Path.of("pending.key"),
            publicMount,
            Path.of("jwks.json"));
    AccountJwtReadinessProbeService.OwnerInternalProbeDelivery delivery =
        (metadata, exactCompactJwt) -> {
          transientDeliveries[0]++;
          safeDeliveryStage[0] = "CALLBACK_REACHED";
          assertThat(exactCompactJwt).isNotEmpty().hasSizeLessThan(16 * 1024);
          assertThat(sha256(exactCompactJwt)).isEqualTo(metadata.compactTokenSha256());
          assertThat(transientProbeBytes.put(metadata.tokenProfile(), exactCompactJwt)).isNull();
          String compact = new String(exactCompactJwt, StandardCharsets.US_ASCII);
          assertThat(compact.split("\\.", -1)).hasSize(3);
          String[] segments = compact.split("\\.", -1);
          String claims =
              new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8);
          assertThat(claims).contains("\"sub\":\"00000000-0000-4000-8000-000000000001\"");
          assertThat(claims).doesNotContain("\"globalRoles\"");
          if (metadata.probeKind() == AccountMountedJwtSignerBundle.ProbeKind.CANARY) {
            assertThat(metadata.audience()).isEqualTo("firemud-account-jwt-readiness");
            assertThat(claims)
                .contains("\"tokenType\":\"account_jwt_readiness_canary\"")
                .doesNotContain("tokenGeneration", "issuanceFence");
          } else if (metadata.tokenProfile().equals("control-ui")) {
            assertThat(metadata.audience()).isEqualTo("control-ui");
            assertThat(claims)
                .contains("\"scopedRoles\":{}", "\"tokenGeneration\":1", "\"issuanceFence\":1")
                .contains("\"tenantAuthorityGeneration\":{}")
                .contains("\"membershipAuthorityGeneration\":{}")
                .contains("\"membershipVersion\":{}");
          } else if (metadata.tokenProfile().equals("player-bootstrap")) {
            assertThat(metadata.audience()).isEqualTo("player-bootstrap");
            assertThat(claims)
                .contains("\"scopedRoles\":{}", "\"tokenGeneration\":1", "\"issuanceFence\":1")
                .contains("\"tenantAuthorityGeneration\":{}")
                .contains("\"membershipAuthorityGeneration\":{}")
                .contains("\"membershipVersion\":{}");
          } else {
            assertThat(metadata.tokenProfile()).isEqualTo("game-session-account-delegation");
            assertThat(metadata.audience()).isEqualTo("account-service");
            assertThat(claims)
                .contains("\"tokenGeneration\":\"1\"", "\"issuanceFence\":\"1\"")
                .contains("\"issuerAuthGeneration\":\"1\"", "\"accountAuthorityGeneration\":\"1\"")
                .contains("\"tenantAuthorityGeneration\":{}")
                .contains("\"membershipAuthorityGeneration\":{}")
                .contains("\"membershipVersion\":{}");
          }
          ProbeEntry issuedBeforeCallback =
              inTransaction(
                      context,
                      () ->
                          readiness.readCurrentPlan(BINDING, trust, result.operationId()).entries())
                  .stream()
                  .filter(entry -> entry.jti().equals(metadata.jti()))
                  .findFirst()
                  .orElseThrow();
          assertThat(issuedBeforeCallback.state()).isEqualTo(ProbeState.ISSUED);
          assertThat(issuedBeforeCallback.compactTokenSha256())
              .contains(metadata.compactTokenSha256());
          safeDeliveryStage[0] = "ISSUED_READBACK_CONFIRMED";
          VerificationReceipt verification =
              validator.validate(issuedBeforeCallback, exactCompactJwt);
          assertThat(verification.verifiedKid()).isEqualTo(issuedBeforeCallback.targetKid());
          assertThat(verification.validatorInstanceId()).isEqualTo(validator.validatorInstanceId());
          assertThat(verification.validatorBindingDigest()).isEqualTo(validator.readinessDigest());
          assertThat(verification.validatorConfigRevision())
              .isEqualTo(validator.readinessRevision());
          assertThat(verification.validatorPeerUri()).isEqualTo(validator.harnessUri());
          assertThat(verification.validatorPeerSpkiSha256())
              .isEqualTo(validator.harnessSpkiSha256());
          safeDeliveryStage[0] = "VALIDATOR_CRYPTO_PROFILE_CONFIRMED";
          ReadinessProbePlan verifiedPlan =
              inTransaction(
                  context, () -> readiness.readCurrentPlan(BINDING, trust, result.operationId()));
          ProbeEntry verifiedEntry =
              verifiedPlan.entries().stream()
                  .filter(entry -> entry.jti().equals(metadata.jti()))
                  .findFirst()
                  .orElseThrow();
          assertThat(verifiedEntry.state()).isEqualTo(ProbeState.VERIFIED);
          assertThat(verifiedEntry.verificationReceipt()).contains(verification);
          assertThat(verification.sourceEntryVersion())
              .isEqualTo(issuedBeforeCallback.entryVersion());
          assertThat(verification.resultEntryVersion()).isEqualTo(verifiedEntry.entryVersion());
          assertThat(validator.validate(issuedBeforeCallback, exactCompactJwt))
              .isEqualTo(verification);
          safeDeliveryStage[0] = "OWNER_RECEIPT_READBACK_CONFIRMED";
          if (metadata.tokenProfile().equals("game-session-account-delegation")) {
            safeDeliveryStage[0] = "GSA_TRANSPORT_INTERRUPTION_ARMED";
            transientFailurePayload[0] = new String(exactCompactJwt, StandardCharsets.US_ASCII);
            throw new IllegalStateException(
                "controlled transport interruption after authenticated observation: "
                    + transientFailurePayload[0]);
          }
        };

    assertThatThrownBy(
            () ->
                service.issueCurrentPlan(
                    BINDING, trust, result.operationId(), deliveryClaim, delivery))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Readiness probe delivery was ambiguous; the operation was quarantined")
        .satisfies(
            failure -> {
              assertThat(failure.getCause()).isNull();
              assertThat(failure.getSuppressed()).isEmpty();
              assertThat(safeDeliveryStage[0]).isEqualTo("GSA_TRANSPORT_INTERRUPTION_ARMED");
              assertThat(transientFailurePayload[0]).isNotNull();
              assertThat(failure.toString().contains(transientFailurePayload[0])).isFalse();
            });
    assertThat(transientDeliveries[0]).isEqualTo(4);
    List<ProbeEntry> cleaned =
        inTransaction(
            context,
            () -> readiness.readCurrentPlan(BINDING, trust, result.operationId()).entries());
    assertThat(cleaned)
        .hasSize(4)
        .allSatisfy(
            entry -> {
              assertThat(entry.state()).isEqualTo(ProbeState.CLEANED);
              assertThat(entry.terminalOutcome()).contains("ABORTED");
              assertThat(entry.compactTokenSha256()).isPresent();
              assertThat(entry.verificationReceipt()).isPresent();
            });
    assertThat(
            service.issueCurrentPlan(BINDING, trust, result.operationId(), deliveryClaim, delivery))
        .isEqualTo(cleaned);
    assertThat(transientDeliveries[0]).isEqualTo(4);
    assertThat(transientProbeBytes.values())
        .allSatisfy(bytes -> assertThat(bytes).containsOnly((byte) 0));
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_jwt_readiness_probe_entries SET state = 'VERIFIED', "
                                    + "entry_version = entry_version + 1 WHERE rotation_operation_id = ?",
                                result.operationId())))
        .isInstanceOf(DataAccessException.class);

    List<ProbeEntry> aborted = service.abortCurrentPlan(BINDING, trust, result.operationId());
    assertThat(aborted)
        .hasSize(4)
        .allSatisfy(
            entry -> {
              assertThat(entry.state()).isEqualTo(ProbeState.CLEANED);
              assertThat(entry.terminalOutcome()).contains("ABORTED");
              assertThat(entry.compactTokenSha256()).isPresent();
              assertThat(entry.verificationReceipt()).isPresent();
            });
    assertThat(count(context, "account_jwt_readiness_probe_plans")).isEqualTo(1L);
    assertThat(count(context, "account_jwt_readiness_probe_entries")).isEqualTo(4L);
    assertThat(count(context, "account_jwt_readiness_delivery_claims")).isEqualTo(1L);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        readiness.claimSingleDelivery(
                            BINDING,
                            trust,
                            plan,
                            validator.caller().binding(),
                            validator.caller().peer(),
                            Instant.ofEpochSecond(now))))
        .isInstanceOf(AccountJwtReadinessProbeRepository.DeliveryAlreadyClaimedException.class);
    assertThat(columnNames(context, "account_jwt_readiness_probe_entries"))
        .contains("compact_token_sha256")
        .contains("verification_receipt_sha256", "validator_peer_spki_sha256")
        .doesNotContain("compact_token", "token_bytes", "credential");
  }

  private static ValidatorFixture createValidatorFixture(
      TestContext context, AccountJwtReadinessProbeRepository readiness, String desiredJwks)
      throws Exception {
    byte[] harnessSpki = "real-pg-readiness-harness-leaf-spki".getBytes(StandardCharsets.US_ASCII);
    try {
      String harnessSpkiSha256 = sha256(harnessSpki);
      long bindingValidUntil = Instant.now().plusSeconds(3_600).getEpochSecond();
      String harnessUri = "spiffe://firemud/ns/firemud-prod/sa/account-jwt-readiness-harness";
      List<String> harnessPins = List.of(harnessSpkiSha256);
      String readinessRevision = "readiness-instance-r1";
      String validatorInstanceId = "account-validator-instance-7";
      String readinessDigest =
          AccountJwtReadinessTrustBinding.computeBindingDigest(
              readinessRevision,
              BINDING.environmentId(),
              BINDING.clusterId(),
              BINDING.namespace(),
              CLUSTER_UID,
              NAMESPACE_UID,
              harnessUri,
              harnessPins,
              validatorInstanceId,
              bindingValidUntil);
      var readinessBinding =
          new AccountJwtReadinessTrustBinding.Binding(
              BINDING.environmentId(),
              BINDING.clusterId(),
              BINDING.namespace(),
              CLUSTER_UID,
              NAMESPACE_UID,
              harnessUri,
              harnessPins,
              readinessRevision,
              "account-service",
              validatorInstanceId,
              bindingValidUntil,
              readinessDigest);
      String materializerDigest =
          AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
              TRUST_REVISION,
              BINDING.environmentId(),
              BINDING.clusterId(),
              BINDING.namespace(),
              CLUSTER_UID,
              NAMESPACE_UID,
              MATERIALIZER_URI,
              MATERIALIZER_PINS);
      var materializerBinding =
          new AccountJwtSignerMaterializerTrustBinding.Binding(
              BINDING.environmentId(),
              BINDING.clusterId(),
              BINDING.namespace(),
              CLUSTER_UID,
              NAMESPACE_UID,
              MATERIALIZER_URI,
              MATERIALIZER_PINS,
              TRUST_REVISION,
              materializerDigest);

      AccountJwtReadinessTrustBinding readinessTrust = mock(AccountJwtReadinessTrustBinding.class);
      AccountJwtSignerMaterializerTrustBinding materializerTrust =
          mock(AccountJwtSignerMaterializerTrustBinding.class);
      when(readinessTrust.current()).thenReturn(Optional.of(readinessBinding));
      when(materializerTrust.current()).thenReturn(Optional.of(materializerBinding));

      AccountPublicJwksCache.SourceIdentity sourceIdentity =
          new AccountPublicJwksCache.SourceIdentity(
              BINDING.environmentId(),
              BINDING.clusterId(),
              CLUSTER_UID,
              BINDING.namespace(),
              NAMESPACE_UID,
              CONFIG_MAP_UID,
              API_REVISION,
              "https://kubernetes.example:6443",
              "9".repeat(64));
      AccountJwtJwksTrustedSource trustedSource = mock(AccountJwtJwksTrustedSource.class);
      when(trustedSource.sourceIdentity()).thenReturn(sourceIdentity);
      when(trustedSource.load())
          .thenReturn(
              new AccountPublicJwksCache.PublicJwksSnapshot(
                  sourceIdentity, desiredJwks.getBytes(StandardCharsets.UTF_8)));
      DataSourceTransactionManager transactionManager =
          new DataSourceTransactionManager(context.dataSource());
      AccountJwtReadinessValidationService validationService =
          new AccountJwtReadinessValidationService(
              readiness, readinessTrust, materializerTrust, trustedSource, transactionManager, 8);
      AccountJwtReadinessTlsInterceptor.AuthenticatedCaller caller =
          authenticatedHarnessCaller(readinessBinding, harnessSpki);
      return new ValidatorFixture(
          validationService,
          caller,
          validatorInstanceId,
          readinessDigest,
          readinessRevision,
          harnessUri,
          harnessSpkiSha256);
    } finally {
      java.util.Arrays.fill(harnessSpki, (byte) 0);
    }
  }

  private record ValidatorFixture(
      AccountJwtReadinessValidationService service,
      AccountJwtReadinessTlsInterceptor.AuthenticatedCaller caller,
      String validatorInstanceId,
      String readinessDigest,
      String readinessRevision,
      String harnessUri,
      String harnessSpkiSha256) {
    private VerificationReceipt validate(ProbeEntry entry, byte[] compactJwt) {
      assertThat(sha256(compactJwt)).isEqualTo(entry.compactTokenSha256().orElseThrow());
      ValidateReadinessProbeRequest request =
          ValidateReadinessProbeRequest.newBuilder()
              .setSchemaVersion(1)
              .setRotationOperationId(entry.rotationOperationId().toString())
              .setValidatorId(entry.validatorId())
              .setTokenProfile(entry.tokenProfile())
              .setAudience(entry.audience())
              .setProbeKind(entry.probeKind().name())
              .setJti(entry.jti().toString())
              .setCompactJwt(new String(compactJwt, StandardCharsets.US_ASCII))
              .build();
      return service.validate(request, caller);
    }
  }

  private static AccountJwtReadinessTlsInterceptor.AuthenticatedCaller authenticatedHarnessCaller(
      AccountJwtReadinessTrustBinding.Binding binding, byte[] spki) throws Exception {
    AccountJwtReadinessTrustBinding bindingProvider = mock(AccountJwtReadinessTrustBinding.class);
    when(bindingProvider.current()).thenReturn(Optional.of(binding));
    AccountJwtReadinessTlsInterceptor interceptor =
        new AccountJwtReadinessTlsInterceptor(bindingProvider);
    @SuppressWarnings("unchecked")
    ServerCall<String, String> call = mock(ServerCall.class);
    @SuppressWarnings("unchecked")
    ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
    SSLSession session = mock(SSLSession.class);
    java.security.cert.X509Certificate certificate = mock(java.security.cert.X509Certificate.class);
    java.security.PublicKey publicKey = mock(java.security.PublicKey.class);
    when(certificate.getPublicKey()).thenReturn(publicKey);
    when(publicKey.getEncoded()).thenReturn(spki);
    when(certificate.getSubjectAlternativeNames())
        .thenReturn(List.of(List.of(6, binding.expectedPeerUri())));
    when(session.getPeerCertificates())
        .thenReturn(new java.security.cert.Certificate[] {certificate});
    when(call.getAttributes())
        .thenReturn(Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build());
    AtomicReference<AccountJwtReadinessTlsInterceptor.AuthenticatedCaller> captured =
        new AtomicReference<>();
    when(next.startCall(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              captured.set(AccountJwtReadinessTlsInterceptor.authenticatedCaller());
              return new ServerCall.Listener<>() {};
            });
    interceptor.interceptCall(call, new Metadata(), next);
    return captured.get();
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
    return new TestContext(dataSource, new TransactionTemplate(transactionManager), dsl);
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

  private static AccountJwtReadinessProbeRepository.DeliveryClaim raceForSingleDeliveryClaim(
      TestContext context,
      AccountJwtReadinessProbeRepository readiness,
      TrustFence trust,
      ReadinessProbePlan plan,
      AccountJwtReadinessTrustBinding.Binding readinessBinding,
      AccountJwtReadinessTrustBinding.PeerIdentity peer,
      long claimedAtEpochSecond)
      throws Exception {
    ExecutorService contenders = Executors.newFixedThreadPool(2);
    CyclicBarrier start = new CyclicBarrier(2);
    try {
      List<Future<AccountJwtReadinessProbeRepository.DeliveryClaim>> attempts =
          List.of(
              contenders.submit(
                  () -> {
                    start.await(30, TimeUnit.SECONDS);
                    return inTransaction(
                        context,
                        () ->
                            readiness.claimSingleDelivery(
                                BINDING,
                                trust,
                                plan,
                                readinessBinding,
                                peer,
                                Instant.ofEpochSecond(claimedAtEpochSecond)));
                  }),
              contenders.submit(
                  () -> {
                    start.await(30, TimeUnit.SECONDS);
                    return inTransaction(
                        context,
                        () ->
                            readiness.claimSingleDelivery(
                                BINDING,
                                trust,
                                plan,
                                readinessBinding,
                                peer,
                                Instant.ofEpochSecond(claimedAtEpochSecond)));
                  }));
      AccountJwtReadinessProbeRepository.DeliveryClaim winner = null;
      int rejected = 0;
      for (Future<AccountJwtReadinessProbeRepository.DeliveryClaim> attempt : attempts) {
        try {
          AccountJwtReadinessProbeRepository.DeliveryClaim claim =
              attempt.get(30, TimeUnit.SECONDS);
          assertThat(winner).isNull();
          winner = claim;
        } catch (ExecutionException failure) {
          assertThat(failure.getCause())
              .isInstanceOf(
                  AccountJwtReadinessProbeRepository.DeliveryAlreadyClaimedException.class);
          rejected++;
        }
      }
      assertThat(winner).isNotNull();
      assertThat(rejected).isEqualTo(1);
      return winner;
    } finally {
      contenders.shutdownNow();
      assertThat(contenders.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static List<String> columnNames(TestContext context, String table) {
    return context
        .dsl()
        .resultQuery(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() "
                + "AND table_name = ? ORDER BY ordinal_position",
            table)
        .fetch()
        .getValues("column_name", String.class);
  }

  private static TrustFence trust() {
    return new TrustFence(CLUSTER_UID, NAMESPACE_UID, TRUST_DIGEST, TRUST_REVISION);
  }

  private static String marker(GenerationResult result) throws Exception {
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

  private static String privateBundleJson(
      GenerationRequest request, GenerationResult result, KeyPair pair) {
    Map<String, Object> bundle = new LinkedHashMap<>();
    bundle.put("version", 1);
    bundle.put("environmentId", request.binding().environmentId());
    bundle.put("clusterId", request.binding().clusterId());
    bundle.put("namespace", request.binding().namespace());
    bundle.put("operationId", request.operationId().toString());
    bundle.put("generation", result.targetGeneration());
    bundle.put("kid", result.targetKid());
    bundle.put("algorithm", "RS256");
    bundle.put(
        "privateKeyPkcs8",
        Base64.getUrlEncoder().withoutPadding().encodeToString(pair.getPrivate().getEncoded()));
    bundle.put("publicKeyFingerprint", result.publicKeyFingerprint());
    return JSON.writeValueAsString(bundle);
  }

  private static PublicJwk publicJwk(String kid, RSAPublicKey key) throws Exception {
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
    Map<String, Object> withoutKeyOps = new LinkedHashMap<>(jwk);
    withoutKeyOps.remove("key_ops");
    String jsonWithoutKeyOps = canonicalJson(withoutKeyOps);
    String fingerprint =
        sha256(
            Rfc8785CanonicalJson.canonicalizeUtf8(
                JSON.writeValueAsString(Map.of("e", exponent, "kty", "RSA", "n", modulus))));
    return new PublicJwk(json, jsonWithoutKeyOps, fingerprint);
  }

  private static String canonicalJson(Map<String, Object> value) throws Exception {
    return new String(
        Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)),
        StandardCharsets.UTF_8);
  }

  private static String jwks(String retainedJwk, String targetJwk) {
    return "{\"keys\":[" + retainedJwk + "," + targetJwk + "]}";
  }

  private static String encodeUnsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    int offset = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(java.util.Arrays.copyOfRange(bytes, offset, bytes.length));
  }

  private static KeyPair generateRsa3072() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3_072);
    return generator.generateKeyPair();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required for the persistence fixture", ex);
    }
  }

  private static InventorySnapshot inventorySnapshot(Instant observedAt) {
    byte[] bytes =
        ("{\"domain\":\"firemud-account-validator-inventory/v1\","
                + "\"environmentId\":\"prod\",\"clusterId\":\"prod-cluster-1\","
                + "\"clusterIncarnationUid\":\""
                + CLUSTER_UID
                + "\","
                + "\"namespace\":\"firemud-prod\",\"namespaceUid\":\""
                + NAMESPACE_UID
                + "\","
                + "\"observedAt\":\""
                + observedAt
                + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    InventorySnapshot snapshot = mock(InventorySnapshot.class);
    when(snapshot.observedAt()).thenReturn(observedAt);
    when(snapshot.environmentId()).thenReturn(BINDING.environmentId());
    when(snapshot.clusterId()).thenReturn(BINDING.clusterId());
    when(snapshot.clusterIncarnationUid()).thenReturn(CLUSTER_UID);
    when(snapshot.namespace()).thenReturn(BINDING.namespace());
    when(snapshot.namespaceUid()).thenReturn(NAMESPACE_UID);
    when(snapshot.apiBindingRevision()).thenReturn(API_REVISION);
    when(snapshot.apiBindingDigest()).thenReturn(API_DIGEST);
    when(snapshot.inventoryBindingRevision()).thenReturn("inventory-r1");
    when(snapshot.inventoryBindingDigest()).thenReturn("c".repeat(64));
    when(snapshot.canonicalBytes()).thenReturn(bytes);
    when(snapshot.digest()).thenReturn(sha256(bytes));
    return snapshot;
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
          EXTERNAL_POSTGRES_URL_ENV + " must target jdbc:postgresql://127.0.0.1:<port>/postgres",
          ex);
    }
    if (!"postgresql".equals(uri.getScheme())
        || !"127.0.0.1".equals(uri.getHost())
        || uri.getPort() < 1
        || uri.getPort() > 65_535
        || !"/postgres".equals(uri.getPath())
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalStateException(
          EXTERNAL_POSTGRES_URL_ENV + " must target jdbc:postgresql://127.0.0.1:<port>/postgres");
    }
    return jdbcUrl;
  }

  private record PublicJwk(String json, String jsonWithoutKeyOps, String fingerprint) {}

  private record TestContext(
      DriverManagerDataSource dataSource, TransactionTemplate transaction, DSLContext dsl) {}
}
