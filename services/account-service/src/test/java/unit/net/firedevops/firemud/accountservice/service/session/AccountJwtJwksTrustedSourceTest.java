package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveSigner;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CommittedSignerEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.DesiredState;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.EnrollmentIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationPhase;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PromotionOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.BindingIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.ConfigMapSnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksTrustedSource;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AccountJwtJwksTrustedSourceTest {
  private static final String ENVIRONMENT = "test";
  private static final String CLUSTER = "cluster-test";
  private static final String NAMESPACE = "firemud-test";
  private static final String CLUSTER_UID = "11111111-1111-4111-8111-111111111111";
  private static final String NAMESPACE_UID = "22222222-2222-4222-8222-222222222222";
  private static final String CONFIG_MAP_UID = "33333333-3333-4333-8333-333333333333";
  private static final String API_BINDING_DIGEST = "a".repeat(64);
  private static final String API_CA_DIGEST = "b".repeat(64);
  private static final UUID INITIAL_OPERATION_ID =
      UUID.fromString("55555555-5555-4555-8555-000000000001");
  private static final UUID NEXT_OPERATION_ID =
      UUID.fromString("55555555-5555-4555-8555-000000000002");
  private static final String GENERATION_OPERATION_DIGEST = "1".repeat(64);
  private static final String GENERATION_REQUEST_DIGEST = "2".repeat(64);
  private static final String GENERATION_RECEIPT_DIGEST = "3".repeat(64);
  private static final String JWK_1 =
      "{\"alg\":\"RS256\",\"e\":\"AQAB\",\"key_ops\":[\"verify\"],\"kid\":\"key_1\",\"kty\":\"RSA\",\"n\":\"AQID\",\"use\":\"sig\"}";
  private static final String JWK_2 =
      "{\"alg\":\"RS256\",\"e\":\"AQAB\",\"key_ops\":[\"verify\"],\"kid\":\"key_2\",\"kty\":\"RSA\",\"n\":\"BAUG\",\"use\":\"sig\"}";
  private static final String JWKS_1 = "{\"keys\":[" + JWK_1 + "]}";
  private static final String JWKS_1_AND_2 = "{\"keys\":[" + JWK_1 + "," + JWK_2 + "]}";

  @Test
  void derivesPinAndReturnsOnlyExactReceiptBackedPublicBytes() {
    Fixture fixture = new Fixture();

    SourceIdentity identity = fixture.source.sourceIdentity();
    PublicJwksSnapshot snapshot = fixture.source.load();

    assertThat(identity.environmentId()).isEqualTo(ENVIRONMENT);
    assertThat(identity.clusterId()).isEqualTo(CLUSTER);
    assertThat(identity.clusterIncarnationUid()).isEqualTo(CLUSTER_UID);
    assertThat(identity.namespace()).isEqualTo(NAMESPACE);
    assertThat(identity.namespaceUid()).isEqualTo(NAMESPACE_UID);
    assertThat(identity.configMapUid()).isEqualTo(CONFIG_MAP_UID);
    assertThat(identity.bindingRevision()).isEqualTo("api-r1");
    assertThat(identity.apiServerOrigin()).isEqualTo("https://kubernetes.test:6443");
    assertThat(identity.servingCaSha256()).isEqualTo(API_CA_DIGEST);
    assertThat(snapshot.sourceIdentity()).isEqualTo(identity);
    assertThat(snapshot.jwksBytes()).containsExactly(JWKS_1.getBytes(StandardCharsets.UTF_8));
    verify(fixture.desiredStateRepository, never())
        .readCurrentCommittedSigner(any(), any(), any(), any());
    verify(fixture.configMapClient, never()).publish(any(), anyMap());
  }

  @Test
  void allowsNewAccountPublicationAtSamePinnedResourceWithoutChangingSourceIdentity() {
    Fixture fixture = new Fixture();
    fixture.useCommittedState();
    SourceIdentity identity = fixture.source.sourceIdentity();
    String nextJwks = JWKS_1_AND_2;
    String nextMarker =
        fixture.marker(
            NEXT_OPERATION_ID,
            "2",
            "key_2",
            fingerprint(JWK_2),
            Optional.of(new ActiveSigner("1", "key_1")));
    PublicationEvidence nextPublication =
        fixture.publication(
            nextJwks,
            nextMarker,
            "42",
            "43",
            "2",
            "key_2",
            Optional.of(new ActiveSigner("1", "key_1")));
    Map<String, String> nextData =
        Map.of(
            AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
            nextJwks,
            AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
            nextMarker,
            "operator.note",
            "preserved");
    when(fixture.publicationRepository.readCurrentPublication(
            eq(fixture.binding.accountBinding()), eq(fixture.trustFence)))
        .thenReturn(Optional.of(nextPublication), Optional.of(nextPublication));
    when(fixture.configMapClient.observe())
        .thenReturn(new ConfigMapSnapshot(CONFIG_MAP_UID, "43", nextData));

    PublicJwksSnapshot snapshot = fixture.source.load();

    assertThat(snapshot.sourceIdentity()).isEqualTo(identity);
    assertThat(snapshot.jwksBytes()).containsExactly(nextJwks.getBytes(StandardCharsets.UTF_8));
    assertThat(new String(snapshot.jwksBytes(), StandardCharsets.UTF_8)).contains(JWK_1, JWK_2);
  }

  @Test
  void historicalKeyFingerprintRequiresTheCurrentOwnerProvedSetToRetainItsExactIdentity() {
    Fixture missingKey = new Fixture();
    missingKey.useCommittedState();
    missingKey.replaceNextJwks("{\"keys\":[" + JWK_2 + "]}");
    assertThatThrownBy(missingKey.source::load)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account trusted public JWKS source was rejected");

    Fixture substitutedKey = new Fixture();
    substitutedKey.useCommittedState();
    String substitutedJwk = JWK_1.replace("\"n\":\"AQID\"", "\"n\":\"BAUG\"");
    substitutedKey.replaceNextJwks("{\"keys\":[" + substitutedJwk + "," + JWK_2 + "]}");
    assertThatThrownBy(substitutedKey.source::load)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account trusted public JWKS source was rejected");
  }

  @Test
  void deniesSameNamedDestinationWithDifferentClusterIncarnation() {
    Fixture fixture = new Fixture();
    Binding wrongIncarnation = binding("44444444-4444-4444-8444-444444444444", NAMESPACE_UID);
    when(fixture.materializerTrust.current()).thenReturn(Optional.of(wrongIncarnation));

    assertThatThrownBy(fixture.source::sourceIdentity)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account trusted public JWKS source was rejected");
    verify(fixture.configMapClient, never()).observe();
  }

  @Test
  void deniesUnexpectedAuthenticatedApiPrincipal() {
    Fixture fixture = new Fixture();
    BindingIdentity wrongPrincipal =
        new BindingIdentity(
            fixture.apiIdentity.bindingDigest(),
            fixture.apiIdentity.configRevision(),
            fixture.apiIdentity.environmentId(),
            fixture.apiIdentity.clusterId(),
            fixture.apiIdentity.namespace(),
            fixture.apiIdentity.expectedClusterIncarnationUid(),
            fixture.apiIdentity.expectedNamespaceUid(),
            fixture.apiIdentity.apiServerUrl(),
            fixture.apiIdentity.tlsServerName(),
            fixture.apiIdentity.servingCaSha256(),
            "system:serviceaccount:" + NAMESPACE + ":another-service");
    when(fixture.configMapClient.identity()).thenReturn(wrongPrincipal);

    assertThatThrownBy(fixture.source::sourceIdentity)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account trusted public JWKS source was rejected");
    verify(fixture.publicationRepository, never()).readCurrentPublication(any(), any());
    verify(fixture.configMapClient, never()).observe();
  }

  @Test
  void rejectsRecreatedSameNameConfigMapEvenWithMatchingPublicData() {
    Fixture fixture = new Fixture();
    when(fixture.configMapClient.observe())
        .thenReturn(
            new ConfigMapSnapshot(
                "44444444-4444-4444-8444-444444444444", "42", fixture.snapshot.data()));

    assertThatThrownBy(fixture.source::sourceIdentity)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account trusted public JWKS source was rejected");
  }

  @Test
  void treatsUnreceiptedOrAmbiguousOwnerPublicationAsUnavailableNotAsAKeySet() {
    Fixture fixture = new Fixture();
    PublicationEvidence unreceipted =
        new PublicationEvidence(fixture.publication.intent(), Optional.empty(), Optional.empty());
    when(fixture.publicationRepository.readCurrentPublication(
            eq(fixture.binding.accountBinding()), eq(fixture.trustFence)))
        .thenReturn(Optional.of(unreceipted));

    assertThatThrownBy(fixture.source::sourceIdentity)
        .isInstanceOf(SourceUnavailableException.class)
        .hasNoCause();
    verify(fixture.configMapClient, never()).observe();
  }

  @Test
  void rejectsTrustChangeAcrossTheAuthenticatedRead() {
    Fixture fixture = new Fixture();
    Binding changedBinding = binding(CLUSTER_UID, NAMESPACE_UID, "materializer-r2");
    when(fixture.materializerTrust.current())
        .thenReturn(Optional.of(fixture.binding), Optional.of(changedBinding));

    assertThatThrownBy(fixture.source::sourceIdentity)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account trusted public JWKS source was rejected");
  }

  @Test
  void rejectsReadbackThatDoesNotMatchReceiptResourceVersionOrMarker() {
    Fixture fixture = new Fixture();
    Map<String, String> changedData =
        Map.of(
            AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
            JWKS_1,
            AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
            "{\"phase\":\"OTHER\"}");
    when(fixture.configMapClient.observe())
        .thenReturn(new ConfigMapSnapshot(CONFIG_MAP_UID, "43", changedData));

    assertThatThrownBy(fixture.source::sourceIdentity)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account trusted public JWKS source was rejected");
  }

  @Test
  void ownerPublicationChangingDuringReadProducesNoSnapshot() {
    Fixture fixture = new Fixture();
    when(fixture.publicationRepository.readCurrentPublication(
            eq(fixture.binding.accountBinding()), eq(fixture.trustFence)))
        .thenReturn(Optional.of(fixture.publication), Optional.empty());

    assertThatThrownBy(fixture.source::sourceIdentity)
        .isInstanceOf(SourceUnavailableException.class)
        .hasNoCause();
  }

  @Test
  void deniesInitialPrepublicationWhenPublicMarkerClaimsAnActiveFence() {
    Fixture fixture = new Fixture();
    String activeMarker =
        fixture.marker(
            INITIAL_OPERATION_ID,
            "1",
            "key_1",
            fingerprint(JWK_1),
            Optional.of(new ActiveSigner("1", "key_1")));
    PublicationEvidence conflicting =
        fixture.publication(JWKS_1, activeMarker, "41", "42", "1", "key_1", Optional.empty());
    when(fixture.publicationRepository.readCurrentPublication(
            eq(fixture.binding.accountBinding()), eq(fixture.trustFence)))
        .thenReturn(Optional.of(conflicting));

    assertThatThrownBy(fixture.source::sourceIdentity)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account trusted public JWKS source was rejected");
    verify(fixture.configMapClient, never()).observe();
  }

  @Test
  void deniesInitialPrepublicationWhenConfigMapResourceVersionIsStale() {
    Fixture fixture = new Fixture();
    when(fixture.configMapClient.observe())
        .thenReturn(new ConfigMapSnapshot(CONFIG_MAP_UID, "43", fixture.snapshot.data()));

    assertThatThrownBy(fixture.source::sourceIdentity)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account trusted public JWKS source was rejected");
  }

  private static Binding binding(String clusterUid, String namespaceUid) {
    return binding(clusterUid, namespaceUid, "materializer-r1");
  }

  private static Binding binding(String clusterUid, String namespaceUid, String revision) {
    String expectedPeer = "spiffe://firemud/ns/" + NAMESPACE + "/sa/jwt-signer-materializer";
    List<String> pins = List.of("c".repeat(64));
    String bindingDigest =
        AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
            revision,
            ENVIRONMENT,
            CLUSTER,
            NAMESPACE,
            clusterUid,
            namespaceUid,
            expectedPeer,
            pins);
    return new Binding(
        ENVIRONMENT,
        CLUSTER,
        NAMESPACE,
        clusterUid,
        namespaceUid,
        expectedPeer,
        pins,
        revision,
        bindingDigest);
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception unavailable) {
      throw new AssertionError(unavailable);
    }
  }

  private static final class Fixture {
    private final AccountJwtJwksConfigMapClient configMapClient =
        mock(AccountJwtJwksConfigMapClient.class);
    private final AccountJwtSignerMaterializerTrustBinding materializerTrust =
        mock(AccountJwtSignerMaterializerTrustBinding.class);
    private final AccountJwtSignerDesiredStateRepository desiredStateRepository =
        mock(AccountJwtSignerDesiredStateRepository.class);
    private final AccountJwtJwksPublicationRepository publicationRepository =
        mock(AccountJwtJwksPublicationRepository.class);
    private final PlatformTransactionManager transactionManager =
        mock(PlatformTransactionManager.class);
    private final Binding binding = binding(CLUSTER_UID, NAMESPACE_UID);
    private final TrustFence trustFence =
        new TrustFence(
            CLUSTER_UID, NAMESPACE_UID, binding.bindingDigest(), binding.configRevision());
    private final AccountJwtSignerDesiredStateRepository.Binding accountBinding =
        binding.accountBinding();
    private final BindingIdentity apiIdentity =
        new BindingIdentity(
            API_BINDING_DIGEST,
            "api-r1",
            ENVIRONMENT,
            CLUSTER,
            NAMESPACE,
            CLUSTER_UID,
            NAMESPACE_UID,
            "https://kubernetes.test:6443",
            "kubernetes.test",
            API_CA_DIGEST,
            "system:serviceaccount:" + NAMESPACE + ":account-service");
    private final String publicFingerprint = fingerprint(JWK_1);
    private final EnrollmentIdentity enrollmentIdentity;
    private final GenerationRequest initialRequest;
    private final GenerationResult initialResult;
    private final DesiredState initialDesiredState;
    private final String initialMarker;
    private final PublicationEvidence publication;
    private final ConfigMapSnapshot snapshot;
    private final AccountJwtJwksTrustedSource source;

    private Fixture() {
      enrollmentIdentity =
          new EnrollmentIdentity(
              CLUSTER_UID,
              NAMESPACE_UID,
              binding.bindingDigest(),
              binding.configRevision(),
              API_BINDING_DIGEST,
              "api-r1",
              CONFIG_MAP_UID,
              "40",
              "6".repeat(64));
      initialMarker =
          marker(INITIAL_OPERATION_ID, "1", "key_1", publicFingerprint, Optional.empty());
      initialRequest = mock(GenerationRequest.class);
      when(initialRequest.phase()).thenReturn(GenerationPhase.GENERATION_RECORDED);
      when(initialRequest.operationId()).thenReturn(INITIAL_OPERATION_ID);
      when(initialRequest.operationDigest()).thenReturn(GENERATION_OPERATION_DIGEST);
      when(initialRequest.generationRequestDigest()).thenReturn(GENERATION_REQUEST_DIGEST);
      when(initialRequest.generationReceiptDigest()).thenReturn(GENERATION_RECEIPT_DIGEST);
      when(initialRequest.desiredStateVersion()).thenReturn(2L);
      when(initialRequest.binding()).thenReturn(accountBinding);
      when(initialRequest.trustFence()).thenReturn(trustFence);
      when(initialRequest.targetGeneration()).thenReturn("1");
      when(initialRequest.targetKid()).thenReturn("key_1");
      when(initialRequest.publicKeyFingerprint()).thenReturn(publicFingerprint);
      when(initialRequest.publicJwkJson()).thenReturn(JWK_1);
      when(initialRequest.expectedActive()).thenReturn(Optional.empty());
      when(initialRequest.expectedPublishedActive()).thenReturn(Optional.empty());

      initialResult = mock(GenerationResult.class);
      when(initialResult.operationId()).thenReturn(INITIAL_OPERATION_ID);
      when(initialResult.binding()).thenReturn(accountBinding);
      when(initialResult.operationDigest()).thenReturn(GENERATION_OPERATION_DIGEST);
      when(initialResult.generationRequestDigest()).thenReturn(GENERATION_REQUEST_DIGEST);
      when(initialResult.desiredStateVersion()).thenReturn(2L);
      when(initialResult.trustFence()).thenReturn(trustFence);
      when(initialResult.targetGeneration()).thenReturn("1");
      when(initialResult.targetKid()).thenReturn("key_1");
      when(initialResult.targetAlgorithm()).thenReturn("RS256");
      when(initialResult.publicKeyFingerprint()).thenReturn(publicFingerprint);
      when(initialResult.publicJwkJson()).thenReturn(JWK_1);
      when(initialResult.receiptDigest()).thenReturn(GENERATION_RECEIPT_DIGEST);

      initialDesiredState =
          new DesiredState(
              accountBinding,
              2,
              Optional.empty(),
              Optional.empty(),
              Optional.of(INITIAL_OPERATION_ID),
              Optional.empty(),
              Optional.of(enrollmentIdentity));
      publication = publication(JWKS_1, initialMarker, "41", "42", "1", "key_1", Optional.empty());
      snapshot =
          new ConfigMapSnapshot(
              CONFIG_MAP_UID,
              "42",
              Map.of(
                  AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
                  JWKS_1,
                  AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
                  initialMarker,
                  "operator.note",
                  "retained"));

      when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
      when(materializerTrust.current()).thenReturn(Optional.of(binding));
      when(configMapClient.identity()).thenReturn(apiIdentity);
      when(configMapClient.observe()).thenReturn(snapshot);
      when(desiredStateRepository.readEnrollmentState(eq(accountBinding)))
          .thenReturn(Optional.of(initialDesiredState));
      when(desiredStateRepository.readCurrentGenerationRequest(eq(accountBinding), eq(trustFence)))
          .thenReturn(initialRequest);
      when(desiredStateRepository.readCurrentGenerationResult(eq(accountBinding), eq(trustFence)))
          .thenReturn(initialResult);
      when(publicationRepository.readCurrentPublication(eq(accountBinding), eq(trustFence)))
          .thenReturn(Optional.of(publication));
      source =
          new AccountJwtJwksTrustedSource(
              configMapClient,
              materializerTrust,
              desiredStateRepository,
              publicationRepository,
              transactionManager);
    }

    private void useCommittedState() {
      ActiveSigner active = new ActiveSigner("1", "key_1");
      DesiredState committedState =
          new DesiredState(
              accountBinding,
              3,
              Optional.of(active),
              Optional.of(active),
              Optional.of(NEXT_OPERATION_ID),
              Optional.empty(),
              Optional.of(enrollmentIdentity));
      CommittedSignerEvidence committed = mock(CommittedSignerEvidence.class);
      PromotionOperationEvidence promotion = mock(PromotionOperationEvidence.class);
      AccountJwtSignerDesiredStateRepository.ActiveJwksPromotionReceipt receipt =
          mock(AccountJwtSignerDesiredStateRepository.ActiveJwksPromotionReceipt.class);
      String activeMarker = activeMarker(active);
      String activeDigest =
          AccountJwtJwksPublicationRepository.publicDataDigest(JWKS_1, activeMarker);
      when(committed.promotion()).thenReturn(promotion);
      when(committed.desiredState()).thenReturn(committedState);
      when(committed.publicReceipt()).thenReturn(receipt);
      when(promotion.targetGeneration()).thenReturn("1");
      when(promotion.targetKid()).thenReturn("key_1");
      when(promotion.targetPublicKeyFingerprint()).thenReturn(publicFingerprint);
      when(promotion.publicConfigMapUid()).thenReturn(CONFIG_MAP_UID);
      when(promotion.expectedPublicJwksJson()).thenReturn(JWKS_1);
      when(promotion.expectedActiveMarkerJson()).thenReturn(activeMarker);
      when(promotion.activeJwksObservedResourceVersion()).thenReturn(Optional.of("42"));
      when(promotion.activeJwksPublicDataDigest()).thenReturn(Optional.of(activeDigest));
      when(receipt.configMapUid()).thenReturn(CONFIG_MAP_UID);
      when(receipt.observedResourceVersion()).thenReturn("42");
      when(receipt.publicDataDigest()).thenReturn(activeDigest);
      when(desiredStateRepository.readEnrollmentState(eq(accountBinding)))
          .thenReturn(Optional.of(committedState));
      when(desiredStateRepository.readCurrentCommittedSigner(
              eq(accountBinding), eq(trustFence), eq(API_BINDING_DIGEST), eq("api-r1")))
          .thenReturn(Optional.of(committed));

      GenerationRequest nextRequest = mock(GenerationRequest.class);
      when(nextRequest.phase()).thenReturn(GenerationPhase.GENERATION_RECORDED);
      when(nextRequest.operationId()).thenReturn(NEXT_OPERATION_ID);
      when(nextRequest.operationDigest()).thenReturn(GENERATION_OPERATION_DIGEST);
      when(nextRequest.generationRequestDigest()).thenReturn(GENERATION_REQUEST_DIGEST);
      when(nextRequest.generationReceiptDigest()).thenReturn(GENERATION_RECEIPT_DIGEST);
      when(nextRequest.desiredStateVersion()).thenReturn(2L);
      when(nextRequest.targetGeneration()).thenReturn("2");
      when(nextRequest.targetKid()).thenReturn("key_2");
      when(nextRequest.publicKeyFingerprint()).thenReturn(fingerprint(JWK_2));
      when(nextRequest.expectedActive()).thenReturn(Optional.of(active));
      when(nextRequest.expectedPublishedActive()).thenReturn(Optional.of(active));
      when(desiredStateRepository.readCurrentGenerationRequest(eq(accountBinding), eq(trustFence)))
          .thenReturn(nextRequest);

      String nextMarker =
          marker(NEXT_OPERATION_ID, "2", "key_2", fingerprint(JWK_2), Optional.of(active));
      PublicationEvidence nextPublication =
          publication(JWKS_1_AND_2, nextMarker, "42", "43", "2", "key_2", Optional.of(active));
      when(publicationRepository.readCurrentPublication(eq(accountBinding), eq(trustFence)))
          .thenReturn(Optional.of(nextPublication));
      when(configMapClient.observe())
          .thenReturn(
              new ConfigMapSnapshot(
                  CONFIG_MAP_UID,
                  "43",
                  Map.of(
                      AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
                      JWKS_1_AND_2,
                      AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
                      nextMarker,
                      "operator.note",
                      "preserved")));
    }

    private void replaceNextJwks(String jwks) {
      String nextMarker =
          marker(
              NEXT_OPERATION_ID,
              "2",
              "key_2",
              fingerprint(JWK_2),
              Optional.of(new ActiveSigner("1", "key_1")));
      PublicationEvidence nextPublication =
          publication(
              jwks,
              nextMarker,
              "42",
              "43",
              "2",
              "key_2",
              Optional.of(new ActiveSigner("1", "key_1")));
      when(publicationRepository.readCurrentPublication(eq(accountBinding), eq(trustFence)))
          .thenReturn(Optional.of(nextPublication));
      when(configMapClient.observe())
          .thenReturn(
              new ConfigMapSnapshot(
                  CONFIG_MAP_UID,
                  "43",
                  Map.of(
                      AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
                      jwks,
                      AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
                      nextMarker,
                      "operator.note",
                      "preserved")));
    }

    private PublicationEvidence publication(
        String jwks,
        String marker,
        String expectedResourceVersion,
        String observedResourceVersion,
        String generation,
        String kid,
        Optional<ActiveSigner> expectedActive) {
      String publicDataDigest = AccountJwtJwksPublicationRepository.publicDataDigest(jwks, marker);
      String keyFingerprint = "key_1".equals(kid) ? fingerprint(JWK_1) : fingerprint(JWK_2);
      UUID operationId = "1".equals(generation) ? INITIAL_OPERATION_ID : NEXT_OPERATION_ID;
      PrepublicationIntent intent =
          PrepublicationIntent.create(
              operationId,
              accountBinding,
              GENERATION_OPERATION_DIGEST,
              GENERATION_REQUEST_DIGEST,
              GENERATION_RECEIPT_DIGEST,
              2L,
              trustFence,
              API_BINDING_DIGEST,
              "api-r1",
              AccountJwtJwksPublicationRepository.PUBLIC_JWKS_NAME,
              CONFIG_MAP_UID,
              expectedResourceVersion,
              "4".repeat(64),
              generation,
              kid,
              keyFingerprint,
              expectedActive,
              expectedActive,
              jwks,
              marker,
              publicDataDigest,
              sha256(marker));
      PublicationReceipt receipt = PublicationReceipt.create(intent, observedResourceVersion);
      return new PublicationEvidence(intent, Optional.of(receipt), Optional.empty());
    }

    private String marker(
        UUID operationId,
        String generation,
        String kid,
        String keyFingerprint,
        Optional<ActiveSigner> active) {
      String activeJson = activeMarker(active);
      return "{\"schemaVersion\":1,\"phase\":\"PREPUBLISHED\",\"operationId\":\""
          + operationId
          + "\",\"operationDigest\":\""
          + GENERATION_OPERATION_DIGEST
          + "\",\"generationRequestDigest\":\""
          + GENERATION_REQUEST_DIGEST
          + "\",\"generationReceiptDigest\":\""
          + GENERATION_RECEIPT_DIGEST
          + "\",\"binding\":{\"environmentId\":\""
          + ENVIRONMENT
          + "\",\"clusterId\":\""
          + CLUSTER
          + "\",\"namespace\":\""
          + NAMESPACE
          + "\",\"expectedClusterIncarnationUid\":\""
          + CLUSTER_UID
          + "\",\"expectedNamespaceUid\":\""
          + NAMESPACE_UID
          + "\",\"trustBindingDigest\":\""
          + binding.bindingDigest()
          + "\",\"trustConfigRevision\":\""
          + binding.configRevision()
          + "\",\"apiBindingDigest\":\""
          + API_BINDING_DIGEST
          + "\",\"apiConfigRevision\":\"api-r1\"},\"publicConfigMap\":{\"name\":\"jwt-jwks\"},\"expectedDurableActive\":"
          + activeJson
          + ",\"expectedPublishedActive\":"
          + activeJson
          + ",\"pending\":{\"generation\":\""
          + generation
          + "\",\"kid\":\""
          + kid
          + "\",\"algorithm\":\"RS256\",\"publicKeyFingerprint\":\""
          + keyFingerprint
          + "\"}}";
    }
  }

  private static String activeMarker(ActiveSigner active) {
    return "{\"generation\":\""
        + active.generation()
        + "\",\"kid\":\""
        + active.kid()
        + "\",\"present\":true}";
  }

  private static String activeMarker(Optional<ActiveSigner> active) {
    return active.map(AccountJwtJwksTrustedSourceTest::activeMarker).orElse("{\"present\":false}");
  }

  private static String fingerprint(String jwk) {
    String modulus = jwk.contains("key_1") ? "AQID" : "BAUG";
    String preimage = "{\"e\":\"AQAB\",\"kty\":\"RSA\",\"n\":\"" + modulus + "\"}";
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(Rfc8785CanonicalJson.canonicalizeUtf8(preimage)));
    } catch (Exception unavailable) {
      throw new AssertionError(unavailable);
    }
  }
}
