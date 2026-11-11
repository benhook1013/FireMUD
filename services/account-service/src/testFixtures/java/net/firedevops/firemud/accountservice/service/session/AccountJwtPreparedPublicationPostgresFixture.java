package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding.ProfileExpectation;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ExpectedPod;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeEntry;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeState;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessProbePlan;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.AuthenticatedAcceptance;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationContext;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.PodObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ValidatorObservation;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bounded real-PostgreSQL readiness setup for public-publication writer tests. Inventory and
 * authenticated receiver inputs are controlled transport observations; all durable plans, real
 * mounted-key signing attempts, per-pod receipts and promotion proofs use the normal owners. No SQL
 * writes, synthetic owner receipts, live Kubernetes IO or runtime activation are provided. Mutable
 * owner/database collaborators are supplied per operation and are not retained by this fixture.
 */
public final class AccountJwtPreparedPublicationPostgresFixture {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final Binding binding;
  private final TrustFence trust;
  private final GenerationResult result;
  private final String apiDigest;
  private final String apiRevision;

  public AccountJwtPreparedPublicationPostgresFixture(
      Binding binding,
      TrustFence trust,
      GenerationResult result,
      String apiDigest,
      String apiRevision) {
    this.binding = binding;
    this.trust = trust;
    this.result = result;
    this.apiDigest = apiDigest;
    this.apiRevision = apiRevision;
  }

  /** Completes the existing readiness prerequisites; the caller invokes its real PREPARED owner. */
  public void completeReadiness(
      AccountJwtReadinessProbeRepository readiness,
      TransactionTemplate transaction,
      DSLContext dsl,
      Path privateMount,
      Path publicMount)
      throws Exception {
    ObservationContext context =
        inTransaction(
            transaction, () -> readiness.readCurrentInventoryObservationContext(binding, trust));
    long plannedAt =
        databaseNow(dsl)
            - AccountJwtReadinessProbeRepository.MAX_VALIDATOR_CACHE_AGE_SECONDS
            - AccountJwtReadinessProbeRepository.PROBE_LIFETIME_SECONDS
            + 300;
    Instant planningTime = Instant.ofEpochSecond(plannedAt);
    ReadinessProbePlan plan =
        inTransaction(
            transaction,
            () ->
                readiness.planCurrent(
                    binding,
                    trust,
                    planningTime,
                    completeInventorySnapshot(planningTime, context)));
    var identity =
        new AccountMountedJwtSignerBundle.ExpectedIdentity(
            binding.environmentId(),
            binding.clusterId(),
            binding.namespace(),
            result.operationId().toString(),
            result.targetGeneration(),
            result.targetKid(),
            result.publicKeyFingerprint());
    assertThat(
            completeRemainingProbes(
                readiness,
                transaction,
                dsl,
                plan,
                currentInventory(dsl, context),
                privateMount,
                publicMount,
                identity))
        .isEqualTo(12L);
    assertThat(
            inTransaction(
                transaction,
                () ->
                    readiness.readCurrentPromotionPrerequisites(
                        binding, trust, result.operationId(), currentInventory(dsl, context))))
        .isPresent();
  }

  public InventorySnapshot currentInventory(DSLContext dsl, ObservationContext context) {
    return completeInventorySnapshot(Instant.ofEpochSecond(databaseNow(dsl)), context);
  }

  private static <T> T inTransaction(TransactionTemplate transaction, Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }

  private static long databaseNow(DSLContext dsl) {
    return java.util.Objects.requireNonNull(
        dsl.resultQuery("SELECT floor(extract(epoch FROM clock_timestamp()))::bigint")
            .fetchOne(0, Long.class));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is required for readiness proof", failure);
    }
  }

  private long completeRemainingProbes(
      AccountJwtReadinessProbeRepository readiness,
      TransactionTemplate transaction,
      DSLContext dsl,
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
                  transaction,
                  () -> readiness.readCurrentPlan(binding, trust, result.operationId()))
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
                transaction,
                () -> readiness.recordSigningAttempt(binding, trust, digest, plannedAt));
            inTransaction(
                transaction, () -> readiness.recordIssued(binding, trust, digest, plannedAt));
          });
      ProbeEntry issued =
          inTransaction(
                  transaction,
                  () -> readiness.readCurrentPlan(binding, trust, result.operationId()))
              .entries()
              .stream()
              .filter(entry -> entry.jti().equals(planned.jti()))
              .findFirst()
              .orElseThrow();
      ProbeEntry issuedForInventory = issued;
      List<ExpectedPod> pods =
          inTransaction(
              transaction,
              () ->
                  readiness.readCurrentExpectedPods(binding, trust, issuedForInventory, inventory));
      expectedPodReceiptCount += pods.size();
      for (ExpectedPod pod : pods) {
        ProbeEntry currentEvidence = issued;
        var acceptance = testAcceptance(currentEvidence, pod, databaseNow(dsl));
        issued =
            inTransaction(
                transaction,
                () ->
                    readiness.recordPodAcceptance(
                        binding, trust, currentEvidence, pod, acceptance, inventory));
      }
      assertThat(issued.state()).isEqualTo(ProbeState.VERIFIED);
    }
    return expectedPodReceiptCount;
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

  public InventorySnapshot completeInventorySnapshot(
      Instant observedAt, ObservationContext observationContext) {
    return completeInventorySnapshot(observedAt, "10.11.0.11", observationContext);
  }

  public InventorySnapshot completeInventorySnapshot(
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
    preimage.put("environmentId", binding.environmentId());
    preimage.put("clusterId", binding.clusterId());
    preimage.put("clusterIncarnationUid", trust.expectedClusterIncarnationUid());
    preimage.put("namespace", binding.namespace());
    preimage.put("namespaceUid", trust.expectedNamespaceUid());
    preimage.put("apiBindingRevision", apiRevision);
    preimage.put("apiBindingDigest", apiDigest);
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
    when(snapshot.environmentId()).thenReturn(binding.environmentId());
    when(snapshot.clusterId()).thenReturn(binding.clusterId());
    when(snapshot.clusterIncarnationUid()).thenReturn(trust.expectedClusterIncarnationUid());
    when(snapshot.namespace()).thenReturn(binding.namespace());
    when(snapshot.namespaceUid()).thenReturn(trust.expectedNamespaceUid());
    when(snapshot.apiBindingRevision()).thenReturn(apiRevision);
    when(snapshot.apiBindingDigest()).thenReturn(apiDigest);
    when(snapshot.inventoryBindingRevision()).thenReturn("inventory-r1");
    when(snapshot.inventoryBindingDigest()).thenReturn("d".repeat(64));
    when(snapshot.observationContext()).thenReturn(Optional.of(observationContext));
    when(snapshot.validators()).thenReturn(validators);
    when(snapshot.canonicalBytes()).thenReturn(bytes.clone());
    when(snapshot.digest()).thenReturn(sha256(bytes));
    return snapshot;
  }

  private PodObservation inventoryPod(
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

  private Map<String, Object> inventoryValidatorMap(
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
}
