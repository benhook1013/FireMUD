package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import io.grpc.stub.StreamObserver;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.firedevops.firemud.account.v1.EnrollBootstrapRequest;
import net.firedevops.firemud.account.v1.EnrollBootstrapResponse;
import net.firedevops.firemud.account.v1.GetCurrentGenerationRequestRequest;
import net.firedevops.firemud.account.v1.GetCurrentGenerationRequestResponse;
import net.firedevops.firemud.account.v1.GetCurrentPromotionRequestRequest;
import net.firedevops.firemud.account.v1.GetCurrentPromotionRequestResponse;
import net.firedevops.firemud.account.v1.PromotionPublicKeyIdentity;
import net.firedevops.firemud.account.v1.RecordGenerationResultRequest;
import net.firedevops.firemud.account.v1.RecordGenerationResultResponse;
import net.firedevops.firemud.account.v1.RecordPrivatePromotionResultRequest;
import net.firedevops.firemud.account.v1.RecordPrivatePromotionResultResponse;
import net.firedevops.firemud.account.v1.RecordSecretObservationRequest;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding.ProfileExpectation;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeEntry;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.security.AccountJwtSignerMaterializerTlsInterceptor;
import net.firedevops.firemud.accountservice.service.impl.AccountJwtSignerMaterializationService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationContext;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.PodObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ValidatorObservation;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Actual signer/readiness owner lifecycle; trust, platform inventory and Pod acceptance are
 * stipulated test inputs.
 */
final class AccountControlUiSignerFixture {
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
  private static final String API_DIGEST = "b".repeat(64), API_REVISION = "api-r1";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  final AccountControlUiOwnerSourcesFixture f;
  final AccountJwtSignerDesiredStateRepository desired;
  final AccountJwtJwksPublicationRepository publication;
  final AccountJwtReadinessProbeRepository readiness;
  final AccountJwtSignerMaterializerTrustBinding trust =
      mock(AccountJwtSignerMaterializerTrustBinding.class);
  final AccountJwtJwksApiBinding api = mock(AccountJwtJwksApiBinding.class);
  final AccountJwtJwksConfigMapClient client = mock(AccountJwtJwksConfigMapClient.class);
  final AccountJwtValidatorInventorySource inventory =
      mock(AccountJwtValidatorInventorySource.class);
  final AtomicReference<AccountJwtJwksConfigMapClient.ConfigMapSnapshot> configMap =
      new AtomicReference<>(
          new AccountJwtJwksConfigMapClient.ConfigMapSnapshot(
              CONFIG_MAP_UID, "41", Map.of("jwks.json", "{\"keys\":[]}")));
  final Path privateRoot, publicRoot;
  final AccountJwtSignerBootstrapCoordinator coordinator;
  final AccountJwtSignerMaterializationService materializer;
  final AccountControlUiSignerOwner signer;

