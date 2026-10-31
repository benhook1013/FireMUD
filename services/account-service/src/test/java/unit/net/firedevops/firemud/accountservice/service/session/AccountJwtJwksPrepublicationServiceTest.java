package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.PrepublicationIntent;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.PublicationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.PublicationReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationPhase;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.BindingIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.CasObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.ConfigMapSnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.Outcome;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class AccountJwtJwksPrepublicationServiceTest {
  private static final UUID OPERATION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final String CLUSTER_UID = "11111111-1111-4111-8111-111111111111";
  private static final String NAMESPACE_UID = "22222222-2222-4222-8222-222222222222";
  private static final String CONFIG_MAP_UID = "33333333-3333-4333-8333-333333333333";
  private static final String MATERIALIZER_URI =
      "spiffe://firemud/ns/firemud-prod/sa/jwt-signer-materializer";
  private static final String OPERATION_DIGEST = "a".repeat(64);
  private static final String REQUEST_DIGEST = "b".repeat(64);
  private static final String RECEIPT_DIGEST = "c".repeat(64);
  private static final String API_DIGEST = "d".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void currentGenerationIsPrepublishedWithRetainedKeysAndExactAccountReadbackOnly()
      throws Exception {
    Fixture fixture = fixture("{\"keys\":[]}", "41");
    fixture.stubCurrentGeneration();
    when(fixture.publicationRepository.readCurrentPublication(
            eq(accountBinding()), eq(trustFence(fixture.binding))))
        .thenReturn(Optional.empty());
    when(fixture.client.observe()).thenReturn(fixture.snapshot);
    when(fixture.client.publish(eq(fixture.snapshot), anyMap()))
        .thenAnswer(
            invocation -> {
              Map<String, String> desired = invocation.getArgument(1);
              Map<String, String> readback = new LinkedHashMap<>(desired);
              readback.put("unrelated.txt", "preserved");
              return new CasObservation(
                  CONFIG_MAP_UID, "41", "42", desired, readback, Outcome.APPLIED);
            });
    when(fixture.publicationRepository.recordPrepublicationIntent(
            eq(accountBinding()),
            eq(trustFence(fixture.binding)),
            eq(fixture.result),
            eq(fixture.request),
            eq(API_DIGEST),
            eq("api-r1"),
            eq(CONFIG_MAP_UID),
            eq("41"),
            any(String.class),
            any(String.class),
            any(String.class)))
        .thenAnswer(
            invocation ->
                intent(
                    fixture,
                    invocation.getArgument(9),
                    invocation.getArgument(10),
                    invocation.getArgument(7),
                    invocation.getArgument(8)));
    when(fixture.publicationRepository.recordPublicationReceipt(
            eq(accountBinding()),
            eq(trustFence(fixture.binding)),
            eq(OPERATION_ID),
            eq(API_DIGEST),
            eq("api-r1"),
            eq(CONFIG_MAP_UID),
            eq("42"),
            any(String.class),
            any(String.class)))
        .thenAnswer(
            invocation ->
                PublicationReceipt.create(
                    intent(
                        fixture,
                        invocation.getArgument(7),
                        invocation.getArgument(8),
                        "41",
                        AccountJwtJwksPublicationRepository.snapshotDigest(
                            fixture.snapshot.data())),
                    invocation.getArgument(6)));

    PublicationReceipt receipt = fixture.service.prepublishCurrentGeneration();

    assertThat(receipt.operationId()).isEqualTo(OPERATION_ID);
    ArgumentCaptor<String> jwksJson = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> generationMarkerJson = ArgumentCaptor.forClass(String.class);
    verify(fixture.publicationRepository)
        .recordPrepublicationIntent(
            eq(accountBinding()),
            eq(trustFence(fixture.binding)),
            eq(fixture.result),
            eq(fixture.request),
            eq(API_DIGEST),
            eq("api-r1"),
            eq(CONFIG_MAP_UID),
            eq("41"),
            any(String.class),
            jwksJson.capture(),
            generationMarkerJson.capture());
    PrepublicationIntent expectedIntent =
        intent(
            fixture,
            jwksJson.getValue(),
            generationMarkerJson.getValue(),
            "41",
            AccountJwtJwksPublicationRepository.snapshotDigest(fixture.snapshot.data()));
    assertThat(receipt.intentDigest()).isEqualTo(expectedIntent.intentDigest());
    assertThat(receipt.expectedResourceVersion()).isEqualTo("41");
    assertThat(receipt.observedResourceVersion()).isEqualTo("42");
    assertThat(receipt.publicDataDigest())
        .isEqualTo(
            AccountJwtJwksPublicationRepository.publicDataDigest(
                jwksJson.getValue(), generationMarkerJson.getValue()));
    verify(fixture.client).publish(eq(fixture.snapshot), anyMap());
    verify(fixture.publicationRepository)
        .recordPublicationReceipt(
            eq(accountBinding()),
            eq(trustFence(fixture.binding)),
            eq(OPERATION_ID),
            eq(API_DIGEST),
            eq("api-r1"),
            eq(CONFIG_MAP_UID),
            eq("42"),
            any(String.class),
            any(String.class));
    verify(fixture.desiredRepository, never()).prepareCurrentGeneration(any(), any(), any(), any());
    assertThat(fixture.transactionManager.commits).isEqualTo(3);
    assertThat(fixture.transactionManager.rollbacks).isZero();
  }

  @Test
  void wrongApiClusterOrNamespaceBindingFailsBeforeCurrentStateOrRemoteAccess() throws Exception {
    Fixture fixture = fixture("{\"keys\":[]}", "41");
    BindingIdentity wrongApi =
        new BindingIdentity(
            API_DIGEST,
            "api-r1",
            "prod",
            "different-cluster",
            "other-namespace",
            CLUSTER_UID,
            NAMESPACE_UID,
            "https://api.example.test",
            "api.example.test",
            "f".repeat(64),
            "system:serviceaccount:other-namespace:account-service");
    when(fixture.client.identity()).thenReturn(wrongApi);

    assertThatThrownBy(fixture.service::prepublishCurrentGeneration)
        .isInstanceOf(AccountJwtJwksPrepublicationService.PublicationRejectedException.class)
        .hasMessageContaining("bindings disagree");

    verifyNoInteractions(fixture.desiredRepository, fixture.publicationRepository);
    verify(fixture.client, never()).observe();
    verify(fixture.client, never()).publish(any(), anyMap());
  }

  @Test
  void withdrawnProtectedBindingBeforeTransactionCommitRollsBackWithoutRemoteWrite()
      throws Exception {
    Fixture fixture = fixture("{\"keys\":[]}", "41");
    Binding changed = trustBinding("trust-r2");
    when(fixture.trustBindingProvider.current())
        .thenReturn(Optional.of(fixture.binding), Optional.of(changed));
    fixture.stubCurrentGeneration();
    when(fixture.publicationRepository.readCurrentPublication(
            eq(accountBinding()), eq(trustFence(fixture.binding))))
        .thenReturn(Optional.empty());

    assertThatThrownBy(fixture.service::prepublishCurrentGeneration)
        .isInstanceOf(AccountJwtJwksPrepublicationService.PublicationRejectedException.class)
        .hasMessageContaining("trust changed");

    assertThat(fixture.transactionManager.commits).isZero();
    assertThat(fixture.transactionManager.rollbacks).isEqualTo(1);
    verify(fixture.client, never()).observe();
    verify(fixture.client, never()).publish(any(), anyMap());
    verify(fixture.publicationRepository, never())
        .recordPrepublicationIntent(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void malformedExistingJwksCannotCreateIntentOrReachConfigMapCas() throws Exception {
    Fixture fixture = fixture("{\"keys\":[] , \"unexpected\":true}", "41");
    fixture.stubCurrentGeneration();
    when(fixture.publicationRepository.readCurrentPublication(
            eq(accountBinding()), eq(trustFence(fixture.binding))))
        .thenReturn(Optional.empty());
    when(fixture.client.observe()).thenReturn(fixture.snapshot);

    assertThatThrownBy(fixture.service::prepublishCurrentGeneration)
        .isInstanceOf(AccountJwtJwksPrepublicationService.PublicationRejectedException.class)
        .hasMessageContaining("unsupported shape");

    verify(fixture.publicationRepository, never())
        .recordPrepublicationIntent(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(fixture.client, never()).publish(any(), anyMap());
  }

  @Test
  void existingIntentCannotOverwriteChangedSameNameConfigMap() throws Exception {
    Fixture fixture = fixture("{\"keys\":[]}", "42");
    fixture.stubCurrentGeneration();
    PrepublicationIntent intent =
        intent(
            fixture,
            "{\"keys\":[]}",
            marker(),
            "41",
            AccountJwtJwksPublicationRepository.snapshotDigest(fixture.snapshot.data()));
    when(fixture.publicationRepository.readCurrentPublication(
            eq(accountBinding()), eq(trustFence(fixture.binding))))
        .thenReturn(
            Optional.of(new PublicationEvidence(intent, Optional.empty(), Optional.empty())));
    when(fixture.client.observe()).thenReturn(fixture.snapshot);

    assertThatThrownBy(fixture.service::prepublishCurrentGeneration)
        .isInstanceOf(AccountJwtJwksPrepublicationService.PublicationRejectedException.class)
        .hasMessageContaining("changed after immutable intent");

    verify(fixture.client, never()).publish(any(), anyMap());
    verify(fixture.publicationRepository, never())
        .recordPublicationReceipt(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void exactRemoteApplyWithoutReceiptIsRecoveredAgainstOriginalResourceVersion() throws Exception {
    String desiredJwks = "{\"keys\":[]}";
    String marker = marker();
    Map<String, String> advancedData =
        Map.of(
            "jwks.json", desiredJwks,
            "jwt-generation.json", marker,
            "unrelated.txt", "preserved");
    Fixture fixture = fixture(desiredJwks, "42");
    fixture.stubCurrentGeneration();
    PrepublicationIntent intent =
        intent(
            fixture,
            desiredJwks,
            marker,
            "41",
            AccountJwtJwksPublicationRepository.snapshotDigest(
                Map.of("jwks.json", "{\"keys\":[]}", "unrelated.txt", "preserved")));
    when(fixture.publicationRepository.readCurrentPublication(
            eq(accountBinding()), eq(trustFence(fixture.binding))))
        .thenReturn(
            Optional.of(new PublicationEvidence(intent, Optional.empty(), Optional.empty())));
    when(fixture.client.observe())
        .thenReturn(new ConfigMapSnapshot(CONFIG_MAP_UID, "42", advancedData));
    Map<String, String> desiredData =
        Map.of("jwks.json", desiredJwks, "jwt-generation.json", marker);
    when(fixture.client.publish(
            eq(new ConfigMapSnapshot(CONFIG_MAP_UID, "41", advancedData)), eq(desiredData)))
        .thenReturn(
            new CasObservation(
                CONFIG_MAP_UID, "41", "42", desiredData, advancedData, Outcome.EXACT_REPLAY));
    when(fixture.publicationRepository.recordPublicationReceipt(
            eq(accountBinding()),
            eq(trustFence(fixture.binding)),
            eq(OPERATION_ID),
            eq(API_DIGEST),
            eq("api-r1"),
            eq(CONFIG_MAP_UID),
            eq("42"),
            eq(desiredJwks),
            eq(marker)))
        .thenReturn(
            PublicationReceipt.create(
                intent(
                    fixture,
                    desiredJwks,
                    marker,
                    "41",
                    AccountJwtJwksPublicationRepository.snapshotDigest(
                        Map.of("jwks.json", "{\"keys\":[]}", "unrelated.txt", "preserved"))),
                "42"));

    PublicationReceipt receipt = fixture.service.prepublishCurrentGeneration();

    assertThat(receipt.expectedResourceVersion()).isEqualTo("41");
    assertThat(receipt.observedResourceVersion()).isEqualTo("42");
    verify(fixture.client)
        .publish(eq(new ConfigMapSnapshot(CONFIG_MAP_UID, "41", advancedData)), eq(desiredData));
    verify(fixture.publicationRepository, never())
        .recordPrepublicationIntent(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(fixture.publicationRepository)
        .recordPublicationReceipt(
            eq(accountBinding()),
            eq(trustFence(fixture.binding)),
            eq(OPERATION_ID),
            eq(API_DIGEST),
            eq("api-r1"),
            eq(CONFIG_MAP_UID),
            eq("42"),
            eq(desiredJwks),
            eq(marker));
    verify(fixture.desiredRepository, never()).prepareCurrentGeneration(any(), any(), any(), any());
  }

  @Test
  void exactBytesAtUnadvancedOriginalResourceVersionDoNotCreateReceipt() throws Exception {
    String desiredJwks = "{\"keys\":[]}";
    String marker = marker();
    Map<String, String> unchangedData =
        Map.of(
            "jwks.json", desiredJwks,
            "jwt-generation.json", marker,
            "unrelated.txt", "preserved");
    Fixture fixture = fixture(desiredJwks, "41");
    fixture.stubCurrentGeneration();
    PrepublicationIntent intent =
        intent(
            fixture,
            desiredJwks,
            marker,
            "41",
            AccountJwtJwksPublicationRepository.snapshotDigest(fixture.snapshot.data()));
    when(fixture.publicationRepository.readCurrentPublication(
            eq(accountBinding()), eq(trustFence(fixture.binding))))
        .thenReturn(
            Optional.of(new PublicationEvidence(intent, Optional.empty(), Optional.empty())));
    when(fixture.client.observe())
        .thenReturn(new ConfigMapSnapshot(CONFIG_MAP_UID, "41", unchangedData));
    when(fixture.client.publish(any(), anyMap()))
        .thenThrow(new AccountJwtJwksConfigMapClient.CasConflictException(CONFIG_MAP_UID, "41"));

    assertThatThrownBy(fixture.service::prepublishCurrentGeneration)
        .isInstanceOf(AccountJwtJwksConfigMapClient.CasConflictException.class);

    verify(fixture.publicationRepository, never())
        .recordPrepublicationIntent(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(fixture.publicationRepository, never())
        .recordPublicationReceipt(any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(fixture.desiredRepository, never()).prepareCurrentGeneration(any(), any(), any(), any());
  }

  private static Fixture fixture(String jwks, String resourceVersion) throws Exception {
    Binding binding = trustBinding("trust-r1");
    GenerationKeys keys = generationKeys();
    AccountJwtSignerDesiredStateRepository desiredRepository =
        mock(AccountJwtSignerDesiredStateRepository.class);
    AccountJwtJwksPublicationRepository publicationRepository =
        mock(AccountJwtJwksPublicationRepository.class);
    AccountJwtSignerMaterializerTrustBinding trustProvider =
        mock(AccountJwtSignerMaterializerTrustBinding.class);
    when(trustProvider.current()).thenReturn(Optional.of(binding));
    AccountJwtJwksConfigMapClient client = mock(AccountJwtJwksConfigMapClient.class);
    when(client.identity()).thenReturn(apiIdentity());
    ConfigMapSnapshot snapshot =
        new ConfigMapSnapshot(
            CONFIG_MAP_UID,
            resourceVersion,
            Map.of("jwks.json", jwks, "unrelated.txt", "preserved"));
    RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    AccountJwtJwksPrepublicationService service =
        new AccountJwtJwksPrepublicationService(
            desiredRepository,
            publicationRepository,
            trustProvider,
            client,
            transactionManager,
            new AccountJwtJwksPrepublicationService.MountedProjection(
                java.nio.file.Path.of("/tmp/private"),
                java.nio.file.Path.of("pending.key"),
                java.nio.file.Path.of("/tmp/public"),
                java.nio.file.Path.of("jwks.json"),
                java.nio.file.Path.of("jwt-generation.json")));
    return new Fixture(
        binding,
        desiredRepository,
        publicationRepository,
        trustProvider,
        client,
        transactionManager,
        service,
        snapshot,
        keys.result(),
        keys.request());
  }

  private static void stubCurrentGeneration(Fixture fixture) {
    when(fixture.desiredRepository.readCurrentGenerationResult(
            eq(accountBinding()), eq(trustFence(fixture.binding))))
        .thenReturn(fixture.result);
    when(fixture.desiredRepository.readCurrentGenerationRequest(
            eq(accountBinding()), eq(trustFence(fixture.binding))))
        .thenReturn(fixture.request);
  }

  private static PrepublicationIntent intent(
      Fixture fixture,
      String jwks,
      String marker,
      String expectedResourceVersion,
      String expectedSnapshotDigest)
      throws Exception {
    return PrepublicationIntent.create(
        OPERATION_ID,
        accountBinding(),
        fixture.result.operationDigest(),
        fixture.result.generationRequestDigest(),
        fixture.result.receiptDigest(),
        fixture.result.desiredStateVersion(),
        fixture.result.trustFence(),
        API_DIGEST,
        "api-r1",
        "jwt-jwks",
        CONFIG_MAP_UID,
        expectedResourceVersion,
        expectedSnapshotDigest,
        fixture.result.targetGeneration(),
        fixture.result.targetKid(),
        fixture.result.publicKeyFingerprint(),
        fixture.request.expectedActive(),
        fixture.request.expectedPublishedActive(),
        jwks,
        marker,
        AccountJwtJwksPublicationRepository.publicDataDigest(jwks, marker),
        sha256(marker.getBytes(StandardCharsets.UTF_8)));
  }

  private static String marker() {
    return "{\"phase\":\"PREPUBLISHED\"}";
  }

  private static Binding trustBinding(String revision) {
    String peerPin = "e".repeat(64);
    String digest =
        AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
            revision,
            "prod",
            "prod-cluster-1",
            "firemud-prod",
            CLUSTER_UID,
            NAMESPACE_UID,
            MATERIALIZER_URI,
            List.of(peerPin));
    return new Binding(
        "prod",
        "prod-cluster-1",
        "firemud-prod",
        CLUSTER_UID,
        NAMESPACE_UID,
        MATERIALIZER_URI,
        List.of(peerPin),
        revision,
        digest);
  }

  private static BindingIdentity apiIdentity() {
    return new BindingIdentity(
        API_DIGEST,
        "api-r1",
        "prod",
        "prod-cluster-1",
        "firemud-prod",
        CLUSTER_UID,
        NAMESPACE_UID,
        "https://api.firemud.test",
        "api.firemud.test",
        "f".repeat(64),
        "system:serviceaccount:firemud-prod:account-service");
  }

  private static AccountJwtSignerDesiredStateRepository.Binding accountBinding() {
    return new AccountJwtSignerDesiredStateRepository.Binding(
        "prod",
        "prod-cluster-1",
        "firemud-prod",
        CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
  }

  private static TrustFence trustFence(Binding binding) {
    return new TrustFence(
        binding.expectedClusterIncarnationUid(),
        binding.expectedNamespaceUid(),
        binding.bindingDigest(),
        binding.configRevision());
  }

  private static GenerationKeys generationKeys() throws Exception {
    UUID operationId = OPERATION_ID;
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    RSAPublicKey publicKey = (RSAPublicKey) generator.generateKeyPair().getPublic();
    String modulus = encodeUnsigned(publicKey.getModulus());
    String exponent = encodeUnsigned(publicKey.getPublicExponent());
    String kid = "jwt-1-444444444444";
    Map<String, Object> jwkMap = new LinkedHashMap<>();
    jwkMap.put("kty", "RSA");
    jwkMap.put("use", "sig");
    jwkMap.put("alg", "RS256");
    jwkMap.put("kid", kid);
    jwkMap.put("key_ops", List.of("verify"));
    jwkMap.put("n", modulus);
    jwkMap.put("e", exponent);
    String jwk = canonicalJson(jwkMap);
    Map<String, Object> thumbprint = Map.of("e", exponent, "kty", "RSA", "n", modulus);
    String fingerprint =
        sha256(Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(thumbprint)));
    Binding binding = trustBinding("trust-r1");
    AccountJwtSignerDesiredStateRepository.Binding accountBinding = accountBinding();
    TrustFence trustFence = trustFence(binding);
    GenerationResult result =
        new GenerationResult(
            operationId,
            accountBinding,
            OPERATION_DIGEST,
            REQUEST_DIGEST,
            2L,
            trustFence,
            "jwt-signing-keys",
            "55555555-5555-4555-8555-555555555555",
            "12",
            "13",
            "1",
            kid,
            "RS256",
            fingerprint,
            jwk,
            RECEIPT_DIGEST);
    GenerationRequest request =
        new GenerationRequest(
            GenerationPhase.GENERATION_RECORDED,
            operationId,
            OPERATION_DIGEST,
            REQUEST_DIGEST,
            2L,
            accountBinding,
            trustFence,
            "jwt-signing-keys",
            "1",
            kid,
            "RS256",
            "MATERIALIZE_PENDING",
            List.of("pending"),
            Optional.empty(),
            Optional.empty(),
            "55555555-5555-4555-8555-555555555555",
            "12",
            RECEIPT_DIGEST,
            fingerprint,
            jwk,
            "13");
    return new GenerationKeys(result, request);
  }

  private static String encodeUnsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    int offset = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(java.util.Arrays.copyOfRange(bytes, offset, bytes.length));
  }

  private static String canonicalJson(Object value) throws Exception {
    return new String(
        Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)),
        StandardCharsets.UTF_8);
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private record GenerationKeys(GenerationResult result, GenerationRequest request) {}

  private record Fixture(
      Binding binding,
      AccountJwtSignerDesiredStateRepository desiredRepository,
      AccountJwtJwksPublicationRepository publicationRepository,
      AccountJwtSignerMaterializerTrustBinding trustBindingProvider,
      AccountJwtJwksConfigMapClient client,
      RecordingTransactionManager transactionManager,
      AccountJwtJwksPrepublicationService service,
      ConfigMapSnapshot snapshot,
      GenerationResult result,
      GenerationRequest request) {
    private void stubCurrentGeneration() {
      AccountJwtJwksPrepublicationServiceTest.stubCurrentGeneration(this);
    }
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private int commits;
    private int rollbacks;

    @Override
    public TransactionStatus getTransaction(
        org.springframework.transaction.TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      commits++;
    }

    @Override
    public void rollback(TransactionStatus status) {
      rollbacks++;
    }
  }
}
