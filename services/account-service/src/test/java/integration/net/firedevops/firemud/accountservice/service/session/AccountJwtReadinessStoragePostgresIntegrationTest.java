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
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding.ProfileExpectation;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ExpectedPod;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeEntry;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeState;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessProbePlan;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.EnrollmentIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PromotionPreparation;
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
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Physical PostgreSQL proof for Account-owned V2 readiness inventory, signing-state, and per-Pod
 * receipt storage. The inventory and authenticated-acceptance inputs are synthetic owner values, as
 * in the retained donor proof; this does not establish live Kubernetes currentness, receiver
 * authentication, readiness activation, or signer activation. The local ephemeral canary key is
 * only a real signing precondition for the storage transition.
 */
class AccountJwtReadinessStoragePostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "jwt_readiness_owner_storage";
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
    InventorySnapshot planningInventory =
        completeInventorySnapshot(Instant.ofEpochSecond(databaseNow - 300), observationContext);
    ReadinessProbePlan plan =
        inTransaction(
            context,
            () ->
                owner
                    .readiness()
                    .planCurrent(
                        BINDING,
                        owner.trust(),
                        Instant.ofEpochSecond(databaseNow - 300),
                        planningInventory));
    assertThat(plan.planVersion()).isEqualTo(2);
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
                        testAcceptance(issuedEntry, expectedPods.get(1), databaseNow),
                        refreshedInventory));
    assertThat(verified.state()).isEqualTo(ProbeState.VERIFIED);
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
        .isInstanceOf(RuntimeException.class);
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
        .isInstanceOf(RuntimeException.class);

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
        .isInstanceOf(RuntimeException.class);
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
        .isInstanceOf(RuntimeException.class);

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
                        BINDING, owner.trust(), owner.result().operationId(), refreshedInventory)
                    .orElseThrow());
    assertThat(restartedProof.readinessEvidencePreimage()).isEqualTo(exactPreimage);
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
                            BINDING, restartedOwner.trust(), preparation, restartedProof)))
        .isEqualTo(prepared);
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
    var publication = proof.publication();
    int inserted =
        inTransaction(
            context,
            () ->
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
                        UUID.randomUUID(),
                        BINDING.environmentId(),
                        BINDING.clusterId(),
                        BINDING.namespace(),
                        BINDING.mode().value(),
                        "a".repeat(64),
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
                        "{}"));
    assertThat(inserted).isEqualTo(1);
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

  private static long databaseNow(TestContext context) {
    return java.util.Objects.requireNonNull(
        context
            .dsl()
            .resultQuery("SELECT floor(extract(epoch FROM CURRENT_TIMESTAMP))::bigint")
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
            "inventory-source-r1",
            "9".repeat(64),
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