  AccountControlUiSignerFixture(AccountControlUiOwnerSourcesFixture fixture, Path root)
      throws Exception {
    f = fixture;
    privateRoot = Files.createDirectories(root.resolve("private"));
    publicRoot = Files.createDirectories(root.resolve("public"));
    desired = new AccountJwtSignerDesiredStateRepository(f.dsl);
    publication = new AccountJwtJwksPublicationRepository(f.dsl, desired);
    readiness = new AccountJwtReadinessProbeRepository(f.dsl, desired, publication);
    when(trust.current()).thenReturn(Optional.of(materializerBinding()));
    when(api.current())
        .thenReturn(
            new AccountJwtJwksApiBinding.ParsedBinding(
                API_REVISION,
                BINDING.environmentId(),
                BINDING.clusterId(),
                URI.create("https://kubernetes.default.svc:6443/"),
                "kubernetes.default.svc",
                root.resolve("test-only-ca"),
                "d".repeat(64),
                root.resolve("test-only-bearer"),
                BINDING.namespace(),
                CLUSTER_UID,
                NAMESPACE_UID,
                "system:serviceaccount:firemud-prod:account-service",
                API_DIGEST));
    when(client.identity())
        .thenReturn(
            new AccountJwtJwksConfigMapClient.BindingIdentity(
                API_DIGEST,
                API_REVISION,
                BINDING.environmentId(),
                BINDING.clusterId(),
                BINDING.namespace(),
                CLUSTER_UID,
                NAMESPACE_UID,
                "https://kubernetes.default.svc:6443/",
                "kubernetes.default.svc",
                "d".repeat(64),
                "system:serviceaccount:firemud-prod:account-service"));
    when(client.observe()).thenAnswer(ignored -> configMap.get());
    when(client.publish(any(), any()))
        .thenAnswer(call -> publish(call.getArgument(0), call.getArgument(1)));
    when(client.publishActiveProjection(any(), any(), any()))
        .thenAnswer(call -> publish(call.getArgument(0), call.getArgument(2)));
    when(inventory.observe(any(ObservationContext.class)))
        .thenAnswer(call -> completeInventorySnapshot(now(), call.getArgument(0)));
    var mounted =
        new AccountJwtJwksPrepublicationService.MountedProjection(
            privateRoot,
            Path.of("pending.key"),
            publicRoot,
            Path.of("jwks.json"),
            Path.of("jwt-generation.json"));
    var prepublication =
        new AccountJwtJwksPrepublicationService(
            desired, publication, trust, client, f.manager, mounted);
    var beans = new StaticListableBeanFactory();
    beans.addBean("testOnlyInventoryInput", inventory);
    coordinator =
        new AccountJwtSignerBootstrapCoordinator(
            trust,
            client,
            desired,
            prepublication,
            readiness,
            beans.getBeanProvider(AccountJwtValidatorInventorySource.class),
            f.transactions);
    beans.addBean("actualAccountCoordinator", coordinator);
    materializer =
        new AccountJwtSignerMaterializationService(
            desired,
            readiness,
            trust,
            f.manager,
            beans.getBeanProvider(AccountJwtSignerBootstrapCoordinator.class),
            beans.getBeanProvider(AccountJwtValidatorInventorySource.class));
    signer = new AccountControlUiSignerOwner(desired, trust, api, privateRoot, publicRoot);
  }

  AccountJwtJwksConfigMapClient.CasObservation publish(
      AccountJwtJwksConfigMapClient.ConfigMapSnapshot expected, Map<String, String> data)
      throws Exception {
    assertThat(configMap.get()).isEqualTo(expected);
    var next =
        new AccountJwtJwksConfigMapClient.ConfigMapSnapshot(
            CONFIG_MAP_UID, Long.toString(Long.parseLong(expected.resourceVersion()) + 1), data);
    configMap.set(next);
    for (var entry : data.entrySet())
      Files.writeString(publicRoot.resolve(entry.getKey()), entry.getValue());
    return new AccountJwtJwksConfigMapClient.CasObservation(
        CONFIG_MAP_UID,
        expected.resourceVersion(),
        next.resourceVersion(),
        data,
        data,
        AccountJwtJwksConfigMapClient.Outcome.APPLIED);
  }

