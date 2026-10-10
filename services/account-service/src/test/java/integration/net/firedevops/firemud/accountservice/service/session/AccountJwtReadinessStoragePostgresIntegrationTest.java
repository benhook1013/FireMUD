package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding.PeerIdentity;
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding.ProfileExpectation;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ExpectedPod;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeEntry;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeState;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessProbePlan;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveJwksPromotionObservation;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.EnrollmentIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PrivatePromotionObservation;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PromotionPreparation;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PublicKeyIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.repository.AccountJwtValidatorInventoryRepository;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerSelector.LocalIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.AuthenticatedAcceptance;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.ProbeExpectation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationContext;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationPurpose;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.PodObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ValidatorObservation;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Physical PostgreSQL proof for Account-owned V2 readiness inventory, signing-state, per-Pod
 * receipt storage, and dispatched promotion recovery after plan expiry. The inventory,
 * authenticated-acceptance, and resource readback inputs are synthetic owner values, as in the
 * retained donor proof; this does not establish live Kubernetes currentness, receiver
 * authentication, readiness activation, or signer activation. The local ephemeral canary key is
 * only a real signing precondition for the storage transition.
 */
class AccountJwtReadinessStoragePostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "jwt_readiness_owner_storage";
  private static final int BULK_READINESS_PLAN_HEADROOM_SECONDS = 300;
  private static final int DISPATCHED_RECOVERY_EXPIRY_WAIT_SECONDS =
      AccountJwtReadinessProbeRepository.MAX_VALIDATOR_CACHE_AGE_SECONDS
          + AccountJwtReadinessProbeRepository.PROBE_LIFETIME_SECONDS
          + 30;
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
  private static final String TRUST_REVISION = "trust-r1";
  private static final String TRUST_DIGEST = "b".repeat(64);
  private static final String API_DIGEST = "c".repeat(64);
  private static final String API_REVISION = "api-r1";
  private static final String CONFIG_MAP_UID = "44444444-4444-4444-8444-444444444444";
  private static final String INITIAL_CONFIG_MAP_RESOURCE_VERSION = "41";
  private static final Map<String, String> INITIAL_CONFIG_MAP_DATA =
      Map.of("jwks.json", "{\"keys\":[]}", "unrelated.txt", "preserve");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir Path tempDirectory;

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
  void rejectsMissingAndNullReadinessInventoryFieldsWithIndependentRawSqlAttempts()
      throws Exception {
    TestContext context = newTestContext(null);
    OwnerState owner = createOwnerState(context);
    ObservationContext observationContext =
        inTransaction(
            context,
            () -> owner.readiness().readCurrentInventoryObservationContext(BINDING, owner.trust()));
    long databaseNow = databaseNow(context);
    long plannedAtEpochSecond =
        databaseNow
            - AccountJwtReadinessProbeRepository.MAX_VALIDATOR_CACHE_AGE_SECONDS
            - AccountJwtReadinessProbeRepository.PROBE_LIFETIME_SECONDS
            + BULK_READINESS_PLAN_HEADROOM_SECONDS;
    Instant plannedAt = Instant.ofEpochSecond(plannedAtEpochSecond);
    InventorySnapshot inventory = completeInventorySnapshot(plannedAt, observationContext);
    inTransaction(
        context, () -> owner.inventory().persistOrReadback(inventory, BINDING, owner.trust()));

    ReadinessProbePlan planTemplate =
        context
            .transaction()
            .execute(
                status -> {
                  ReadinessProbePlan candidate =
                      owner.readiness().planCurrent(BINDING, owner.trust(), plannedAt, inventory);
                  status.setRollbackOnly();
                  return candidate;
                });
    assertThat(planTemplate).isNotNull();
    assertThat(count(context, "account_jwt_readiness_probe_plans")).isZero();

    assertReadinessInventoryClosureGuardRejectsMatrixMismatch(
        context,
        planTemplate,
        readinessMatrixWithExtraValidator(planTemplate.applicabilityMatrixJson()),
        "validator ID set differs from the canonical inventory snapshot");
    assertReadinessInventoryClosureGuardRejectsMatrixMismatch(
        context,
        planTemplate,
        readinessMatrixWithDifferentApplicableProfiles(planTemplate.applicabilityMatrixJson()),
        "matching validator IDs have different applicable profile membership");

    ReadinessProbePlan legacyPlanTemplate =
        context
            .transaction()
            .execute(
                status -> {
                  ReadinessProbePlan candidate =
                      owner.readiness().planCurrent(BINDING, owner.trust(), plannedAt);
                  status.setRollbackOnly();
                  return candidate;
                });
    assertThat(legacyPlanTemplate).isNotNull();
    assertThat(legacyPlanTemplate.planVersion()).isEqualTo(1);
    assertThat(count(context, "account_jwt_readiness_probe_plans")).isZero();

    assertReadinessPlanInventoryGuardRejects(
        context,
        legacyPlanTemplate,
        List.of("inventoryStatus", "schemaVersion", "validatorId", "applicableProfiles"));
    assertReadinessPlanInventoryGuardRejects(
        context,
        planTemplate,
        List.of(
            "inventoryStatus",
            "schemaVersion",
            "validatorId",
            "productionProfiles",
            "applicableProfiles"));

    ReadinessProbePlan canonicalPlan =
        inTransaction(
            context,
            () -> owner.readiness().planCurrent(BINDING, owner.trust(), plannedAt, inventory));
    assertThat(canonicalPlan.validatorInventoryComplete()).isTrue();
    assertThat(count(context, "account_jwt_readiness_probe_plans")).isEqualTo(1L);
    assertThat(count(context, "account_jwt_readiness_probe_entries")).isEqualTo(8L);
    assertThat(count(context, "account_jwt_readiness_validator_pods")).isEqualTo(12L);
  }

  @Test
  void recoversDispatchedReceiptBoundPromotionAfterReadinessPlanExpiry() throws Exception {
    TestContext context = newTestContext(null);
    OwnerState owner = createOwnerState(context);
    ObservationContext observationContext =
        inTransaction(
            context,
            () -> owner.readiness().readCurrentInventoryObservationContext(BINDING, owner.trust()));
    long databaseNow = databaseNow(context);
    Instant plannedAt = Instant.ofEpochSecond(databaseNow);
    InventorySnapshot inventory = completeInventorySnapshot(plannedAt, observationContext);
    ReadinessProbePlan plan =
        inTransaction(
            context,
            () -> owner.readiness().planCurrent(BINDING, owner.trust(), plannedAt, inventory));
    assertThat(plan.expiresAtEpochSecond())
        .isEqualTo(
            databaseNow
                + AccountJwtReadinessProbeRepository.MAX_VALIDATOR_CACHE_AGE_SECONDS
                + AccountJwtReadinessProbeRepository.PROBE_LIFETIME_SECONDS);
    assertThat(plan.entries()).hasSize(8);

    Path privateMount = Files.createDirectory(tempDirectory.resolve("expiry-private-mount"));
    Path publicMount = Files.createDirectory(tempDirectory.resolve("expiry-public-mount"));
    Files.writeString(
        privateMount.resolve("pending.key"),
        privateBundleJson(owner.request(), owner.result(), owner.keyPair()));
    Files.writeString(publicMount.resolve("jwks.json"), owner.desiredJwks());
    var expectedIdentity =
        new AccountMountedJwtSignerBundle.ExpectedIdentity(
            BINDING.environmentId(),
            BINDING.clusterId(),
            BINDING.namespace(),
            owner.result().operationId().toString(),
            owner.result().targetGeneration(),
            owner.result().targetKid(),
            owner.result().publicKeyFingerprint());
    assertThat(
            completeRemainingProbes(
                context, owner, plan, inventory, privateMount, publicMount, expectedIdentity))
        .isEqualTo(12L);

    var proof =
        inTransaction(
            context,
            () ->
                owner
                    .readiness()
                    .readCurrentPromotionPrerequisites(
                        BINDING, owner.trust(), owner.result().operationId(), inventory)
                    .orElseThrow());
    assertThat(proof.verifiedProbes()).hasSize(plan.entries().size());
    byte[] exactPreimage = proof.readinessEvidencePreimage();
    assertThat(sha256(exactPreimage)).isEqualTo(proof.readinessEvidenceDigest());

    var publicationEvidence = proof.publication();
    PromotionPreparation preparation =
        new PromotionPreparation(
            proof.generationResult().operationId(),
            proof.generationResult(),
            new EnrollmentIdentity(
                CLUSTER_UID,
                NAMESPACE_UID,
                TRUST_DIGEST,
                TRUST_REVISION,
                API_DIGEST,
                API_REVISION,
                CONFIG_MAP_UID,
                INITIAL_CONFIG_MAP_RESOURCE_VERSION,
                AccountJwtJwksPublicationRepository.snapshotDigest(INITIAL_CONFIG_MAP_DATA)),
            API_DIGEST,
            API_REVISION,
            CONFIG_MAP_UID,
            publicationEvidence.receipt().observedResourceVersion(),
            publicationEvidence.intent().intentDigest(),
            publicationEvidence.receipt().receiptDigest(),
            publicationEvidence.mountedCorrespondence().observationDigest(),
            proof.plan().planDigest(),
            proof.readinessEvidenceDigest(),
            publicationEvidence.intent().jwksJson());
    var prepared =
        inTransaction(
            context,
            () ->
                owner
                    .desired()
                    .prepareCurrentGeneration(BINDING, owner.trust(), preparation, proof));
    assertThat(prepared.status()).isEqualTo("PREPARED");

    AccountJwtSignerDesiredStateRepository restartedDesired =
        new AccountJwtSignerDesiredStateRepository(context.dsl());
    AccountJwtJwksPublicationRepository restartedPublication =
        new AccountJwtJwksPublicationRepository(context.dsl(), restartedDesired);
    AccountJwtValidatorInventoryRepository restartedInventory =
        new AccountJwtValidatorInventoryRepository(context.dsl());
    AccountJwtReadinessProbeRepository restartedReadiness =
        new AccountJwtReadinessProbeRepository(
            context.dsl(), restartedDesired, restartedPublication, restartedInventory);
    var restartedProof =
        inTransaction(
            context,
            () ->
                restartedReadiness
                    .readPromotionProof(
                        BINDING, owner.trust(), owner.result().operationId(), inventory)
                    .orElseThrow());
    assertThat(restartedProof.readinessEvidencePreimage()).isEqualTo(exactPreimage);
    assertThat(databaseClockNow(context)).isLessThan(plan.expiresAtEpochSecond());
    var dispatchedEvidence =
        inTransaction(
            context,
            () -> restartedDesired.readAndMarkPreparedPromotionDispatched(BINDING, owner.trust()));
    assertThat(dispatchedEvidence.promotion().privatePromotionDispatched()).isTrue();
    var dispatchedProof =
        inTransaction(
            context,
            () ->
                restartedReadiness
                    .readPromotionProof(BINDING, owner.trust(), owner.result().operationId())
                    .orElseThrow());
    assertThat(dispatchedProof.readinessEvidencePreimage()).isEqualTo(exactPreimage);
    OwnerState restartedOwner =
        new OwnerState(
            restartedDesired,
            restartedPublication,
            restartedInventory,
            restartedReadiness,
            owner.trust(),
            owner.request(),
            owner.result(),
            owner.keyPair(),
            owner.desiredJwks());
    assertThat(
            inTransaction(
                context,
                () ->
                    restartedOwner
                        .desired()
                        .prepareCurrentGeneration(
                            BINDING, restartedOwner.trust(), preparation, dispatchedProof)))
        .isEqualTo(prepared);

    var dispatchedPromotion = dispatchedEvidence.promotion();
    PrivatePromotionObservation privatePromotionObservation =
        new PrivatePromotionObservation(
            dispatchedPromotion.operationId(),
            dispatchedPromotion.requestDigest(),
            dispatchedPromotion.generationOperationId(),
            dispatchedPromotion.generationOperationDigest(),
            owner.result().secretUid(),
            dispatchedPromotion.expectedPrivateResourceVersion(),
            "14",
            new PublicKeyIdentity(
                dispatchedPromotion.targetGeneration(),
                dispatchedPromotion.targetKid(),
                dispatchedPromotion.targetPublicKeyFingerprint()),
            Optional.empty(),
            List.of("current"));
    var privatePromotionReceipt =
        inTransaction(
            context,
            () ->
                restartedDesired.recordPrivatePromotionResult(
                    BINDING, owner.trust(), privatePromotionObservation));
    var activeJwksPromotionReceipt =
        inTransaction(
            context,
            () ->
                restartedDesired.recordActiveJwksPromotionResult(
                    BINDING,
                    owner.trust(),
                    new ActiveJwksPromotionObservation(
                        dispatchedPromotion.publicConfigMapUid(),
                        dispatchedPromotion.expectedPublicResourceVersion(),
                        "43",
                        dispatchedPromotion.expectedPublicJwksJson(),
                        dispatchedPromotion.expectedActiveMarkerJson())));
    var receiptBoundPrepared =
        inTransaction(
            context,
            () -> restartedDesired.readPreparedGenerationForRecovery(BINDING, owner.trust()));
    assertThat(receiptBoundPrepared.promotion().privatePromotionReceiptDigest())
        .contains(privatePromotionReceipt.receiptDigest());
    assertThat(receiptBoundPrepared.promotion().activeJwksReceiptDigest())
        .contains(activeJwksPromotionReceipt.receiptDigest());

    awaitDatabaseClockAtOrAfter(context, plan.expiresAtEpochSecond());
    assertThat(databaseClockNow(context)).isGreaterThanOrEqualTo(plan.expiresAtEpochSecond());
    var expiredDispatchedProof =
        inTransaction(
            context,
            () ->
                restartedReadiness
                    .readPromotionProof(BINDING, owner.trust(), owner.result().operationId())
                    .orElseThrow());
    assertThat(expiredDispatchedProof.plan().planDigest()).isEqualTo(plan.planDigest());
    assertThat(expiredDispatchedProof.plan().expiresAtEpochSecond())
        .isEqualTo(plan.expiresAtEpochSecond());
    assertThat(expiredDispatchedProof.readinessEvidencePreimage()).isEqualTo(exactPreimage);
    assertThat(expiredDispatchedProof.readinessEvidenceDigest())
        .isEqualTo(proof.readinessEvidenceDigest());
    assertThat(expiredDispatchedProof.verifiedProbes()).hasSize(plan.entries().size());
    assertThat(
            inTransaction(
                context,
                () ->
                    restartedOwner
                        .desired()
                        .prepareCurrentGeneration(
                            BINDING, restartedOwner.trust(), preparation, expiredDispatchedProof)))
        .isEqualTo(prepared);

    var committed =
        inTransaction(
            context,
            () ->
                restartedDesired.commitPreparedPromotion(
                    BINDING, owner.trust(), prepared.operationId(), expiredDispatchedProof));
    assertThat(committed.promotion().status()).isEqualTo("COMMITTED");
    assertThat(committed.promotion().privatePromotionReceiptDigest())
        .contains(privatePromotionReceipt.receiptDigest());
    assertThat(committed.promotion().activeJwksReceiptDigest())
        .contains(activeJwksPromotionReceipt.receiptDigest());
    assertThat(committed.privateReceipt()).isEqualTo(privatePromotionReceipt);
    assertThat(committed.publicReceipt()).isEqualTo(activeJwksPromotionReceipt);
  }

  @Test
  void persistsExactV2PodProjectionAndClosesPerPodReceiptsAcrossRepositoryRestart()
      throws Exception {
    TestContext context = newTestContext(null);
    OwnerState owner = createOwnerState(context);
    ObservationContext observationContext =
        inTransaction(
            context,
            () -> owner.readiness().readCurrentInventoryObservationContext(BINDING, owner.trust()));
    assertThat(observationContext.purpose())
        .isEqualTo(ObservationPurpose.INITIAL_NO_ACTIVE_SIGNER_CANDIDATES);
    assertThat(observationContext.operationId()).isEqualTo(owner.result().operationId());
    assertThat(observationContext.operationDigest()).isEqualTo(owner.result().operationDigest());

    long databaseNow = databaseNow(context);
    long plannedAtEpochSecond =
        databaseNow
            - AccountJwtReadinessProbeRepository.MAX_VALIDATOR_CACHE_AGE_SECONDS
            - AccountJwtReadinessProbeRepository.PROBE_LIFETIME_SECONDS
            + BULK_READINESS_PLAN_HEADROOM_SECONDS;
    Instant planTimestamp = Instant.ofEpochSecond(plannedAtEpochSecond);
    InventorySnapshot planningInventory =
        completeInventorySnapshot(planTimestamp, observationContext);
    ReadinessProbePlan plan =
        inTransaction(
            context,
            () ->
                owner
                    .readiness()
                    .planCurrent(BINDING, owner.trust(), planTimestamp, planningInventory));
    assertThat(plan.planVersion()).isEqualTo(2);
    assertThat(plan.expiresAtEpochSecond())
        .isEqualTo(databaseNow + BULK_READINESS_PLAN_HEADROOM_SECONDS);
    assertThat(plan.validatorInventoryComplete()).isTrue();
    assertThat(plan.inventorySnapshotDigest()).contains(planningInventory.digest());
    assertThat(plan.trustFence()).isEqualTo(owner.trust());
    assertThat(plan.expectedFence().durableActive()).isEmpty();
    assertThat(plan.expectedFence().publishedActive()).isEmpty();
    assertThat(plan.entries())
        .hasSize(8)
        .allSatisfy(
            entry -> {
              assertThat(entry.expectedActive()).isEmpty();
              assertThat(entry.state()).isEqualTo(ProbeState.PLANNED);
            });
    assertThat(count(context, "account_jwt_readiness_validator_pods")).isEqualTo(12L);
    assertThat(count(context, "account_jwt_readiness_probe_plans")).isEqualTo(1L);
    assertThat(count(context, "account_jwt_readiness_probe_entries")).isEqualTo(8L);
    assertThat(count(context, "account_jwt_validator_inventory_snapshots")).isEqualTo(1L);

    long harnessValidUntilEpochSecond = plan.expiresAtEpochSecond() + 600L;
    String harnessPeerUri =
        "spiffe://firemud/ns/" + BINDING.namespace() + "/sa/account-jwt-readiness-harness";
    List<String> harnessPins = List.of("f".repeat(64));
    String harnessInstanceId = "account-harness-test";
    String harnessBindingDigest =
        AccountJwtReadinessTrustBinding.computeBindingDigest(
            "readiness-r1",
            BINDING.environmentId(),
            BINDING.clusterId(),
            BINDING.namespace(),
            CLUSTER_UID,
            NAMESPACE_UID,
            harnessPeerUri,
            harnessPins,
            harnessInstanceId,
            harnessValidUntilEpochSecond);
    var readinessBinding =
        new AccountJwtReadinessTrustBinding.Binding(
            BINDING.environmentId(),
            BINDING.clusterId(),
            BINDING.namespace(),
            CLUSTER_UID,
            NAMESPACE_UID,
            harnessPeerUri,
            harnessPins,
            "readiness-r1",
            "account-service",
            harnessInstanceId,
            harnessValidUntilEpochSecond,
            harnessBindingDigest);
    var deliveryClaim =
        inTransaction(
            context,
            () ->
                owner
                    .readiness()
                    .claimSingleDelivery(
                        BINDING,
                        owner.trust(),
                        plan,
                        readinessBinding,
                        new PeerIdentity(harnessPeerUri, harnessPins.getFirst()),
                        Instant.ofEpochSecond(databaseNow)));
    assertThat(deliveryClaim.planDigest()).isEqualTo(plan.planDigest());
    assertThat(count(context, "account_jwt_readiness_delivery_claims")).isEqualTo(1L);

    InventorySnapshot refreshedInventory =
        completeInventorySnapshot(Instant.ofEpochSecond(databaseNow), observationContext);
    assertThat(refreshedInventory.digest()).isEqualTo(planningInventory.digest());
    ReadinessProbePlan exactPlanReplay =
        inTransaction(
            context,
            () ->
                owner
                    .readiness()
                    .planCurrent(
                        BINDING,
                        owner.trust(),
                        Instant.ofEpochSecond(databaseNow),
                        refreshedInventory));
    assertThat(exactPlanReplay).isEqualTo(plan);
    assertThat(
            inTransaction(
                context,
                () ->
                    owner
                        .readiness()
                        .readCurrentPlan(BINDING, owner.trust(), owner.result().operationId())))
        .isEqualTo(plan);

    ObservationContext wrongPurpose =
        new ObservationContext(
            ObservationPurpose.STRICT_READY,
            observationContext.operationId(),
            observationContext.operationDigest());
    InventorySnapshot wrongPurposeInventory =
        completeInventorySnapshot(Instant.ofEpochSecond(databaseNow), wrongPurpose);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        owner
                            .readiness()
                            .planCurrent(
                                BINDING,
                                owner.trust(),
                                Instant.ofEpochSecond(databaseNow),
                                wrongPurposeInventory)))
        .isInstanceOf(AccountJwtReadinessProbeRepository.InventoryPlanConflictException.class);
    InventorySnapshot changedInventory =
        completeInventorySnapshot(
            Instant.ofEpochSecond(databaseNow), "10.11.0.99", observationContext);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        owner
                            .readiness()
                            .planCurrent(
                                BINDING,
                                owner.trust(),
                                Instant.ofEpochSecond(databaseNow),
                                changedInventory)))
        .isInstanceOf(AccountJwtReadinessProbeRepository.InventoryPlanConflictException.class);

    ProbeEntry plannedCanary =
        plan.entries().stream()
            .filter(
                entry ->
                    entry.validatorId().equals("account-service")
                        && entry.probeKind() == AccountMountedJwtSignerBundle.ProbeKind.CANARY)
            .findFirst()
            .orElseThrow();
    Path privateMount = Files.createDirectory(tempDirectory.resolve("private-mount"));
    Path publicMount = Files.createDirectory(tempDirectory.resolve("public-mount"));
    Files.writeString(
        privateMount.resolve("pending.key"),
        privateBundleJson(owner.request(), owner.result(), owner.keyPair()));
    Files.writeString(publicMount.resolve("jwks.json"), owner.desiredJwks());
    var expectedIdentity =
        new AccountMountedJwtSignerBundle.ExpectedIdentity(
            BINDING.environmentId(),
            BINDING.clusterId(),
            BINDING.namespace(),
            owner.result().operationId().toString(),
            owner.result().targetGeneration(),
            owner.result().targetKid(),
            owner.result().publicKeyFingerprint());
    var signingSpec =
        new AccountMountedJwtSignerBundle.ReadinessProbeSigningSpec(
            plannedCanary.validatorId(),
            plannedCanary.probeKind(),
            plannedCanary.tokenProfile(),
            plannedCanary.audience(),
            plannedCanary.jti(),
            plannedCanary.targetGeneration(),
            plannedCanary.targetKid(),
            plannedCanary.plannedIssuedAtEpochSecond(),
            plannedCanary.expiresAtEpochSecond());
    Instant plannedAt = Instant.ofEpochSecond(plannedCanary.plannedIssuedAtEpochSecond());
    AccountMountedJwtSignerBundle.SignedProbeDigest signed =
        AccountMountedJwtSignerBundle.signReadinessProbeDigest(
            privateMount,
            Path.of("pending.key"),
            publicMount,
            Path.of("jwks.json"),
            expectedIdentity,
            signingSpec,
            (digest, compactJwt) -> {
              assertThat(sha256(compactJwt)).isEqualTo(digest.compactTokenSha256());
              ProbeEntry attempt =
                  inTransaction(
                      context,
                      () ->
                          owner
                              .readiness()
                              .recordSigningAttempt(BINDING, owner.trust(), digest, plannedAt));
              assertThat(attempt.state()).isEqualTo(ProbeState.PLANNED);
              assertThat(attempt.signingAttemptedAtEpochSecond())
                  .contains(digest.issuedAtEpochSecond());
              ProbeEntry issued =
                  inTransaction(
                      context,
                      () ->
                          owner
                              .readiness()
                              .recordIssued(BINDING, owner.trust(), digest, plannedAt));
              assertThat(issued.state()).isEqualTo(ProbeState.ISSUED);
              assertThat(issued.compactTokenSha256()).contains(digest.compactTokenSha256());
            });
    assertThat(signed.compactTokenSha256()).hasSize(64);

    ProbeEntry issuedEntry =
        inTransaction(
                context,
                () ->
                    owner
                        .readiness()
                        .readCurrentPlan(BINDING, owner.trust(), owner.result().operationId()))
            .entries()
            .stream()
            .filter(entry -> entry.jti().equals(plannedCanary.jti()))
            .findFirst()
            .orElseThrow();
    assertThat(issuedEntry.state()).isEqualTo(ProbeState.ISSUED);
    assertThat(issuedEntry.compactTokenSha256()).contains(signed.compactTokenSha256());
    assertThat(
            inTransaction(
                context,
                () -> owner.readiness().recordIssued(BINDING, owner.trust(), signed, plannedAt)))
        .isEqualTo(issuedEntry);

    List<ExpectedPod> expectedPods =
        inTransaction(
            context,
            () ->
                owner
                    .readiness()
                    .readCurrentExpectedPods(
                        BINDING, owner.trust(), issuedEntry, refreshedInventory));
    assertThat(expectedPods).hasSize(2);
    assertThat(expectedPods)
        .extracting(pod -> pod.target().podUid())
        .containsExactly(
            "77777777-7777-4777-8777-777777777777", "88888888-8888-4888-8888-888888888888");
    assertThat(expectedPods)
        .extracting(pod -> pod.target().exactPodEndpoint().orElseThrow().toString())
        .containsExactly("grpcs://10.11.0.11:9443", "grpcs://10.11.0.12:9443");
    assertThat(expectedPods)
        .allSatisfy(
            pod -> {
              assertThat(pod.planDigest()).isEqualTo(plan.planDigest());
              assertThat(pod.jti()).isEqualTo(issuedEntry.jti());
              assertThat(pod.target().inventorySnapshotDigest())
                  .isEqualTo(planningInventory.digest());
              assertThat(pod.target().applicabilityMatrixDigest())
                  .isEqualTo(plan.applicabilityMatrixDigest());
              assertThat(pod.target().namespaceUid()).isEqualTo(NAMESPACE_UID);
              assertThat(pod.target().exactPodEndpoint()).isPresent();
              assertThat(pod.target().canonicalServiceUri())
                  .contains("spiffe://firemud/ns/firemud-prod/sa/account-jwt-validator");
              assertThat(pod.target().podLeafSpkiSha256()).isPresent();
              assertThat(pod.target().expectation()).isEqualTo(ProbeExpectation.ACCEPT);
            });

    var selector = ownerSelector(plan, issuedEntry, expectedPods.getFirst());
    var ownerEvidence =
        inTransaction(
            context,
            () ->
                owner
                    .readiness()
                    .readCurrentProbeOwner(
                        BINDING, owner.trust(), selector, refreshedInventory, BINDING.namespace()));
    assertThat(ownerEvidence.plan().planDigest()).isEqualTo(plan.planDigest());
    assertThat(ownerEvidence.entry()).isEqualTo(issuedEntry);
    assertThat(ownerEvidence.expectedPod()).isEqualTo(expectedPods.getFirst());
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        owner
                            .readiness()
                            .readCurrentProbeOwner(
                                BINDING,
                                owner.trust(),
                                ownerSelector(
                                    plan,
                                    issuedEntry,
                                    expectedPods.getFirst(),
                                    issuedEntry.expectedActive(),
                                    "inventory-r2",
                                    "d".repeat(64)),
                                refreshedInventory,
                                BINDING.namespace())))
        .isInstanceOf(AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        owner
                            .readiness()
                            .readCurrentProbeOwner(
                                BINDING,
                                owner.trust(),
                                ownerSelector(
                                    plan,
                                    issuedEntry,
                                    expectedPods.getFirst(),
                                    issuedEntry.expectedActive(),
                                    "inventory-r1",
                                    "e".repeat(64)),
                                refreshedInventory,
                                BINDING.namespace())))
        .isInstanceOf(AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException.class);
    var inventedActiveFence =
        ownerSelector(
            plan,
            issuedEntry,
            expectedPods.getFirst(),
            Optional.of(new AccountJwtSignerDesiredStateRepository.ActiveSigner("2", "old-key")));
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        owner
                            .readiness()
                            .readCurrentProbeOwner(
                                BINDING,
                                owner.trust(),
                                inventedActiveFence,
                                refreshedInventory,
                                BINDING.namespace())))
        .isInstanceOf(AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException.class);

    AuthenticatedAcceptance firstAcceptance =
        testAcceptance(issuedEntry, expectedPods.getFirst(), databaseNow);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        owner
                            .readiness()
                            .recordPodAcceptance(
                                BINDING,
                                owner.trust(),
                                issuedEntry,
                                expectedPods.getFirst(),
                                firstAcceptance,
                                changedInventory)))
        .isInstanceOf(AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException.class);
    assertThat(count(context, "account_jwt_readiness_pod_receipts")).isZero();

    AuthenticatedAcceptance wrongEndpoint =
        testAcceptance(
            issuedEntry,
            expectedPods.getFirst(),
            databaseNow,
            issuedEntry.targetKid(),
            "grpcs://192.0.2.1:9443");
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        owner
                            .readiness()
                            .recordPodAcceptance(
                                BINDING,
                                owner.trust(),
                                issuedEntry,
                                expectedPods.getFirst(),
                                wrongEndpoint,
                                refreshedInventory)))
        .isInstanceOf(AccountJwtReadinessProbeRepository.IdempotencyConflictException.class);
    AuthenticatedAcceptance wrongKid =
        testAcceptance(
            issuedEntry, expectedPods.getFirst(), databaseNow, issuedEntry.targetKid() + "-wrong");
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        owner
                            .readiness()
                            .recordPodAcceptance(
                                BINDING,
                                owner.trust(),
                                issuedEntry,
                                expectedPods.getFirst(),
                                wrongKid,
                                refreshedInventory)))
        .isInstanceOf(AccountJwtReadinessProbeRepository.IdempotencyConflictException.class);

    ProbeEntry partial =
        inTransaction(
            context,
            () ->
                owner
                    .readiness()
                    .recordPodAcceptance(
                        BINDING,
                        owner.trust(),
                        issuedEntry,
                        expectedPods.getFirst(),
                        firstAcceptance,
                        refreshedInventory));
    assertThat(partial.state()).isEqualTo(ProbeState.ISSUED);
    assertThat(partial.verificationReceipt()).isEmpty();
    assertThat(count(context, "account_jwt_readiness_pod_receipts")).isEqualTo(1L);
    assertThat(
            inTransaction(
                context,
                () ->
                    owner
                        .readiness()
                        .recordPodAcceptance(
                            BINDING,
                            owner.trust(),
                            issuedEntry,
                            expectedPods.getFirst(),
                            firstAcceptance,
                            refreshedInventory)))
        .isEqualTo(partial);
    assertThat(count(context, "account_jwt_readiness_pod_receipts")).isEqualTo(1L);

    AuthenticatedAcceptance mismatchedDuplicate =
        testAcceptance(
            issuedEntry,
            expectedPods.getFirst(),
            databaseNow,
            issuedEntry.targetKid(),
            "grpcs://192.0.2.2:9443");
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        owner
                            .readiness()
                            .recordPodAcceptance(
                                BINDING,
                                owner.trust(),
                                issuedEntry,
                                expectedPods.getFirst(),
                                mismatchedDuplicate,
                                refreshedInventory)))
        .isInstanceOf(AccountJwtReadinessProbeRepository.IdempotencyConflictException.class);
    assertThat(count(context, "account_jwt_readiness_pod_receipts")).isEqualTo(1L);

    AuthenticatedAcceptance secondAcceptance =
        testAcceptance(issuedEntry, expectedPods.get(1), databaseNow);
    insertRawPodReceipt(context, partial, expectedPods.get(1), secondAcceptance);
    assertThat(count(context, "account_jwt_readiness_pod_receipts")).isEqualTo(2L);
    assertReadinessVerifiedTransitionRejectsChangedSigningAttempt(
        context, partial, expectedPods.size(), secondAcceptance.observedAtEpochSecond());

    ProbeEntry verified =
        inTransaction(
            context,
            () ->
                owner
                    .readiness()
                    .recordPodAcceptance(
                        BINDING,
                        owner.trust(),
                        partial,
                        expectedPods.get(1),
                        secondAcceptance,
                        refreshedInventory));
    assertThat(verified.state()).isEqualTo(ProbeState.VERIFIED);
    assertThat(verified.signingAttemptedAtEpochSecond())
        .isEqualTo(partial.signingAttemptedAtEpochSecond());
    assertThat(verified.verificationReceipt()).isPresent();
    assertThat(verified.verificationReceipt().orElseThrow().receiptVersion()).isEqualTo(2);
    assertThat(verified.verificationReceipt().orElseThrow().podReceiptCount()).isEqualTo(2);
    assertThat(count(context, "account_jwt_readiness_pod_receipts")).isEqualTo(2L);

    assertThat(
            inTransaction(
                context,
                () ->
                    owner
                        .readiness()
                        .recordPodAcceptance(
                            BINDING,
                            owner.trust(),
                            verified,
                            expectedPods.get(1),
                            testAcceptance(issuedEntry, expectedPods.get(1), databaseNow),
                            refreshedInventory)))
        .isEqualTo(verified);
    AuthenticatedAcceptance changedClosedReceipt =
        testAcceptance(
            issuedEntry,
            expectedPods.get(1),
            databaseNow,
            issuedEntry.targetKid(),
            "grpcs://192.0.2.3:9443");
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        owner
                            .readiness()
                            .recordPodAcceptance(
                                BINDING,
                                owner.trust(),
                                verified,
                                expectedPods.get(1),
                                changedClosedReceipt,
                                refreshedInventory)))
        .isInstanceOf(AccountJwtReadinessProbeRepository.IdempotencyConflictException.class);

    AccountJwtReadinessProbeRepository restartedRepository =
        new AccountJwtReadinessProbeRepository(
            context.dsl(), owner.desired(), owner.publication(), owner.inventory());
    ReadinessProbePlan restartedPlan =
        inTransaction(
            context,
            () ->
                restartedRepository.readCurrentPlan(
                    BINDING, owner.trust(), owner.result().operationId()));
    ProbeEntry restartedEntry =
        restartedPlan.entries().stream()
            .filter(entry -> entry.jti().equals(verified.jti()))
            .findFirst()
            .orElseThrow();
    List<ProbeEntry> expectedRestartedEntries =
        plan.entries().stream()
            .map(entry -> entry.jti().equals(verified.jti()) ? verified : entry)
            .toList();
    assertThat(restartedPlan.operationId()).isEqualTo(plan.operationId());
    assertThat(restartedPlan.planDigest()).isEqualTo(plan.planDigest());
    assertThat(restartedPlan.entries()).containsExactlyElementsOf(expectedRestartedEntries);
    assertThat(restartedEntry).isEqualTo(verified);
    assertThat(restartedEntry.verificationReceipt())
        .contains(verified.verificationReceipt().orElseThrow());
    assertThat(restartedPlan.expectedFence().durableActive()).isEmpty();
    assertThat(restartedPlan.expectedFence().publishedActive()).isEmpty();
    assertThat(count(context, "account_jwt_readiness_pod_receipts")).isEqualTo(2L);

    long remainingExpectedPodReceipts =
        completeRemainingProbes(
            context, owner, plan, refreshedInventory, privateMount, publicMount, expectedIdentity);
    long expectedPodReceiptCount = expectedPods.size() + remainingExpectedPodReceipts;
    assertThat(expectedPodReceiptCount).isEqualTo(12L);
    var proof =
        inTransaction(
            context,
            () ->
                owner
                    .readiness()
                    .readCurrentPromotionPrerequisites(
                        BINDING, owner.trust(), owner.result().operationId(), refreshedInventory)
                    .orElseThrow());
    assertThat(proof.verifiedProbes()).hasSize(plan.entries().size());
    assertThat(sha256(proof.readinessEvidencePreimage()))
        .isEqualTo(proof.readinessEvidenceDigest());
    byte[] callerCopy = proof.readinessEvidencePreimage();
    callerCopy[0] ^= 1;
    assertThat(sha256(proof.readinessEvidencePreimage()))
        .isEqualTo(proof.readinessEvidenceDigest());

    byte[] exactPreimage = proof.readinessEvidencePreimage();
    assertThatThrownBy(
            () -> insertDirectPromotion(context, owner, proof, "0".repeat(64), exactPreimage))
        .satisfies(
            failure ->
                assertThat(postgresConstraint(failure))
                    .isEqualTo("account_jwt_signer_promotion_readiness_digest"));
    String changedProofText =
        new String(exactPreimage, StandardCharsets.UTF_8)
            .replace(
                proof.inventoryEvidenceReference(),
                proof.inventoryEvidenceReference() + ":changed");
    byte[] changedPreimage = changedProofText.getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                insertDirectPromotion(
                    context, owner, proof, sha256(changedPreimage), changedPreimage))
        .satisfies(
            failure ->
                assertThat(postgresConstraint(failure))
                    .isEqualTo("account_jwt_signer_promotion_readiness_binding"));

    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () -> {
                      insertDirectGenerationAbortReceipt(context, owner);
                      insertDirectPromotionInCurrentTransaction(
                          context,
                          owner,
                          proof,
                          UUID.randomUUID(),
                          proof.readinessEvidenceDigest(),
                          exactPreimage);
                      return null;
                    }))
        .satisfies(
            failure ->
                assertThat(postgresConstraint(failure))
                    .isEqualTo("account_jwt_signer_promotion_insert_binding"));
    assertThat(count(context, "account_jwt_signer_generation_abort_receipts")).isZero();

    var publicationEvidence = proof.publication();
    PromotionPreparation preparation =
        new PromotionPreparation(
            proof.generationResult().operationId(),
            proof.generationResult(),
            new EnrollmentIdentity(
                CLUSTER_UID,
                NAMESPACE_UID,
                TRUST_DIGEST,
                TRUST_REVISION,
                API_DIGEST,
                API_REVISION,
                CONFIG_MAP_UID,
                INITIAL_CONFIG_MAP_RESOURCE_VERSION,
                AccountJwtJwksPublicationRepository.snapshotDigest(INITIAL_CONFIG_MAP_DATA)),
            API_DIGEST,
            API_REVISION,
            CONFIG_MAP_UID,
            publicationEvidence.receipt().observedResourceVersion(),
            publicationEvidence.intent().intentDigest(),
            publicationEvidence.receipt().receiptDigest(),
            publicationEvidence.mountedCorrespondence().observationDigest(),
            proof.plan().planDigest(),
            proof.readinessEvidenceDigest(),
            publicationEvidence.intent().jwksJson());
    var prepared =
        inTransaction(
            context,
            () ->
                owner
                    .desired()
                    .prepareCurrentGeneration(BINDING, owner.trust(), preparation, proof));
    assertThat(prepared.status()).isEqualTo("PREPARED");
    assertThat(
            inTransaction(
                context,
                () ->
                    owner
                        .desired()
                        .prepareCurrentGeneration(BINDING, owner.trust(), preparation, proof)))
        .isEqualTo(prepared);
    assertThat(count(context, "account_jwt_signer_promotion_operations")).isEqualTo(1L);
    assertThat(count(context, "account_jwt_readiness_pod_receipts"))
        .isEqualTo(expectedPodReceiptCount);

    byte[] persistedPreimage =
        java.util.Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "SELECT readiness_evidence_preimage FROM account_jwt_signer_promotion_operations "
                            + "WHERE operation_id = ?",
                        prepared.operationId()),
                "Prepared promotion must retain its exact readiness preimage")
            .get("readiness_evidence_preimage", byte[].class);
    assertThat(persistedPreimage).isEqualTo(exactPreimage);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_jwt_signer_promotion_operations "
                                    + "SET readiness_evidence_digest = ? WHERE operation_id = ?",
                                "0".repeat(64),
                                prepared.operationId())))
        .satisfies(
            failure ->
                assertThat(postgresConstraint(failure))
                    .isEqualTo("account_jwt_signer_operation_immutable"));
    byte[] changedStoredPreimage = exactPreimage.clone();
    changedStoredPreimage[0] ^= 1;
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_jwt_signer_promotion_operations "
                                    + "SET readiness_evidence_preimage = ? WHERE operation_id = ?",
                                changedStoredPreimage,
                                prepared.operationId())))
        .satisfies(
            failure ->
                assertThat(postgresConstraint(failure))
                    .isEqualTo("account_jwt_signer_operation_immutable"));

    AccountJwtSignerDesiredStateRepository restartedDesired =
        new AccountJwtSignerDesiredStateRepository(context.dsl());
    AccountJwtJwksPublicationRepository restartedPublication =
        new AccountJwtJwksPublicationRepository(context.dsl(), restartedDesired);
    AccountJwtValidatorInventoryRepository restartedInventory =
        new AccountJwtValidatorInventoryRepository(context.dsl());
    AccountJwtReadinessProbeRepository restartedReadiness =
        new AccountJwtReadinessProbeRepository(
            context.dsl(), restartedDesired, restartedPublication, restartedInventory);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () -> restartedDesired.readCurrentGenerationRequest(BINDING, owner.trust())))
        .isInstanceOf(
            AccountJwtSignerDesiredStateRepository.PromotionPrerequisitesIncompleteException.class)
        .hasMessage("Existing PREPARED signer state requires unavailable lifecycle evidence");
    var restartedProof =
        inTransaction(
            context,
            () ->
                restartedReadiness
                    .readPromotionProof(
                        BINDING, owner.trust(), owner.result().operationId(), refreshedInventory)
                    .orElseThrow());
    assertThat(restartedProof.readinessEvidencePreimage()).isEqualTo(exactPreimage);
    assertThat(databaseClockNow(context)).isLessThan(plan.expiresAtEpochSecond());
    var dispatchedEvidence =
        inTransaction(
            context,
            () -> restartedDesired.readAndMarkPreparedPromotionDispatched(BINDING, owner.trust()));
    assertThat(dispatchedEvidence.promotion().privatePromotionDispatched()).isTrue();
    var dispatchedProof =
        inTransaction(
            context,
            () ->
                restartedReadiness
                    .readPromotionProof(BINDING, owner.trust(), owner.result().operationId())
                    .orElseThrow());
    assertThat(dispatchedProof.readinessEvidencePreimage()).isEqualTo(exactPreimage);
    assertThat(dispatchedProof.readinessEvidenceDigest())
        .isEqualTo(proof.readinessEvidenceDigest());
    OwnerState restartedOwner =
        new OwnerState(
            restartedDesired,
            restartedPublication,
            restartedInventory,
            restartedReadiness,
            owner.trust(),
            owner.request(),
            owner.result(),
            owner.keyPair(),
            owner.desiredJwks());
    assertThat(
            inTransaction(
                context,
                () ->
                    restartedOwner
                        .desired()
                        .prepareCurrentGeneration(
                            BINDING, restartedOwner.trust(), preparation, dispatchedProof)))
        .isEqualTo(prepared);

    // These synthetic owner readbacks stand for the two exact external CAS receipts.
    var dispatchedPromotion = dispatchedEvidence.promotion();
    PrivatePromotionObservation privatePromotionObservation =
        new PrivatePromotionObservation(
            dispatchedPromotion.operationId(),
            dispatchedPromotion.requestDigest(),
            dispatchedPromotion.generationOperationId(),
            dispatchedPromotion.generationOperationDigest(),
            owner.result().secretUid(),
            dispatchedPromotion.expectedPrivateResourceVersion(),
            "14",
            new PublicKeyIdentity(
                dispatchedPromotion.targetGeneration(),
                dispatchedPromotion.targetKid(),
                dispatchedPromotion.targetPublicKeyFingerprint()),
            Optional.empty(),
            List.of("current"));
    var privatePromotionReceipt =
        inTransaction(
            context,
            () ->
                restartedDesired.recordPrivatePromotionResult(
                    BINDING, owner.trust(), privatePromotionObservation));
    var activeJwksPromotionReceipt =
        inTransaction(
            context,
            () ->
                restartedDesired.recordActiveJwksPromotionResult(
                    BINDING,
                    owner.trust(),
                    new ActiveJwksPromotionObservation(
                        dispatchedPromotion.publicConfigMapUid(),
                        dispatchedPromotion.expectedPublicResourceVersion(),
                        "43",
                        dispatchedPromotion.expectedPublicJwksJson(),
                        dispatchedPromotion.expectedActiveMarkerJson())));
    var receiptBoundPrepared =
        inTransaction(
            context,
            () -> restartedDesired.readPreparedGenerationForRecovery(BINDING, owner.trust()));
    assertThat(receiptBoundPrepared.promotion().privatePromotionReceiptDigest())
        .contains(privatePromotionReceipt.receiptDigest());
    assertThat(receiptBoundPrepared.promotion().activeJwksReceiptDigest())
        .contains(activeJwksPromotionReceipt.receiptDigest());

    assertThat(databaseClockNow(context)).isLessThan(plan.expiresAtEpochSecond());
    var receiptBoundProof =
        inTransaction(
            context,
            () ->
                restartedReadiness
                    .readPromotionProof(BINDING, owner.trust(), owner.result().operationId())
                    .orElseThrow());
    assertThat(receiptBoundProof.plan().planDigest()).isEqualTo(plan.planDigest());
    assertThat(receiptBoundProof.plan().expiresAtEpochSecond())
        .isEqualTo(plan.expiresAtEpochSecond());
    assertThat(receiptBoundProof.readinessEvidencePreimage()).isEqualTo(exactPreimage);
    assertThat(receiptBoundProof.readinessEvidenceDigest())
        .isEqualTo(proof.readinessEvidenceDigest());
    assertThat(receiptBoundProof.verifiedProbes()).hasSize(plan.entries().size());
    assertThat(
            inTransaction(
                context,
                () ->
                    restartedOwner
                        .desired()
                        .prepareCurrentGeneration(
                            BINDING, restartedOwner.trust(), preparation, receiptBoundProof)))
        .isEqualTo(prepared);

    var committed =
        inTransaction(
            context,
            () ->
                restartedDesired.commitPreparedPromotion(
                    BINDING, owner.trust(), prepared.operationId(), receiptBoundProof));
    assertThat(committed.promotion().status()).isEqualTo("COMMITTED");
    assertThat(committed.promotion().privatePromotionReceiptDigest())
        .contains(privatePromotionReceipt.receiptDigest());
    assertThat(committed.promotion().activeJwksReceiptDigest())
        .contains(activeJwksPromotionReceipt.receiptDigest());
    assertThat(committed.privateReceipt()).isEqualTo(privatePromotionReceipt);
    assertThat(committed.publicReceipt()).isEqualTo(activeJwksPromotionReceipt);

    assertReadinessEvidenceTruncateDeniedWithoutLoss(
        context,
        List.of(
            "account_jwt_readiness_probe_plans",
            "account_jwt_readiness_probe_entries",
            "account_jwt_readiness_validator_pods",
            "account_jwt_readiness_pod_receipts",
            "account_jwt_readiness_delivery_claims"));
  }

  private static long completeRemainingProbes(
      TestContext context,
      OwnerState owner,
      ReadinessProbePlan plan,
      InventorySnapshot inventory,
      Path privateMount,
      Path publicMount,
      AccountMountedJwtSignerBundle.ExpectedIdentity expectedIdentity)
      throws Exception {
    long expectedPodReceiptCount = 0;
    for (ProbeEntry planned : plan.entries()) {
      ProbeEntry current =
          inTransaction(
                  context,
                  () ->
                      owner
                          .readiness()
                          .readCurrentPlan(BINDING, owner.trust(), owner.result().operationId()))
              .entries()
              .stream()
              .filter(entry -> entry.jti().equals(planned.jti()))
              .findFirst()
              .orElseThrow();
      if (current.state() == ProbeState.VERIFIED) {
        continue;
      }
      var signingSpec =
          new AccountMountedJwtSignerBundle.ReadinessProbeSigningSpec(
              current.validatorId(),
              current.probeKind(),
              current.tokenProfile(),
              current.audience(),
              current.jti(),
              current.targetGeneration(),
              current.targetKid(),
              current.plannedIssuedAtEpochSecond(),
              current.expiresAtEpochSecond());
      Instant plannedAt = Instant.ofEpochSecond(current.plannedIssuedAtEpochSecond());
      AccountMountedJwtSignerBundle.signReadinessProbeDigest(
          privateMount,
          Path.of("pending.key"),
          publicMount,
          Path.of("jwks.json"),
          expectedIdentity,
          signingSpec,
          (digest, compactJwt) -> {
            inTransaction(
                context,
                () ->
                    owner
                        .readiness()
                        .recordSigningAttempt(BINDING, owner.trust(), digest, plannedAt));
            inTransaction(
                context,
                () -> owner.readiness().recordIssued(BINDING, owner.trust(), digest, plannedAt));
          });
      ProbeEntry issued =
          inTransaction(
                  context,
                  () ->
                      owner
                          .readiness()
                          .readCurrentPlan(BINDING, owner.trust(), owner.result().operationId()))
              .entries()
              .stream()
              .filter(entry -> entry.jti().equals(planned.jti()))
              .findFirst()
              .orElseThrow();
      ProbeEntry issuedForInventory = issued;
      List<ExpectedPod> pods =
          inTransaction(
              context,
              () ->
                  owner
                      .readiness()
                      .readCurrentExpectedPods(
                          BINDING, owner.trust(), issuedForInventory, inventory));
      expectedPodReceiptCount += pods.size();
      for (ExpectedPod pod : pods) {
        ProbeEntry currentEvidence = issued;
        var acceptance = testAcceptance(currentEvidence, pod, databaseNow(context));
        issued =
            inTransaction(
                context,
                () ->
                    owner
                        .readiness()
                        .recordPodAcceptance(
                            BINDING, owner.trust(), currentEvidence, pod, acceptance, inventory));
      }
      assertThat(issued.state()).isEqualTo(ProbeState.VERIFIED);
    }
    return expectedPodReceiptCount;
  }

  private static void insertDirectPromotion(
      TestContext context,
      OwnerState owner,
      AccountJwtReadinessProbeRepository.ReadinessPromotionProof proof,
      String readinessDigest,
      byte[] readinessPreimage) {
    inTransaction(
        context,
        () -> {
          insertDirectPromotionInCurrentTransaction(
              context, owner, proof, UUID.randomUUID(), readinessDigest, readinessPreimage);
          return null;
        });
  }

  private static void insertDirectPromotionInCurrentTransaction(
      TestContext context,
      OwnerState owner,
      AccountJwtReadinessProbeRepository.ReadinessPromotionProof proof,
      UUID promotionOperationId,
      String readinessDigest,
      byte[] readinessPreimage) {
    var publication = proof.publication();
    String activeGenerationMarker = generationMarkerUnchecked(owner.result());
    int inserted =
        context
            .dsl()
            .execute(
                "INSERT INTO account_jwt_signer_promotion_operations "
                    + "(operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                    + "request_digest_version, request_digest, expected_record_version, "
                    + "expected_previous_generation, expected_previous_kid, target_generation, target_kid, "
                    + "target_algorithm, target_public_key_fingerprint, "
                    + "expected_private_secret_resource_version, expected_public_jwks_resource_version, "
                    + "expected_public_active_generation, expected_public_active_kid, operation_action, "
                    + "allowed_private_slots_canonical_bytes, status, generation_operation_id, "
                    + "expected_cluster_incarnation_uid, expected_namespace_uid, "
                    + "materializer_trust_binding_digest, materializer_trust_config_revision, "
                    + "api_binding_digest, api_config_revision, expected_private_secret_uid, "
                    + "expected_public_config_map_uid, prepublication_intent_digest, "
                    + "prepublication_receipt_digest, mounted_observation_digest, readiness_plan_digest, "
                    + "readiness_evidence_digest, readiness_evidence_preimage, expected_public_jwks_json, "
                    + "expected_active_generation_marker_json) "
                    + "VALUES (?, ?, ?, ?, ?, 1, ?, ?, NULL, NULL, ?, ?, 'RS256', ?, ?, ?, "
                    + "NULL, NULL, 'PROMOTE_PENDING', ?, 'PREPARED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                promotionOperationId,
                BINDING.environmentId(),
                BINDING.clusterId(),
                BINDING.namespace(),
                BINDING.mode().value(),
                directPromotionRequestDigest(owner, proof, promotionOperationId, readinessDigest),
                owner.result().desiredStateVersion(),
                Long.parseLong(owner.result().targetGeneration()),
                owner.result().targetKid(),
                owner.result().publicKeyFingerprint(),
                owner.result().observedResourceVersion(),
                publication.receipt().observedResourceVersion(),
                "[\"current\",\"pending\",\"previous\"]".getBytes(StandardCharsets.UTF_8),
                owner.result().operationId(),
                UUID.fromString(owner.trust().expectedClusterIncarnationUid()),
                UUID.fromString(owner.trust().expectedNamespaceUid()),
                owner.trust().bindingDigest(),
                owner.trust().configRevision(),
                API_DIGEST,
                API_REVISION,
                UUID.fromString(owner.result().secretUid()),
                UUID.fromString(CONFIG_MAP_UID),
                publication.intent().intentDigest(),
                publication.receipt().receiptDigest(),
                publication.mountedCorrespondence().observationDigest(),
                proof.plan().planDigest(),
                readinessDigest,
                readinessPreimage,
                publication.intent().jwksJson(),
                activeGenerationMarker);
    assertThat(inserted).isEqualTo(1);
  }

  private static void insertDirectGenerationAbortReceipt(TestContext context, OwnerState owner) {
    int inserted =
        context
            .dsl()
            .execute(
                "INSERT INTO account_jwt_signer_generation_abort_receipts "
                    + "(operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode, "
                    + "operation_digest, generation_request_digest, generation_receipt_digest, "
                    + "expected_state_version, resulting_state_version, expected_cluster_incarnation_uid, "
                    + "expected_namespace_uid, trust_binding_digest, trust_config_revision, "
                    + "durable_active_generation, durable_active_kid, published_active_generation, "
                    + "published_active_kid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL)",
                owner.result().operationId(),
                BINDING.environmentId(),
                BINDING.clusterId(),
                BINDING.namespace(),
                BINDING.mode().value(),
                owner.result().operationDigest(),
                owner.result().generationRequestDigest(),
                owner.result().receiptDigest(),
                owner.result().desiredStateVersion(),
                owner.result().desiredStateVersion() + 1L,
                UUID.fromString(owner.trust().expectedClusterIncarnationUid()),
                UUID.fromString(owner.trust().expectedNamespaceUid()),
                owner.trust().bindingDigest(),
                owner.trust().configRevision());
    assertThat(inserted).isEqualTo(1);
  }

  private static String directPromotionRequestDigest(
      OwnerState owner,
      AccountJwtReadinessProbeRepository.ReadinessPromotionProof proof,
      UUID promotionOperationId,
      String readinessDigest) {
    var publication = proof.publication();
    var result = owner.result();
    Map<String, Object> preimage = new LinkedHashMap<>();
    preimage.put("digestVersion", "account-jwt-signer-promotion-operation/v1");
    preimage.put("operationId", promotionOperationId.toString());
    preimage.put("generationOperationId", result.operationId().toString());
    preimage.put("generationOperationDigest", result.operationDigest());
    preimage.put("generationReceiptDigest", result.receiptDigest());
    preimage.put("environmentId", BINDING.environmentId());
    preimage.put("clusterId", BINDING.clusterId());
    preimage.put("namespace", BINDING.namespace());
    preimage.put("custodyMode", BINDING.mode().value());
    preimage.put("expectedRecordVersion", Long.toString(result.desiredStateVersion()));
    preimage.put("expectedPreviousActive", Map.of("present", false));
    preimage.put("expectedPublishedActive", Map.of("present", false));
    preimage.put("targetGeneration", result.targetGeneration());
    preimage.put("targetKid", result.targetKid());
    preimage.put("targetAlgorithm", "RS256");
    preimage.put("targetPublicKeyFingerprint", result.publicKeyFingerprint());
    preimage.put("expectedPrivateSecretUid", result.secretUid());
    preimage.put("expectedPrivateSecretResourceVersion", result.observedResourceVersion());
    preimage.put("expectedPublicConfigMapUid", CONFIG_MAP_UID);
    preimage.put(
        "expectedPublicJwksResourceVersion", publication.receipt().observedResourceVersion());
    preimage.put("expectedClusterIncarnationUid", owner.trust().expectedClusterIncarnationUid());
    preimage.put("expectedNamespaceUid", owner.trust().expectedNamespaceUid());
    preimage.put("materializerTrustBindingDigest", owner.trust().bindingDigest());
    preimage.put("materializerTrustConfigRevision", owner.trust().configRevision());
    preimage.put("apiBindingDigest", API_DIGEST);
    preimage.put("apiConfigRevision", API_REVISION);
    preimage.put("prepublicationIntentDigest", publication.intent().intentDigest());
    preimage.put("prepublicationReceiptDigest", publication.receipt().receiptDigest());
    preimage.put(
        "mountedObservationDigest", publication.mountedCorrespondence().observationDigest());
    preimage.put("readinessPlanDigest", proof.plan().planDigest());
    preimage.put("readinessEvidenceDigest", readinessDigest);
    preimage.put(
        "publicJwksSha256",
        sha256(publication.intent().jwksJson().getBytes(StandardCharsets.UTF_8)));
    preimage.put("operationAction", "PROMOTE_PENDING");
    preimage.put("allowedPrivateSlots", List.of("current", "pending", "previous"));
    try {
      return sha256(Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(preimage)));
    } catch (Exception failure) {
      throw new AssertionError(
          "Direct promotion digest fixture could not be canonicalized", failure);
    }
  }

  private static String postgresConstraint(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof PSQLException postgresFailure) {
        var serverError = postgresFailure.getServerErrorMessage();
        if (serverError != null) {
          return serverError.getConstraint();
        }
      }
      current = current.getCause();
    }
    return null;
  }

  private static String generationMarkerUnchecked(GenerationResult result) {
    try {
      return generationMarker(result);
    } catch (Exception failure) {
      throw new AssertionError("Generation marker fixture could not be serialized", failure);
    }
  }

  private OwnerState createOwnerState(TestContext context) throws Exception {
    AccountJwtSignerDesiredStateRepository desired =
        new AccountJwtSignerDesiredStateRepository(context.dsl());
    AccountJwtJwksPublicationRepository publication =
        new AccountJwtJwksPublicationRepository(context.dsl(), desired);
    AccountJwtValidatorInventoryRepository inventory =
        new AccountJwtValidatorInventoryRepository(context.dsl());
    AccountJwtReadinessProbeRepository readiness =
        new AccountJwtReadinessProbeRepository(context.dsl(), desired, publication, inventory);
    TrustFence trust = new TrustFence(CLUSTER_UID, NAMESPACE_UID, TRUST_DIGEST, TRUST_REVISION);
    EnrollmentIdentity enrollment =
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
    inTransaction(context, () -> desired.initialize(BINDING, enrollment));
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
    String desiredJwks = jwks(retainedJwk, targetJwk.json());
    String marker = generationMarker(result);
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
    var publicationReceipt =
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
    assertThat(intent.jwksJson()).isEqualTo(desiredJwks);
    assertThat(intent.jwksJson()).contains(targetJwk.json());
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
    assertThat(publicationReceipt.intentDigest()).isEqualTo(intent.intentDigest());
    return new OwnerState(
        desired, publication, inventory, readiness, trust, requested, result, keyPair, desiredJwks);
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

  private static <T> T inTransaction(TestContext context, Supplier<T> operation) {
    return context.transaction().execute(status -> operation.get());
  }

  private static long count(TestContext context, String table) {
    return java.util.Objects.requireNonNull(
        context.dsl().resultQuery("SELECT count(*) FROM " + table).fetchOne(0, Long.class),
        "Count query returned no row.");
  }

  private static String readinessMatrixWithMissingOrNullField(
      String validMatrixJson, String field, boolean explicitNull) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> matrix = (Map<String, Object>) JSON.readValue(validMatrixJson, Map.class);
      Map<String, Object> target = matrix;
      if (field.equals("validatorId") || field.equals("applicableProfiles")) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> validators = (List<Map<String, Object>>) matrix.get("validators");
        target =
            validators.stream()
                .filter(validator -> "account-service".equals(validator.get("validatorId")))
                .findFirst()
                .orElseThrow();
      }
      if (explicitNull) {
        target.put(field, null);
      } else {
        target.remove(field);
      }
      return JSON.writeValueAsString(matrix);
    } catch (Exception failure) {
      throw new AssertionError("Readiness inventory SQL fixture could not be mutated", failure);
    }
  }

  private static String readinessMatrixWithExtraValidator(String validMatrixJson) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> matrix = (Map<String, Object>) JSON.readValue(validMatrixJson, Map.class);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> parsedValidators =
          (List<Map<String, Object>>) matrix.get("validators");
      List<Map<String, Object>> validators = new java.util.ArrayList<>(parsedValidators);
      Map<String, Object> extraValidator = new LinkedHashMap<>(validators.getFirst());
      extraValidator.put("validatorId", "unexpected-validator");
      validators.add(extraValidator);
      matrix.put("validators", validators);
      matrix.put("inventoryStatus", "PARTIAL_UNCONFIRMED");
      return JSON.writeValueAsString(matrix);
    } catch (Exception failure) {
      throw new AssertionError("Readiness SQL validator-set fixture could not be mutated", failure);
    }
  }

  private static String readinessMatrixWithDifferentApplicableProfiles(String validMatrixJson) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> matrix = (Map<String, Object>) JSON.readValue(validMatrixJson, Map.class);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> validators = (List<Map<String, Object>>) matrix.get("validators");
      Map<String, Object> accountValidator =
          validators.stream()
              .filter(validator -> "account-service".equals(validator.get("validatorId")))
              .findFirst()
              .orElseThrow();
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> parsedProfiles =
          (List<Map<String, Object>>) accountValidator.get("applicableProfiles");
      List<Map<String, Object>> profiles = new java.util.ArrayList<>(parsedProfiles);
      profiles.removeFirst();
      accountValidator.put("applicableProfiles", profiles);
      matrix.put("inventoryStatus", "PARTIAL_UNCONFIRMED");
      return JSON.writeValueAsString(matrix);
    } catch (Exception failure) {
      throw new AssertionError("Readiness SQL profile fixture could not be mutated", failure);
    }
  }

  private static void assertReadinessInventoryClosureGuardRejectsMatrixMismatch(
      TestContext context,
      ReadinessProbePlan plan,
      String mismatchedMatrixJson,
      String mismatchDescription) {
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () -> {
                      insertRawReadinessPlanClosure(context, plan, mismatchedMatrixJson, false);
                      assertThat(count(context, "account_jwt_readiness_probe_entries"))
                          .isEqualTo(plan.entries().size());
                      assertThat(count(context, "account_jwt_readiness_validator_pods"))
                          .isEqualTo(12L);
                      return null;
                    }))
        .as("deferred V2 closure must reject %s", mismatchDescription)
        .satisfies(failure -> assertThat(postgresSqlState(failure)).isEqualTo("23514"));
    assertThat(count(context, "account_jwt_readiness_probe_plans")).isZero();
    assertThat(count(context, "account_jwt_readiness_probe_entries")).isZero();
    assertThat(count(context, "account_jwt_readiness_validator_pods")).isZero();
  }

  private static void insertRawReadinessPlanClosure(
      TestContext context,
      ReadinessProbePlan plan,
      String applicabilityMatrixJson,
      boolean inventoryComplete) {
    insertRawReadinessPlan(context, plan, applicabilityMatrixJson, inventoryComplete);
    for (ProbeEntry entry : plan.entries()) {
      context
          .dsl()
          .execute(
              """
              INSERT INTO account_jwt_readiness_probe_entries (
                  rotation_operation_id, plan_digest, validator_id, token_profile, audience,
                  probe_kind, jti, target_generation, target_kid, expected_active_generation,
                  expected_active_kid, registry_version, entry_version,
                  planned_issued_at_epoch_seconds, expires_at_epoch_seconds, state, entry_plan_version
              ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              """,
              entry.rotationOperationId(),
              entry.planDigest(),
              entry.validatorId(),
              entry.tokenProfile(),
              entry.audience(),
              entry.probeKind().name(),
              entry.jti(),
              Long.parseLong(entry.targetGeneration()),
              entry.targetKid(),
              entry.expectedActive().map(value -> Long.parseLong(value.generation())).orElse(null),
              entry.expectedActive().map(value -> value.kid()).orElse(null),
              entry.registryVersion(),
              entry.entryVersion(),
              entry.plannedIssuedAtEpochSecond(),
              entry.expiresAtEpochSecond(),
              entry.state().name(),
              plan.planVersion());
    }
    context
        .dsl()
        .execute(
            """
            INSERT INTO account_jwt_readiness_validator_pods (
                rotation_operation_id, plan_digest, validator_id, token_profile, audience,
                probe_kind, jti, inventory_snapshot_digest, environment_id, cluster_id,
                cluster_incarnation_uid, kubernetes_namespace, namespace_uid,
                api_binding_revision, api_binding_digest, inventory_binding_revision,
                inventory_binding_digest, deployment_uid, pod_uid, pod_ip, image,
                verifier_config_sha256, applicability_matrix_digest, expected_outcome,
                exact_pod_endpoint, canonical_service_uri, expected_pod_leaf_spki_sha256
            )
            SELECT entry.rotation_operation_id,
                   entry.plan_digest,
                   entry.validator_id,
                   entry.token_profile,
                   entry.audience,
                   entry.probe_kind,
                   entry.jti,
                   snapshot.snapshot_digest,
                   snapshot.environment_id,
                   snapshot.cluster_id,
                   snapshot.cluster_incarnation_uid,
                   snapshot.kubernetes_namespace,
                   snapshot.namespace_uid,
                   snapshot.api_binding_revision,
                   snapshot.api_binding_digest,
                   snapshot.inventory_binding_revision,
                   snapshot.inventory_binding_digest,
                   (validator.value ->> 'deploymentUid')::uuid,
                   (pod.value ->> 'uid')::uuid,
                   pod.value ->> 'podIp',
                   pod.value ->> 'image',
                   pod.value ->> 'verifierConfigSha256',
                   plan.applicability_matrix_digest,
                   CASE
                       WHEN entry.probe_kind = 'CANARY'
                         OR EXISTS (
                              SELECT 1
                                FROM jsonb_array_elements(validator.value -> 'profiles') AS profile(value)
                               WHERE profile.value ->> 'tokenProfile' = entry.token_profile
                                 AND profile.value ->> 'audience' = entry.audience)
                           THEN 'ACCEPT'
                       ELSE 'INAPPLICABLE_REJECT'
                   END,
                   pod.value ->> 'exactPodEndpoint',
                   pod.value ->> 'canonicalServiceUri',
                   pod.value ->> 'leafSpkiSha256'
              FROM account_jwt_readiness_probe_entries AS entry
              JOIN account_jwt_readiness_probe_plans AS plan
                ON plan.rotation_operation_id = entry.rotation_operation_id
               AND plan.plan_digest = entry.plan_digest
              JOIN account_jwt_validator_inventory_snapshots AS snapshot
                ON snapshot.snapshot_digest = plan.inventory_snapshot_digest
              CROSS JOIN LATERAL jsonb_array_elements(
                  convert_from(snapshot.canonical_snapshot, 'UTF8')::jsonb -> 'validators') AS validator(value)
              CROSS JOIN LATERAL jsonb_array_elements(validator.value -> 'pods') AS pod(value)
             WHERE entry.rotation_operation_id = ?
               AND entry.plan_digest = ?
               AND validator.value ->> 'validatorId' = entry.validator_id
            """,
            plan.operationId(),
            plan.planDigest());
  }

  private static void assertReadinessPlanInventoryGuardRejects(
      TestContext context, ReadinessProbePlan plan, List<String> requiredFields) {
    for (String field : requiredFields) {
      for (boolean explicitNull : new boolean[] {false, true}) {
        String malformedMatrix =
            readinessMatrixWithMissingOrNullField(
                plan.applicabilityMatrixJson(), field, explicitNull);
        String fieldState = explicitNull ? "null " : "missing ";
        assertThatThrownBy(
                () ->
                    inTransaction(
                        context,
                        () -> {
                          insertRawReadinessPlan(context, plan, malformedMatrix);
                          return null;
                        }))
            .as("raw SQL must reject V%s %s%s", plan.planVersion(), fieldState, field)
            .satisfies(failure -> assertThat(postgresSqlState(failure)).isEqualTo("23514"));
        assertThat(count(context, "account_jwt_readiness_probe_plans")).isZero();
      }
    }

    context
        .transaction()
        .execute(
            status -> {
              insertRawReadinessPlan(context, plan, plan.applicabilityMatrixJson());
              assertThat(count(context, "account_jwt_readiness_probe_plans")).isEqualTo(1L);
              status.setRollbackOnly();
              return null;
            });
    assertThat(count(context, "account_jwt_readiness_probe_plans")).isZero();
  }

  private static void insertRawReadinessPlan(
      TestContext context, ReadinessProbePlan plan, String applicabilityMatrixJson) {
    insertRawReadinessPlan(
        context, plan, applicabilityMatrixJson, plan.validatorInventoryComplete());
  }

  private static void insertRawReadinessPlan(
      TestContext context,
      ReadinessProbePlan plan,
      String applicabilityMatrixJson,
      boolean validatorInventoryComplete) {
    new JdbcTemplate(context.dataSource())
        .update(
            """
            INSERT INTO account_jwt_readiness_probe_plans (
                rotation_operation_id, environment_id, cluster_id, kubernetes_namespace, custody_mode,
                operation_digest, generation_request_digest, generation_receipt_digest,
                desired_state_version, expected_cluster_incarnation_uid, expected_namespace_uid,
                trust_binding_digest, trust_config_revision, target_generation, target_kid,
                target_public_key_fingerprint, expected_active_generation, expected_active_kid,
                expected_published_generation, expected_published_kid, publication_intent_digest,
                publication_receipt_digest, mounted_observation_digest, applicability_matrix_json,
                applicability_matrix_digest, validator_inventory_complete, maximum_cache_age_seconds,
                planned_at_epoch_seconds, not_before_epoch_seconds, expires_at_epoch_seconds,
                plan_version, plan_digest, inventory_snapshot_digest
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            """,
            plan.operationId(),
            plan.binding().environmentId(),
            plan.binding().clusterId(),
            plan.binding().namespace(),
            plan.binding().mode().value(),
            plan.operationDigest(),
            plan.generationRequestDigest(),
            plan.generationReceiptDigest(),
            plan.desiredStateVersion(),
            UUID.fromString(plan.trustFence().expectedClusterIncarnationUid()),
            UUID.fromString(plan.trustFence().expectedNamespaceUid()),
            plan.trustFence().bindingDigest(),
            plan.trustFence().configRevision(),
            Long.parseLong(plan.targetGeneration()),
            plan.targetKid(),
            plan.targetPublicKeyFingerprint(),
            plan.expectedFence()
                .durableActive()
                .map(value -> Long.parseLong(value.generation()))
                .orElse(null),
            plan.expectedFence().durableActive().map(value -> value.kid()).orElse(null),
            plan.expectedFence()
                .publishedActive()
                .map(value -> Long.parseLong(value.generation()))
                .orElse(null),
            plan.expectedFence().publishedActive().map(value -> value.kid()).orElse(null),
            plan.publicationIntentDigest(),
            plan.publicationReceiptDigest(),
            plan.mountedObservationDigest(),
            applicabilityMatrixJson,
            plan.applicabilityMatrixDigest(),
            validatorInventoryComplete,
            plan.maximumCacheAgeSeconds(),
            plan.plannedAtEpochSecond(),
            plan.notBeforeEpochSecond(),
            plan.expiresAtEpochSecond(),
            plan.planVersion(),
            plan.planDigest(),
            plan.inventorySnapshotDigest().orElse(null));
  }

  private static void assertReadinessEvidenceTruncateDeniedWithoutLoss(
      TestContext context, List<String> tables) {
    Map<String, Long> retainedCounts = new LinkedHashMap<>();
    for (String table : tables) {
      retainedCounts.put(table, count(context, table));
    }
    assertThat(retainedCounts.values()).allSatisfy(count -> assertThat(count).isPositive());

    for (String table : tables) {
      for (String cascade : List.of("", " CASCADE")) {
        assertThatThrownBy(
                () ->
                    inTransaction(
                        context,
                        () -> {
                          context.dsl().execute("TRUNCATE TABLE " + table + cascade);
                          return null;
                        }))
            .as("raw TRUNCATE must retain readiness evidence in %s%s", table, cascade)
            .satisfies(
                failure -> {
                  String sqlState = postgresSqlState(failure);
                  List<String> expectedSqlStates =
                      cascade.isEmpty() ? List.of("23514", "0A000") : List.of("23514");
                  assertThat(sqlState).isIn(expectedSqlStates);
                  if ("23514".equals(sqlState)) {
                    assertThat(postgresServerMessage(failure))
                        .contains("Account JWT readiness evidence");
                  }
                });
        retainedCounts.forEach(
            (retainedTable, expectedCount) ->
                assertThat(count(context, retainedTable)).isEqualTo(expectedCount));
      }
    }
  }

  private static void insertRawPodReceipt(
      TestContext context,
      ProbeEntry entry,
      ExpectedPod expectedPod,
      AuthenticatedAcceptance acceptance) {
    Map<String, Object> digestFields = new LinkedHashMap<>();
    digestFields.put("domain", "account-jwt-readiness-per-pod-receipt/v1");
    digestFields.put("rotationOperationId", entry.rotationOperationId().toString());
    digestFields.put("planDigest", entry.planDigest());
    digestFields.put("validatorId", entry.validatorId());
    digestFields.put("tokenProfile", entry.tokenProfile());
    digestFields.put("audience", entry.audience());
    digestFields.put("probeKind", entry.probeKind().name());
    digestFields.put("podUid", expectedPod.target().podUid());
    digestFields.put("jti", entry.jti().toString());
    digestFields.put("sourceEntryVersion", entry.entryVersion());
    digestFields.put("compactTokenSha256", entry.compactTokenSha256().orElseThrow());
    digestFields.put("targetGeneration", entry.targetGeneration());
    digestFields.put("targetKid", entry.targetKid());
    Map<String, Object> expectedActive = new LinkedHashMap<>();
    expectedActive.put("present", entry.expectedActive().isPresent());
    entry
        .expectedActive()
        .ifPresent(
            active -> {
              expectedActive.put("generation", active.generation());
              expectedActive.put("kid", active.kid());
            });
    digestFields.put("expectedActive", expectedActive);
    digestFields.put("outcome", acceptance.result().name());
    digestFields.put("observedPodUid", acceptance.podUid());
    digestFields.put("image", acceptance.image());
    digestFields.put("verifierConfigSha256", acceptance.verifierConfigSha256());
    digestFields.put("verifiedKid", acceptance.verifiedKid());
    digestFields.put("receiverEndpoint", URI.create(acceptance.actualPodEndpoint()).toString());
    digestFields.put("receiverServiceUri", acceptance.actualServiceUri());
    digestFields.put("receiverPeerSpkiSha256", acceptance.actualPeerSpkiSha256());
    digestFields.put("observedAtEpochSecond", acceptance.observedAtEpochSecond());
    String receiptDigest;
    try {
      receiptDigest =
          sha256(Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(digestFields)));
    } catch (Exception failure) {
      throw new AssertionError("Raw per-Pod receipt fixture could not be canonicalized", failure);
    }
    var target = expectedPod.target();
    context
        .dsl()
        .execute(
            "INSERT INTO account_jwt_readiness_pod_receipts ("
                + "rotation_operation_id, plan_digest, validator_id, token_profile, audience, "
                + "probe_kind, pod_uid, receipt_version, jti, source_entry_version, "
                + "compact_token_sha256, target_generation, target_kid, expected_active_generation, "
                + "expected_active_kid, outcome, observed_pod_uid, observed_image, "
                + "observed_verifier_config_sha256, verified_kid, receiver_endpoint, "
                + "receiver_service_uri, receiver_peer_spki_sha256, observed_at_epoch_seconds, receipt_sha256) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            entry.rotationOperationId(),
            entry.planDigest(),
            entry.validatorId(),
            entry.tokenProfile(),
            entry.audience(),
            entry.probeKind().name(),
            UUID.fromString(target.podUid()),
            entry.jti(),
            entry.entryVersion(),
            entry.compactTokenSha256().orElseThrow(),
            Long.parseLong(entry.targetGeneration()),
            entry.targetKid(),
            entry.expectedActive().map(value -> Long.parseLong(value.generation())).orElse(null),
            entry.expectedActive().map(value -> value.kid()).orElse(null),
            acceptance.result().name(),
            UUID.fromString(acceptance.podUid()),
            acceptance.image(),
            acceptance.verifierConfigSha256(),
            acceptance.verifiedKid(),
            acceptance.actualPodEndpoint(),
            acceptance.actualServiceUri(),
            acceptance.actualPeerSpkiSha256(),
            acceptance.observedAtEpochSecond(),
            receiptDigest);
  }

  private static void assertReadinessVerifiedTransitionRejectsChangedSigningAttempt(
      TestContext context,
      ProbeEntry issuedEntry,
      int expectedPodCount,
      long verifiedAtEpochSecond) {
    String alteredReceiptDigest = "f".repeat(64);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () -> {
                      context
                          .dsl()
                          .execute(
                              "UPDATE account_jwt_readiness_probe_entries SET "
                                  + "state = 'VERIFIED', entry_version = entry_version + 1, "
                                  + "signing_attempted_at_epoch_seconds = "
                                  + "signing_attempted_at_epoch_seconds + 1, "
                                  + "verification_receipt_version = 2, "
                                  + "verification_source_entry_version = entry_version, "
                                  + "verified_at_epoch_seconds = ?, verified_kid = target_kid, "
                                  + "validator_instance_id = 'raw-sql-validator-1', "
                                  + "validator_binding_digest = ?, "
                                  + "validator_config_revision = 'config-r1', "
                                  + "validator_peer_uri = ?, validator_peer_spki_sha256 = ?, "
                                  + "verification_receipt_sha256 = ?, pod_receipt_count = ?, "
                                  + "pod_receipt_closure_sha256 = ? "
                                  + "WHERE rotation_operation_id = ? AND validator_id = ? "
                                  + "AND token_profile = ? AND audience = ? AND probe_kind = ?",
                              verifiedAtEpochSecond,
                              "d".repeat(64),
                              "spiffe://firemud/ns/firemud-prod/sa/account-jwt-validator",
                              "e".repeat(64),
                              alteredReceiptDigest,
                              expectedPodCount,
                              alteredReceiptDigest,
                              issuedEntry.rotationOperationId(),
                              issuedEntry.validatorId(),
                              issuedEntry.tokenProfile(),
                              issuedEntry.audience(),
                              issuedEntry.probeKind().name());
                      return null;
                    }))
        .satisfies(failure -> assertThat(postgresSqlState(failure)).isEqualTo("23514"));

    var retainedEntry =
        context
            .dsl()
            .fetchOne(
                "SELECT state, signing_attempted_at_epoch_seconds "
                    + "FROM account_jwt_readiness_probe_entries "
                    + "WHERE rotation_operation_id = ? AND validator_id = ? AND token_profile = ? "
                    + "AND audience = ? AND probe_kind = ?",
                issuedEntry.rotationOperationId(),
                issuedEntry.validatorId(),
                issuedEntry.tokenProfile(),
                issuedEntry.audience(),
                issuedEntry.probeKind().name());
    assertThat(retainedEntry).isNotNull();
    assertThat(retainedEntry.get("state", String.class)).isEqualTo("ISSUED");
    assertThat(retainedEntry.get("signing_attempted_at_epoch_seconds", Long.class))
        .isEqualTo(issuedEntry.signingAttemptedAtEpochSecond().orElseThrow());
  }

  private static String postgresSqlState(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof PSQLException postgresFailure) {
        return postgresFailure.getSQLState();
      }
      current = current.getCause();
    }
    return null;
  }

  private static String postgresServerMessage(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof PSQLException postgresFailure) {
        var serverError = postgresFailure.getServerErrorMessage();
        return serverError == null ? postgresFailure.getMessage() : serverError.getMessage();
      }
      current = current.getCause();
    }
    return String.valueOf(failure.getMessage());
  }

  private static long databaseNow(TestContext context) {
    return java.util.Objects.requireNonNull(
        context
            .dsl()
            .resultQuery("SELECT floor(extract(epoch FROM CURRENT_TIMESTAMP))::bigint")
            .fetchOne(0, Long.class),
        "Database clock query returned no row.");
  }

  private static void awaitDatabaseClockAtOrAfter(TestContext context, long epochSecond) {
    Awaitility.await()
        .alias("PostgreSQL clock reaches the immutable readiness plan expiry")
        .pollInterval(Duration.ofMillis(100))
        .atMost(Duration.ofSeconds(DISPATCHED_RECOVERY_EXPIRY_WAIT_SECONDS))
        .until(() -> databaseClockNow(context) >= epochSecond);
  }

  private static long databaseClockNow(TestContext context) {
    return java.util.Objects.requireNonNull(
        context
            .dsl()
            .resultQuery("SELECT floor(extract(epoch FROM clock_timestamp()))::bigint")
            .fetchOne(0, Long.class),
        "Database clock query returned no row.");
  }

  private static AuthenticatedAcceptance testAcceptance(
      ProbeEntry entry, ExpectedPod expectedPod, long observedAtEpochSecond) {
    return testAcceptance(entry, expectedPod, observedAtEpochSecond, entry.targetKid());
  }

  private static AuthenticatedAcceptance testAcceptance(
      ProbeEntry entry, ExpectedPod expectedPod, long observedAtEpochSecond, String verifiedKid) {
    return testAcceptance(
        entry,
        expectedPod,
        observedAtEpochSecond,
        verifiedKid,
        expectedPod.target().exactPodEndpoint().orElseThrow().toString());
  }

  private static AuthenticatedAcceptance testAcceptance(
      ProbeEntry entry,
      ExpectedPod expectedPod,
      long observedAtEpochSecond,
      String verifiedKid,
      String actualPodEndpoint) {
    var target = expectedPod.target();
    return AccountJwtReadinessReceiverInvocationPort.authenticatedAcceptance(
        target.podUid(),
        actualPodEndpoint,
        target.image(),
        target.verifierConfigSha256(),
        target.canonicalServiceUri().orElseThrow(),
        target.podLeafSpkiSha256().orElseThrow(),
        entry.jti(),
        entry.compactTokenSha256().orElseThrow(),
        verifiedKid,
        target.expectation(),
        observedAtEpochSecond);
  }

  private static AccountJwtReadinessProbeOwnerSelector ownerSelector(
      ReadinessProbePlan plan, ProbeEntry entry, ExpectedPod expectedPod) {
    return ownerSelector(plan, entry, expectedPod, entry.expectedActive());
  }

  private static AccountJwtReadinessProbeOwnerSelector ownerSelector(
      ReadinessProbePlan plan,
      ProbeEntry entry,
      ExpectedPod expectedPod,
      Optional<AccountJwtSignerDesiredStateRepository.ActiveSigner> activeFence) {
    return ownerSelector(plan, entry, expectedPod, activeFence, "inventory-r1", "d".repeat(64));
  }

  private static AccountJwtReadinessProbeOwnerSelector ownerSelector(
      ReadinessProbePlan plan,
      ProbeEntry entry,
      ExpectedPod expectedPod,
      Optional<AccountJwtSignerDesiredStateRepository.ActiveSigner> activeFence,
      String sourceInventoryRevision,
      String sourceInventoryDigest) {
    var target = expectedPod.target();
    LocalIdentity localIdentity =
        new LocalIdentity(
            target.validatorId(),
            target.deploymentUid(),
            target.podUid(),
            target.podIp(),
            target.exactPodEndpoint().orElseThrow().toString(),
            target.canonicalServiceUri().orElseThrow(),
            target.image(),
            target.verifierConfigSha256(),
            target.applicabilityMatrixDigest(),
            sourceInventoryRevision,
            sourceInventoryDigest,
            target.podLeafSpkiSha256().orElseThrow());
    return new AccountJwtReadinessProbeOwnerSelector(
        plan.operationId(),
        plan.operationDigest(),
        plan.planDigest(),
        plan.planVersion(),
        entry.registryVersion(),
        entry.validatorId(),
        entry.tokenProfile(),
        entry.audience(),
        entry.probeKind(),
        target.expectation(),
        entry.jti(),
        entry.entryVersion(),
        entry.targetGeneration(),
        entry.targetKid(),
        activeFence,
        entry.plannedIssuedAtEpochSecond(),
        entry.expiresAtEpochSecond(),
        plan.expiresAtEpochSecond(),
        entry.compactTokenSha256().orElseThrow(),
        localIdentity);
  }

  private static InventorySnapshot completeInventorySnapshot(
      Instant observedAt, ObservationContext observationContext) {
    return completeInventorySnapshot(observedAt, "10.11.0.11", observationContext);
  }

  private static InventorySnapshot completeInventorySnapshot(
      Instant observedAt, String firstAccountPodIp, ObservationContext observationContext) {
    String accountImage = "registry.example/account-validator@sha256:" + "1".repeat(64);
    String gameSessionImage = "registry.example/game-session-validator@sha256:" + "3".repeat(64);
    String accountConfigDigest = "2".repeat(64);
    String gameSessionConfigDigest = "4".repeat(64);
    String accountServiceUri = "spiffe://firemud/ns/firemud-prod/sa/account-jwt-validator";
    String gameSessionServiceUri = "spiffe://firemud/ns/firemud-prod/sa/game-session-jwt-validator";
    List<ProfileExpectation> accountProfiles =
        List.of(
            new ProfileExpectation("control-ui", "control-ui"),
            new ProfileExpectation("game-session-account-delegation", "account-service"),
            new ProfileExpectation("player-bootstrap", "player-bootstrap"));
    List<ProfileExpectation> gameSessionProfiles =
        List.of(new ProfileExpectation("game-session-account-delegation", "account-service"));
    List<PodObservation> accountPods =
        List.of(
            inventoryPod(
                "account-a",
                "77777777-7777-4777-8777-777777777777",
                "account-rs",
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "acct1",
                firstAccountPodIp,
                9443,
                accountImage,
                accountConfigDigest,
                accountServiceUri,
                "5".repeat(64)),
            inventoryPod(
                "account-b",
                "88888888-8888-4888-8888-888888888888",
                "account-rs",
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "acct1",
                "10.11.0.12",
                9443,
                accountImage,
                accountConfigDigest,
                accountServiceUri,
                "6".repeat(64)));
    List<PodObservation> gameSessionPods =
        List.of(
            inventoryPod(
                "game-session-a",
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "game-session-rs",
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                "games",
                "10.12.0.11",
                9444,
                gameSessionImage,
                gameSessionConfigDigest,
                gameSessionServiceUri,
                "7".repeat(64)));
    List<ValidatorObservation> validators =
        List.of(
            new ValidatorObservation(
                "account-service",
                "account-service",
                "66666666-6666-4666-8666-666666666666",
                1L,
                "51",
                2,
                accountImage,
                accountConfigDigest,
                300,
                accountProfiles,
                accountPods,
                List.of()),
            new ValidatorObservation(
                "game-session-service",
                "game-session-service",
                "99999999-9999-4999-8999-999999999999",
                1L,
                "52",
                1,
                gameSessionImage,
                gameSessionConfigDigest,
                300,
                gameSessionProfiles,
                gameSessionPods,
                List.of()));
    List<Map<String, Object>> validatorMaps =
        List.of(
            inventoryValidatorMap(validators.get(0), accountServiceUri, 9443),
            inventoryValidatorMap(validators.get(1), gameSessionServiceUri, 9444));
    Map<String, Object> preimage = new LinkedHashMap<>();
    preimage.put("domain", "firemud-account-validator-inventory/v2");
    preimage.put("environmentId", BINDING.environmentId());
    preimage.put("clusterId", BINDING.clusterId());
    preimage.put("clusterIncarnationUid", CLUSTER_UID);
    preimage.put("namespace", BINDING.namespace());
    preimage.put("namespaceUid", NAMESPACE_UID);
    preimage.put("apiBindingRevision", API_REVISION);
    preimage.put("apiBindingDigest", API_DIGEST);
    preimage.put("inventoryBindingRevision", "inventory-r1");
    preimage.put("inventoryBindingDigest", "d".repeat(64));
    preimage.put(
        "observationContext",
        Map.of(
            "purpose", observationContext.purpose().name(),
            "operationId", observationContext.operationId().toString(),
            "operationDigest", observationContext.operationDigest()));
    preimage.put("validators", validatorMaps);
    byte[] bytes;
    try {
      bytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(preimage));
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
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
    when(snapshot.inventoryBindingDigest()).thenReturn("d".repeat(64));
    when(snapshot.observationContext()).thenReturn(Optional.of(observationContext));
    when(snapshot.validators()).thenReturn(validators);
    when(snapshot.canonicalBytes()).thenReturn(bytes.clone());
    when(snapshot.digest()).thenReturn(sha256(bytes));
    return snapshot;
  }

  private static PodObservation inventoryPod(
      String name,
      String uid,
      String ownerName,
      String ownerUid,
      String podTemplateHash,
      String podIp,
      int receiverPort,
      String image,
      String configDigest,
      String serviceUri,
      String spkiDigest) {
    String host = podIp.indexOf(':') >= 0 ? "[" + podIp + "]" : podIp;
    return new PodObservation(
        name,
        uid,
        "11",
        ownerName,
        ownerUid,
        podTemplateHash,
        image,
        configDigest,
        podIp,
        URI.create("grpcs://" + host + ":" + receiverPort),
        serviceUri,
        spkiDigest);
  }

  private static Map<String, Object> inventoryValidatorMap(
      ValidatorObservation validator, String serviceUri, int receiverPort) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("validatorId", validator.validatorId());
    result.put("deploymentName", validator.deploymentName());
    result.put("deploymentUid", validator.deploymentUid());
    result.put("deploymentGeneration", Long.toString(validator.deploymentGeneration()));
    result.put("deploymentResourceVersion", validator.deploymentResourceVersion());
    result.put("replicas", Integer.toString(validator.replicas()));
    result.put("image", validator.image());
    result.put("verifierConfigSha256", validator.verifierConfigSha256());
    result.put("maxCacheAgeSeconds", Integer.toString(validator.maxCacheAgeSeconds()));
    result.put(
        "profiles",
        validator.profiles().stream()
            .map(
                profile ->
                    Map.of("tokenProfile", profile.tokenProfile(), "audience", profile.audience()))
            .toList());
    result.put(
        "pods",
        validator.pods().stream()
            .map(
                pod ->
                    Map.ofEntries(
                        Map.entry("name", pod.name()),
                        Map.entry("uid", pod.uid()),
                        Map.entry("resourceVersion", pod.resourceVersion()),
                        Map.entry("ownerReplicaSetName", pod.ownerName()),
                        Map.entry("ownerReplicaSetUid", pod.ownerUid()),
                        Map.entry("podTemplateHash", pod.podTemplateHash()),
                        Map.entry("image", pod.image()),
                        Map.entry("verifierConfigSha256", pod.verifierConfigSha256()),
                        Map.entry("podIp", pod.podIp()),
                        Map.entry("exactPodEndpoint", pod.endpoint().toString()),
                        Map.entry("canonicalServiceUri", pod.receiverServiceUri()),
                        Map.entry("leafSpkiSha256", pod.leafSpkiSha256())))
            .toList());
    result.put("receiverServiceUri", serviceUri);
    result.put("receiverPort", receiverPort);
    result.put("replicaSets", List.of());
    return result;
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
    try {
      return JSON.writeValueAsString(bundle);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static String generationMarker(GenerationResult result) throws Exception {
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
    return canonicalJson(marker);
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
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is required for the persistence fixture", failure);
    }
  }

  private record PublicJwk(String json, String jsonWithoutKeyOps, String fingerprint) {}

  private record OwnerState(
      AccountJwtSignerDesiredStateRepository desired,
      AccountJwtJwksPublicationRepository publication,
      AccountJwtValidatorInventoryRepository inventory,
      AccountJwtReadinessProbeRepository readiness,
      TrustFence trust,
      GenerationRequest request,
      GenerationResult result,
      KeyPair keyPair,
      String desiredJwks) {}

  private record TestContext(
      String schema,
      javax.sql.DataSource dataSource,
      TransactionTemplate transaction,
      DSLContext dsl) {}
}