  void commit() throws Exception {
    // Only the protected upstream identity is stipulated; every owner method/transaction is real.
    try (var authenticated = mockStatic(AccountJwtSignerMaterializerTlsInterceptor.class)) {
      authenticated
          .when(AccountJwtSignerMaterializerTlsInterceptor::authenticatedBinding)
          .thenReturn(materializerBinding());
      AccountControlUiSignerFixture.<EnrollBootstrapResponse>rpc(
          out ->
              materializer.enrollBootstrap(
                  EnrollBootstrapRequest.newBuilder().setSchemaVersion(1).build(), out));
      GetCurrentGenerationRequestResponse initial =
          rpc(
              out ->
                  materializer.getCurrentGenerationRequest(
                      GetCurrentGenerationRequestRequest.newBuilder().setSchemaVersion(1).build(),
                      out));
      var request = f.tx(() -> desired.readCurrentGenerationRequest(BINDING, trust()));
      GetCurrentGenerationRequestResponse observed =
          rpc(
              out ->
                  materializer.recordSecretObservation(
                      RecordSecretObservationRequest.newBuilder()
                          .setSchemaVersion(1)
                          .setOperationId(initial.getOperationId())
                          .setOperationDigest(initial.getOperationDigest())
                          .setSecretUid(SECRET_UID)
                          .setObservedResourceVersion("12")
                          .build(),
                      out));
      var pair = generateRsa3072();
      var publicKey = publicJwk(request.targetKid(), (RSAPublicKey) pair.getPublic());
      RecordGenerationResultResponse recorded =
          rpc(
              out ->
                  materializer.recordGenerationResult(
                      RecordGenerationResultRequest.newBuilder()
                          .setSchemaVersion(1)
                          .setOperationId(initial.getOperationId())
                          .setGenerationRequestDigest(observed.getGenerationRequestDigest())
                          .setSecretUid(SECRET_UID)
                          .setExpectedPriorResourceVersion("12")
                          .setObservedResourceVersion("13")
                          .setPublicJwkJson(publicKey.json())
                          .build(),
                      out));
      var result = f.tx(() -> desired.readCurrentGenerationResult(BINDING, trust()));
      assertThat(recorded.getOperationId()).isEqualTo(result.operationId().toString());
      assertThat(recorded.getOperationDigest()).isEqualTo(result.operationDigest());
      assertThat(recorded.getGenerationRequestDigest()).isEqualTo(result.generationRequestDigest());
      assertThat(recorded.getGenerationReceiptDigest()).isEqualTo(result.receiptDigest());
      assertThat(recorded.getObservedResourceVersion()).isEqualTo(result.observedResourceVersion());
      assertThat(recorded.getPublicKeyFingerprint()).isEqualTo(result.publicKeyFingerprint());
      Files.writeString(
          privateRoot.resolve("pending.key"), privateBundleJson(request, result, pair));
      assertThat(
              coordinator
                  .reconcileCurrentGenerationOnce(result.operationId())
                  .publicKeyPrepublished())
          .isTrue();
      var context = f.tx(() -> readiness.readCurrentInventoryObservationContext(BINDING, trust()));
      var plannedAt = now().minusSeconds(300);
      var planned = completeInventorySnapshot(plannedAt, context);
      var plan = f.tx(() -> readiness.planCurrent(BINDING, trust(), plannedAt, planned));
      var harnessUri = "spiffe://firemud/ns/firemud-prod/sa/account-jwt-readiness-harness";
      var harnessPin = "e".repeat(64);
      long validUntil = now().plusSeconds(3600).getEpochSecond();
      String readinessDigest =
          net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding
              .computeBindingDigest(
                  "test-readiness-r1",
                  BINDING.environmentId(),
                  BINDING.clusterId(),
                  BINDING.namespace(),
                  CLUSTER_UID,
                  NAMESPACE_UID,
                  harnessUri,
                  List.of(harnessPin),
                  "test-only-validator",
                  validUntil);
      var harness =
          new net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding.Binding(
              BINDING.environmentId(),
              BINDING.clusterId(),
              BINDING.namespace(),
              CLUSTER_UID,
              NAMESPACE_UID,
              harnessUri,
              List.of(harnessPin),
              "test-readiness-r1",
              "account-service",
              "test-only-validator",
              validUntil,
              readinessDigest);
      var peer =
          new net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding
              .PeerIdentity(harnessUri, harnessPin);
      var claim =
          f.tx(() -> readiness.claimSingleDelivery(BINDING, trust(), plan, harness, peer, now()));
      var producer =
          new AccountJwtReadinessProbeService(
              readiness,
              f.transactions,
              Clock.systemUTC(),
              privateRoot,
              Path.of("pending.key"),
              publicRoot,
              Path.of("jwks.json"));
      var delivered =
          producer.issueCurrentPlan(
              BINDING,
              trust(),
              result.operationId(),
              claim,
              (metadata, compact) -> {
                assertThat(compact).isNotEmpty();
                var issued =
                    f
                        .tx(() -> readiness.readCurrentPlan(BINDING, trust(), result.operationId()))
                        .entries()
                        .stream()
                        .filter(entry -> entry.jti().equals(metadata.jti()))
                        .findFirst()
                        .orElseThrow();
                var current = completeInventorySnapshot(now(), context);
                var pods =
                    f.tx(
                        () -> readiness.readCurrentExpectedPods(BINDING, trust(), issued, current));
                for (var pod : pods) {
                  var acceptance = testAuthenticatedAcceptance(issued, pod, now().getEpochSecond());
                  f.tx(
                      () ->
                          readiness.recordPodAcceptance(
                              BINDING, trust(), issued, pod, acceptance, current));
                }
              });
      assertThat(delivered)
          .hasSize(8)
          .allSatisfy(
              entry ->
                  assertThat(entry.state())
                      .isEqualTo(AccountJwtReadinessProbeRepository.ProbeState.VERIFIED));
      assertThat(f.dsl.fetchCount(DSL.table("account_jwt_readiness_pod_receipts"))).isEqualTo(12);
      assertThat(
              f.dsl.fetchCount(
                  DSL.table("account_jwt_readiness_pod_receipts"),
                  DSL.field("outcome").eq("INAPPLICABLE_REJECT")))
          .isEqualTo(2);
      var prepared = coordinator.prepareCurrentPromotionOnce().orElseThrow();
      GetCurrentPromotionRequestResponse dispatched =
          rpc(
              out ->
                  materializer.getCurrentPromotionRequest(
                      GetCurrentPromotionRequestRequest.newBuilder().setSchemaVersion(1).build(),
                      out));
      var promotion =
          f.tx(() -> desired.readPreparedGenerationForRecovery(BINDING, trust())).promotion();
      assertThat(prepared.operationId()).isEqualTo(promotion.operationId());
      assertThat(dispatched.getPromotionOperationId())
          .isEqualTo(promotion.operationId().toString());
      assertThat(dispatched.getPromotionRequestDigest()).isEqualTo(promotion.requestDigest());
      assertThat(dispatched.getGenerationOperationId()).isEqualTo(result.operationId().toString());
      assertThat(dispatched.getGenerationOperationDigest()).isEqualTo(result.operationDigest());
      assertThat(dispatched.getGenerationReceiptDigest()).isEqualTo(result.receiptDigest());
      assertThat(dispatched.getExpectedSecretUid()).isEqualTo(promotion.secretUid());
      assertThat(dispatched.getExpectedSecretResourceVersion())
          .isEqualTo(promotion.expectedPrivateResourceVersion());
      Files.writeString(
          privateRoot.resolve("current.key"), privateBundleJson(request, result, pair));
      RecordPrivatePromotionResultResponse committed =
          rpc(
              out ->
                  materializer.recordPrivatePromotionResult(
                      RecordPrivatePromotionResultRequest.newBuilder()
                          .setSchemaVersion(1)
                          .setPromotionOperationId(promotion.operationId().toString())
                          .setPromotionRequestDigest(promotion.requestDigest())
                          .setGenerationOperationId(result.operationId().toString())
                          .setGenerationOperationDigest(result.operationDigest())
                          .setSecretUid(SECRET_UID)
                          .setExpectedPriorResourceVersion(
                              promotion.expectedPrivateResourceVersion())
                          .setObservedResourceVersion("14")
                          .setCurrent(
                              PromotionPublicKeyIdentity.newBuilder()
                                  .setGeneration(result.targetGeneration())
                                  .setKid(result.targetKid())
                                  .setPublicKeyFingerprint(result.publicKeyFingerprint()))
                          .addResultingSlots("current")
                          .build(),
                      out));
      var durableCommit =
          f.tx(() -> desired.readCurrentCommittedSigner(BINDING, trust(), API_DIGEST, API_REVISION))
              .orElseThrow();
      assertThat(committed.getPromotionOperationId())
          .isEqualTo(durableCommit.promotion().operationId().toString());
      assertThat(committed.getGenerationOperationId())
          .isEqualTo(durableCommit.generationResult().operationId().toString());
      assertThat(committed.getObservedResourceVersion())
          .isEqualTo(durableCommit.privateReceipt().observedResourceVersion());
      assertThat(committed.getPublicReceiptDigest())
          .isEqualTo(durableCommit.privateReceipt().receiptDigest());
    }
  }

  private static <T> T rpc(Consumer<StreamObserver<T>> invocation) {
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    invocation.accept(
        new StreamObserver<>() {
          @Override
          public void onNext(T value) {
            result.set(value);
          }

          @Override
          public void onError(Throwable error) {
            failure.set(error);
          }

          @Override
          public void onCompleted() {}
        });
    if (failure.get() != null)
      throw new AssertionError("Actual Account materialization RPC failed", failure.get());
    return java.util.Objects.requireNonNull(result.get());
  }

  private record PublicJwk(String json, String jsonWithoutKeyOps, String fingerprint) {}

  private static Instant now() {
    return Instant.ofEpochSecond(Instant.now().getEpochSecond());
  }

  private static TrustFence trust() {
    return new TrustFence(CLUSTER_UID, NAMESPACE_UID, TRUST_DIGEST, TRUST_REVISION);
  }

  private static AccountJwtSignerMaterializerTrustBinding.Binding materializerBinding() {
    return new AccountJwtSignerMaterializerTrustBinding.Binding(
        BINDING.environmentId(),
        BINDING.clusterId(),
        BINDING.namespace(),
        CLUSTER_UID,
        NAMESPACE_UID,
        MATERIALIZER_URI,
        MATERIALIZER_PINS,
        TRUST_REVISION,
        TRUST_DIGEST);
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

  /** Stipulated platform input only; Account's real owners derive all private proof from it. */
  private static InventorySnapshot completeInventorySnapshot(
      Instant observedAt, ObservationContext observationContext) {
    return completeInventorySnapshot(observedAt, "10.11.0.11", observationContext);
  }

  private static InventorySnapshot completeInventorySnapshot(
      Instant observedAt, String firstAccountPodIp, ObservationContext observationContext) {
    return completeInventorySnapshot(
        observedAt, firstAccountPodIp, observationContext, "77777777-7777-4777-8777-777777777777");
  }

  private static InventorySnapshot completeInventorySnapshot(
      Instant observedAt,
      String firstAccountPodIp,
      ObservationContext observationContext,
      String firstAccountPodUid) {
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
                firstAccountPodUid,
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
    preimage.put("inventoryBindingDigest", "c".repeat(64));
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
    when(snapshot.inventoryBindingDigest()).thenReturn("c".repeat(64));
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

  private static AccountJwtReadinessReceiverInvocationPort.AuthenticatedAcceptance
      testAuthenticatedAcceptance(
          ProbeEntry entry,
          AccountJwtReadinessProbeRepository.ExpectedPod expectedPod,
          long observedAtEpochSecond) {
    return testAuthenticatedAcceptance(
        entry, expectedPod, observedAtEpochSecond, entry.targetKid());
  }

  private static AccountJwtReadinessReceiverInvocationPort.AuthenticatedAcceptance
      testAuthenticatedAcceptance(
          ProbeEntry entry,
          AccountJwtReadinessProbeRepository.ExpectedPod expectedPod,
          long observedAtEpochSecond,
          String verifiedKid) {
    return testAuthenticatedAcceptance(
        entry,
        expectedPod,
        observedAtEpochSecond,
        verifiedKid,
        expectedPod.target().exactPodEndpoint().orElseThrow().toString());
  }

  private static AccountJwtReadinessReceiverInvocationPort.AuthenticatedAcceptance
      testAuthenticatedAcceptance(
          ProbeEntry entry,
          AccountJwtReadinessProbeRepository.ExpectedPod expectedPod,
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
}
