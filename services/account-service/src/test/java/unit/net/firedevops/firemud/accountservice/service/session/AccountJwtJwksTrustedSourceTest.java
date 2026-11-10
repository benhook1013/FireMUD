package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.GetCurrentAccountPublicJwksResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ApiCall;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ApiResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ParsedBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.accountservice.config.AccountPublicJwksReadWorkloadGuard;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.MountObservation;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.PrepublicationIntent;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository.PromotionPublicationEvidence;
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
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PreparedGenerationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PrivatePromotionReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PromotionOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.impl.AccountPublicJwksReadGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.BindingIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksConfigMapClient.ConfigMapSnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksTrustedSource;
import net.firedevops.firemud.accountservice.service.session.AccountPublicJwksReadService;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceUnavailableException;
import net.firedevops.firemud.common.security.AccountPublicJwksReadCodec;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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
  private static final String JWK_1 = publicTestJwk("key_1");
  private static final String JWK_2 = publicTestJwk("key_2");
  private static final Map<String, String> INITIAL_PRIOR_DATA =
      Map.of("jwks.json", "{\"keys\":[]}", "operator.note", "retained");
  private static final String JWKS_1 = "{\"keys\":[" + JWK_1 + "]}";
  private static final String JWKS_1_AND_2 = "{\"keys\":[" + JWK_1 + "," + JWK_2 + "]}";

  @Test
  void sameUidUnownedPublicKeyRemovalIsTerminalAndCannotRepopulateWarmCache() {
    Fixture fixture = new Fixture();
    fixture.useCommittedState();
    var pin = fixture.source.sourceIdentity();
    var cache =
        new net.firedevops.firemud.common.security.AccountPublicJwksCache(
            fixture.source,
            pin,
            java.time.Clock.fixed(
                java.time.Instant.parse("2026-11-10T00:00:00Z"), java.time.ZoneOffset.UTC),
            java.time.Duration.ofSeconds(10));
    cache.refresh();
    assertThat(cache.keyFor("key_1")).isNotNull();
    assertThat(cache.keyFor("key_2")).isNotNull();
    var original =
        new ConfigMapSnapshot(
            CONFIG_MAP_UID,
            "43",
            Map.of(
                AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
                JWKS_1_AND_2,
                AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
                fixture.nextPublication.intent().generationMarkerJson()));
    var pruned = new java.util.LinkedHashMap<>(original.data());
    pruned.put(AccountJwtJwksPublicationRepository.JWKS_DATA_KEY, "{\"keys\":[" + JWK_2 + "]}");
    when(fixture.configMapClient.observe())
        .thenReturn(new ConfigMapSnapshot(CONFIG_MAP_UID, "44", pruned));
    // Same UID and a valid retained key profile cannot replace exact Account owner evidence.
    assertRejectedPublicRead(fixture);
    assertThatThrownBy(cache::refresh)
        .isInstanceOf(
            net.firedevops.firemud.common.security.AccountPublicJwksCache.InvalidJwksException
                .class);
    for (String kid : List.of("key_1", "key_2")) {
      assertThatThrownBy(() -> cache.keyFor(kid))
          .isInstanceOf(
              net.firedevops.firemud.common.security.AccountPublicJwksCache.InvalidJwksException
                  .class);
    }
    when(fixture.configMapClient.observe()).thenReturn(original);
    assertThat(fixture.source.currentPublicRead().jwksBytes())
        .containsExactly(JWKS_1_AND_2.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(cache::refresh)
        .isInstanceOf(
            net.firedevops.firemud.common.security.AccountPublicJwksCache.InvalidJwksException
                .class);
    assertThatThrownBy(() -> cache.keyFor("key_1"))
        .isInstanceOf(
            net.firedevops.firemud.common.security.AccountPublicJwksCache.InvalidJwksException
                .class);
  }

  private static String publicTestJwk(String kid) {
    try {
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(3072);
      var key = (java.security.interfaces.RSAPublicKey) generator.generateKeyPair().getPublic();
      byte[] modulus = key.getModulus().toByteArray();
      if (modulus[0] == 0) modulus = java.util.Arrays.copyOfRange(modulus, 1, modulus.length);
      var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
      return "{\"alg\":\"RS256\",\"e\":\""
          + encoder.encodeToString(key.getPublicExponent().toByteArray())
          + "\",\"key_ops\":[\"verify\"],\"kid\":\""
          + kid
          + "\",\"kty\":\"RSA\",\"n\":\""
          + encoder.encodeToString(modulus)
          + "\",\"use\":\"sig\"}";
    } catch (java.security.GeneralSecurityException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

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
  void allowsAbsentKeyOperationsOnlyForTrustedPublishedKeysAndRejectsOtherShapes()
      throws Exception {
    String jwkWithoutKeyOps = JWK_1.replace("\"key_ops\":[\"verify\"],", "");
    Fixture absentKeyOps = new Fixture("{\"keys\":[" + jwkWithoutKeyOps + "]}");

    SourceIdentity identity = absentKeyOps.source.sourceIdentity();
    assertThat(absentKeyOps.source.load().jwksBytes())
        .containsExactly(
            absentKeyOps
                .snapshot
                .data()
                .get(AccountJwtJwksPublicationRepository.JWKS_DATA_KEY)
                .getBytes(StandardCharsets.UTF_8));
    assertThat(identity.configMapUid()).isEqualTo(CONFIG_MAP_UID);

    var mapper = JsonMapper.builder().build();
    var missingModulus = (tools.jackson.databind.node.ObjectNode) mapper.readTree(JWK_1);
    missingModulus.remove("n");
    for (String malformedJwk :
        List.of(
            JWK_1.replace("\"use\":\"sig\"", "\"use\":\"sig\",\"unexpected\":true"),
            JWK_1.replace("\"use\":\"sig\"", "\"use\":\"sig\",\"d\":\"AQID\""),
            mapper.writeValueAsString(missingModulus),
            JWK_1.replace("[\"verify\"]", "[\"verify\",\"sign\"]"))) {
      Fixture malformed = new Fixture("{\"keys\":[" + malformedJwk + "]}");
      assertThatThrownBy(malformed.source::sourceIdentity)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Account trusted public JWKS source was rejected");
    }
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
  void requiresReceiptWhenCurrentPublicationExistsAlongsideCommittedSigner() {
    Fixture fixture = new Fixture();
    fixture.useCommittedState();
    fixture.removeCurrentPublicationReceipt();

    assertThatThrownBy(fixture.source::sourceIdentity)
        .isInstanceOf(SourceUnavailableException.class)
        .hasNoCause();
    verify(fixture.configMapClient, never()).observe();
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
  void realPrincipalReviewOutageRemainsRetryableThroughCurrentReadGrpc() throws Exception {
    Fixture fixture = new Fixture();
    var parsed =
        new ParsedBinding(
            "api-r1",
            ENVIRONMENT,
            CLUSTER,
            URI.create("https://kubernetes.test:6443"),
            "kubernetes.test",
            Path.of("unused-ca.pem"),
            API_CA_DIGEST,
            Path.of("unused-token"),
            NAMESPACE,
            CLUSTER_UID,
            NAMESPACE_UID,
            fixture.apiIdentity.expectedApiUsername(),
            API_BINDING_DIGEST);
    var api = mock(AccountJwtJwksApiBinding.class);
    var operation = mock(AccountJwtJwksApiBinding.ApiOperation.class);
    when(api.beginOperation()).thenReturn(operation);
    when(operation.binding()).thenReturn(parsed);
    var reviewStatus = new AtomicInteger(503);
    var json = JsonMapper.builder().build();
    when(operation.send(any(), any()))
        .thenAnswer(
            invocation -> {
              ApiCall call = invocation.getArgument(0);
              int status =
                  call == ApiCall.REVIEW_AUTHENTICATED_PRINCIPAL ? reviewStatus.get() : 200;
              Map<String, ?> body =
                  switch (call) {
                    case REVIEW_AUTHENTICATED_PRINCIPAL ->
                        status == 201
                            ? Map.of(
                                "apiVersion", "authentication.k8s.io/v1",
                                "kind", "SelfSubjectReview",
                                "status",
                                    Map.of(
                                        "userInfo",
                                        Map.of("username", parsed.expectedApiUsername())))
                            : Map.of();
                    case READ_KUBE_SYSTEM_NAMESPACE, READ_TARGET_NAMESPACE ->
                        Map.of(
                            "apiVersion",
                            "v1",
                            "kind",
                            "Namespace",
                            "metadata",
                            Map.of(
                                "name",
                                call == ApiCall.READ_KUBE_SYSTEM_NAMESPACE
                                    ? "kube-system"
                                    : NAMESPACE,
                                "uid",
                                call == ApiCall.READ_KUBE_SYSTEM_NAMESPACE
                                    ? CLUSTER_UID
                                    : NAMESPACE_UID),
                            "status",
                            Map.of("phase", "Active"));
                    case READ_JWKS_CONFIG_MAP ->
                        Map.of(
                            "apiVersion",
                            "v1",
                            "kind",
                            "ConfigMap",
                            "metadata",
                            Map.of(
                                "name",
                                AccountJwtJwksConfigMapClient.CONFIG_MAP_NAME,
                                "namespace",
                                NAMESPACE,
                                "uid",
                                CONFIG_MAP_UID,
                                "resourceVersion",
                                fixture.snapshot.resourceVersion()),
                            "data",
                            fixture.snapshot.data());
                    default -> throw new AssertionError("Unexpected public-read API call");
                  };
              return new ApiResponse(status, json.writeValueAsBytes(body));
            });
    var source =
        new AccountJwtJwksTrustedSource(
            new AccountJwtJwksConfigMapClient(api),
            fixture.materializerTrust,
            fixture.desiredStateRepository,
            fixture.publicationRepository,
            fixture.transactionManager);
    var guard = mock(AccountPublicJwksReadWorkloadGuard.class);
    when(guard.requireCaller())
        .thenReturn(
            new AccountPublicJwksReadWorkloadGuard.ProtectedCaller(fixture.binding, parsed));
    var receiver =
        new AccountPublicJwksReadGrpcService(new AccountPublicJwksReadService(source, guard));

    // First-pin and later load outages both recover only after another complete protected read.
    for (int status : List.of(408, 201, 429, 201, 503, 201, 401, 200)) {
      reviewStatus.set(status);
      var response = new AtomicReference<GetCurrentAccountPublicJwksResponse>();
      var failure = new AtomicReference<Throwable>();
      var completed = new AtomicInteger();
      receiver.getCurrentPublicJwks(
          AccountPublicJwksReadCodec.request(),
          new StreamObserver<>() {
            @Override
            public void onNext(GetCurrentAccountPublicJwksResponse value) {
              response.set(value);
            }

            @Override
            public void onError(Throwable error) {
              failure.set(error);
            }

            @Override
            public void onCompleted() {
              completed.incrementAndGet();
            }
          });
      if (status == 201) {
        assertThat(failure.get()).isNull();
        assertThat(completed.get()).isEqualTo(1);
        PublicJwksSnapshot snapshot = AccountPublicJwksReadCodec.snapshot(response.get());
        assertThat(snapshot.sourceIdentity()).isEqualTo(source.sourceIdentity());
        assertThat(snapshot.jwksBytes()).containsExactly(JWKS_1.getBytes(StandardCharsets.UTF_8));
      } else {
        assertThat(response.get()).isNull();
        assertThat(completed.get()).isZero();
        assertThat(failure.get()).isNotNull();
        assertThat(Status.fromThrowable(failure.get()).getCode())
            .isEqualTo(
                status == 401 || status == 200
                    ? Status.Code.FAILED_PRECONDITION
                    : Status.Code.UNAVAILABLE);
      }
    }
  }

  @Test
  void treatsDatabaseAndTransactionFailuresAsRetryableSourceUnavailability() {
    List<RuntimeException> failures =
        List.of(
            new org.jooq.exception.DataAccessException("database unavailable"),
            new DataAccessResourceFailureException("database unavailable"),
            new TransactionException("transaction unavailable") {});

    for (RuntimeException failure : failures) {
      Fixture fixture = new Fixture();
      when(fixture.desiredStateRepository.readEnrollmentState(fixture.binding.accountBinding()))
          .thenThrow(failure);

      assertThatThrownBy(fixture.source::sourceIdentity)
          .isInstanceOf(SourceUnavailableException.class)
          .hasNoCause();
    }
  }

  @Test
  void doesNotTrustUnexpectedOwnerFailureByMatchingItsMessageText() {
    Fixture fixture = new Fixture();
    IllegalStateException unrelated =
        new IllegalStateException(
            "unrelated adapter failure mentioning Account trusted public JWKS source was rejected");
    when(fixture.desiredStateRepository.readEnrollmentState(fixture.binding.accountBinding()))
        .thenThrow(unrelated);

    assertThatThrownBy(fixture.source::sourceIdentity)
        .isInstanceOf(IllegalStateException.class)
        .isNotSameAs(unrelated)
        .hasMessage("Account trusted public JWKS source was rejected")
        .hasNoCause();
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
  void publicMovingOwnerBracketRejectsWhileOrdinaryReadRemainsTemporary() {
    for (boolean publicRead : new boolean[] {false, true}) {
      Fixture fixture = new Fixture();
      var intent = fixture.publication.intent();
      // Two individually valid owner projections differ around the intervening live read.
      var after =
          new PublicationEvidence(
              intent, Optional.of(PublicationReceipt.create(intent, "43")), Optional.empty());
      when(fixture.publicationRepository.readCurrentPublication(
              eq(fixture.binding.accountBinding()), eq(fixture.trustFence)))
          .thenReturn(Optional.of(fixture.publication), Optional.of(after));
      if (publicRead) {
        assertRejectedPublicRead(fixture);
      } else {
        assertThatThrownBy(fixture.source::sourceIdentity)
            .isInstanceOf(SourceUnavailableException.class)
            .hasNoCause();
      }
      verify(fixture.configMapClient).observe();
    }
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
  void versionOnlyInitialReadbackRemainsUnavailableWithoutInstallingASourcePin() {
    Fixture fixture = new Fixture();
    when(fixture.configMapClient.observe())
        .thenReturn(new ConfigMapSnapshot(CONFIG_MAP_UID, "43", fixture.snapshot.data()));

    assertThatThrownBy(fixture.source::sourceIdentity)
        .isInstanceOf(SourceUnavailableException.class)
        .hasNoCause();
  }

  @Test
  void versionOnlyDriftReturnsNoBytesUntilALaterExactOwnerOperation() {
    Fixture fixture = new Fixture();
    SourceIdentity originalPin = fixture.source.sourceIdentity();
    Map<String, String> metadataOnlyData = new LinkedHashMap<>(fixture.snapshot.data());
    metadataOnlyData.put("operator.note", "changed without changing Account-owned bytes");
    when(fixture.configMapClient.observe())
        .thenReturn(new ConfigMapSnapshot(CONFIG_MAP_UID, "43", metadataOnlyData));
    var guard = mock(AccountPublicJwksReadWorkloadGuard.class);
    when(guard.requireCaller())
        .thenReturn(mock(AccountPublicJwksReadWorkloadGuard.ProtectedCaller.class));
    var owner = new AccountPublicJwksReadService(fixture.source, guard);
    for (int attempt = 0; attempt < 3; attempt++) {
      assertThatThrownBy(() -> owner.read(AccountPublicJwksReadCodec.request()))
          .isInstanceOf(SourceUnavailableException.class)
          .hasNoCause();
      assertThat(fixture.source.sourceIdentity()).isEqualTo(originalPin);
    }
    assertThat(fixture.publication.receipt().orElseThrow().observedResourceVersion())
        .isEqualTo("42");
    // Recovery advances through a different authorized operation and receipt. It neither rolls
    // Kubernetes RV backward nor replaces the original operation's immutable receipt.
    fixture.useCommittedState("44", "45");
    var recovered =
        AccountPublicJwksReadCodec.snapshot(owner.read(AccountPublicJwksReadCodec.request()));
    assertThat(recovered.sourceIdentity()).isEqualTo(originalPin);
    assertThat(recovered.jwksBytes())
        .containsExactly(JWKS_1_AND_2.getBytes(StandardCharsets.UTF_8));
    assertThat(fixture.nextPublication.receipt().orElseThrow().observedResourceVersion())
        .isEqualTo("45");
    assertThat(fixture.publication.receipt().orElseThrow().observedResourceVersion())
        .isEqualTo("42");
    verify(fixture.configMapClient, never()).publish(any(), anyMap());
  }

  @Test
  void versionOnlyOutageClassificationNeverHidesUidOrOwnedByteTamper() {
    Fixture original = new Fixture();
    Map<String, String> changedJwks = new LinkedHashMap<>(original.snapshot.data());
    changedJwks.put(AccountJwtJwksPublicationRepository.JWKS_DATA_KEY, JWKS_1_AND_2);
    Map<String, String> changedMarker = new LinkedHashMap<>(original.snapshot.data());
    changedMarker.put(
        AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
        original.initialMarker + " ");
    for (ConfigMapSnapshot tamper :
        List.of(
            new ConfigMapSnapshot(
                "44444444-4444-4444-8444-444444444444", "43", original.snapshot.data()),
            new ConfigMapSnapshot(CONFIG_MAP_UID, "43", changedJwks),
            new ConfigMapSnapshot(CONFIG_MAP_UID, "43", changedMarker))) {
      Fixture fixture = new Fixture();
      fixture.source.sourceIdentity();
      when(fixture.configMapClient.observe()).thenReturn(tamper);
      var guard = mock(AccountPublicJwksReadWorkloadGuard.class);
      when(guard.requireCaller())
          .thenReturn(mock(AccountPublicJwksReadWorkloadGuard.ProtectedCaller.class));
      var owner = new AccountPublicJwksReadService(fixture.source, guard);
      assertThatThrownBy(() -> owner.read(AccountPublicJwksReadCodec.request()))
          .isExactlyInstanceOf(
              net.firedevops.firemud.common.security.AccountPublicJwksCache.InvalidJwksException
                  .class)
          .hasNoCause();
    }
  }

  @Test
  void preparedPrepublicationDeniesWithoutKeysRegardlessOfActiveReceiptAndNeverEnablesIssuance() {
    for (boolean hasActiveReceipt : List.of(false, true)) {
      Fixture fixture = new Fixture();
      fixture.usePreparedState(false, hasActiveReceipt);
      var result = fixture.source.currentPublicRead();
      assertThat(result.preparedDeny()).isTrue();
      assertThat(result.committedRecoveryEligible()).isFalse();
      assertThat(result.jwksBytes()).isEmpty();
      assertThat(result.sourceIdentity()).isEqualTo(fixture.source.sourceIdentity());
      assertThatThrownBy(fixture.source::load)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Account trusted public JWKS source was rejected");
      verify(fixture.configMapClient, never()).publish(any(), anyMap());
    }
  }

  @Test
  void preparedActiveDeniesWithExactActiveReceiptOrFullyVerifiedPrivatePromotionProof() {
    for (boolean hasActiveReceipt : List.of(false, true)) {
      Fixture fixture = new Fixture();
      fixture.usePreparedState(true, hasActiveReceipt);
      var result = fixture.source.currentPublicRead();
      assertThat(result.preparedDeny()).isTrue();
      assertThat(result.committedRecoveryEligible()).isFalse();
      assertThat(result.jwksBytes()).isEmpty();
      assertThat(result.sourceIdentity().configMapUid()).isEqualTo(CONFIG_MAP_UID);
      verify(fixture.desiredStateRepository, times(2))
          .readPreparedPrivatePromotionReceiptForRecovery(
              fixture.accountBinding, fixture.trustFence);
      assertThatThrownBy(fixture.source::load)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Account trusted public JWKS source was rejected");
    }
  }

  @Test
  void unreceiptedPreparedActiveRejectsMissingMismatchedOrUnverifiedPrivateReceipt() {
    for (String tamper :
        List.of(
            "missing",
            "helper-rejected",
            "digest",
            "operation",
            "generation",
            "private-rv",
            "stored-digest",
            "stored-rv",
            "not-dispatched")) {
      Fixture fixture = new Fixture();
      PromotionOperationEvidence promotion = fixture.usePreparedState(true, false);
      UUID promotionId = promotion.operationId();
      String receiptDigest = promotion.privatePromotionReceiptDigest().orElseThrow();
      switch (tamper) {
        case "missing" -> {
          when(promotion.privatePromotionObservedResourceVersion()).thenReturn(Optional.empty());
          when(promotion.privatePromotionReceiptDigest()).thenReturn(Optional.empty());
        }
        case "helper-rejected" ->
            when(fixture.desiredStateRepository.readPreparedPrivatePromotionReceiptForRecovery(
                    eq(fixture.accountBinding), eq(fixture.trustFence)))
                .thenThrow(
                    new AccountJwtSignerDesiredStateRepository.QuarantinedStateException(
                        "Full canonical private promotion receipt verification failed"));
        case "digest", "operation", "generation", "private-rv" -> {
          PrivatePromotionReceipt mismatched =
              new PrivatePromotionReceipt(
                  tamper.equals("operation") ? NEXT_OPERATION_ID : promotionId,
                  tamper.equals("generation") ? NEXT_OPERATION_ID : INITIAL_OPERATION_ID,
                  tamper.equals("private-rv") ? "private-other" : "private-result",
                  tamper.equals("digest") ? "0".repeat(64) : receiptDigest);
          when(fixture.desiredStateRepository.readPreparedPrivatePromotionReceiptForRecovery(
                  eq(fixture.accountBinding), eq(fixture.trustFence)))
              .thenReturn(mismatched);
        }
        case "stored-digest" ->
            when(promotion.privatePromotionReceiptDigest()).thenReturn(Optional.of("0".repeat(64)));
        case "stored-rv" ->
            when(promotion.privatePromotionObservedResourceVersion())
                .thenReturn(Optional.of("private-other"));
        case "not-dispatched" -> when(promotion.privatePromotionDispatched()).thenReturn(false);
        default -> throw new AssertionError(tamper);
      }
      assertRejectedPublicRead(fixture);
    }
  }

  @Test
  void unreceiptedPreparedActiveAcceptsOpaqueChangedRvAndRejectsPriorRv() {
    Fixture opaque = new Fixture();
    PromotionOperationEvidence promotion = opaque.usePreparedState(true, false);
    String marker = promotion.expectedActiveMarkerJson();
    Map<String, String> data =
        Map.of(
            AccountJwtJwksPublicationRepository.JWKS_DATA_KEY, JWKS_1,
            AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY, marker);
    when(opaque.configMapClient.observe())
        .thenReturn(new ConfigMapSnapshot(CONFIG_MAP_UID, "etcd:opaque_v-7", data));
    var result = opaque.source.currentPublicRead();
    assertThat(result.preparedDeny()).isTrue();
    assertThat(result.jwksBytes()).isEmpty();
    assertThat(result.committedRecoveryEligible()).isFalse();

    Fixture priorRv = new Fixture();
    priorRv.usePreparedState(true, false);
    when(priorRv.configMapClient.observe())
        .thenReturn(new ConfigMapSnapshot(CONFIG_MAP_UID, "42", data));
    assertRejectedPublicRead(priorRv);
  }

  @Test
  void unreceiptedPreparedActiveRejectsChangedUidCompletePayloadAndMarker() {
    for (String tamper : List.of("uid", "missing-key", "substituted-key", "marker")) {
      Fixture fixture = new Fixture();
      PromotionOperationEvidence promotion = fixture.usePreparedState(true, false);
      String marker = promotion.expectedActiveMarkerJson();
      String jwks =
          switch (tamper) {
            case "missing-key" -> "{\"keys\":[]}";
            case "substituted-key" -> "{\"keys\":[" + JWK_2 + "]}";
            default -> JWKS_1;
          };
      if (tamper.equals("missing-key") || tamper.equals("substituted-key")) {
        assertThat(jwks).isNotEqualTo(JWKS_1);
      }
      var snapshot =
          new ConfigMapSnapshot(
              tamper.equals("uid") ? "44444444-4444-4444-8444-444444444444" : CONFIG_MAP_UID,
              "43",
              Map.of(
                  AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
                  jwks,
                  AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
                  tamper.equals("marker") ? marker + " " : marker));
      when(fixture.configMapClient.observe()).thenReturn(snapshot);
      assertRejectedPublicRead(fixture);
    }
  }

  @Test
  void preparedDenialRejectsChangedOperationMountTrustUidMarkerOrActiveReceipt() {
    for (String tamper :
        List.of(
            "operation",
            "mount",
            "trust",
            "uid",
            "marker",
            "receipt",
            "receipt-data",
            "receipt-rv",
            "unreceipted-active")) {
      Fixture fixture = new Fixture();
      PromotionOperationEvidence promotion = fixture.usePreparedState(true, true);
      String expectedActiveMarker = promotion.expectedActiveMarkerJson();
      switch (tamper) {
        case "operation" -> when(promotion.operationId()).thenReturn(NEXT_OPERATION_ID);
        case "mount" -> when(promotion.mountedObservationDigest()).thenReturn("0".repeat(64));
        case "trust" -> when(promotion.apiConfigRevision()).thenReturn("obsolete");
        case "receipt" ->
            when(promotion.activeJwksReceiptDigest()).thenReturn(Optional.of("0".repeat(64)));
        case "receipt-data" ->
            when(promotion.activeJwksPublicDataDigest()).thenReturn(Optional.of("0".repeat(64)));
        case "receipt-rv" ->
            when(promotion.activeJwksObservedResourceVersion()).thenReturn(Optional.of("99"));
        case "unreceipted-active" ->
            when(promotion.activeJwksReceiptDigest()).thenReturn(Optional.empty());
        case "uid" ->
            when(fixture.configMapClient.observe())
                .thenReturn(
                    new ConfigMapSnapshot(
                        "44444444-4444-4444-8444-444444444444",
                        "43",
                        Map.of(
                            AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
                            JWKS_1,
                            AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
                            expectedActiveMarker)));
        case "marker" ->
            when(fixture.configMapClient.observe())
                .thenReturn(
                    new ConfigMapSnapshot(
                        CONFIG_MAP_UID,
                        "43",
                        Map.of(
                            AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
                            JWKS_1,
                            AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
                            expectedActiveMarker + " ")));
        default -> throw new AssertionError(tamper);
      }
      assertRejectedPublicRead(fixture);
    }
  }

  @Test
  void publicReadRejectsMissingOwnerEvidenceWhileOrdinarySourceKeepsOutageSemantics() {
    for (String missing : List.of("enrollment", "committed-signer", "initial-publication")) {
      Fixture fixture = new Fixture();
      switch (missing) {
        case "enrollment" ->
            when(fixture.desiredStateRepository.readEnrollmentState(eq(fixture.accountBinding)))
                .thenReturn(Optional.empty());
        case "committed-signer" -> {
          fixture.useCommittedState();
          when(fixture.desiredStateRepository.readCurrentCommittedSigner(
                  eq(fixture.accountBinding),
                  eq(fixture.trustFence),
                  eq(API_BINDING_DIGEST),
                  eq("api-r1")))
              .thenReturn(Optional.empty());
        }
        case "initial-publication" ->
            when(fixture.publicationRepository.readCurrentPublication(
                    eq(fixture.accountBinding), eq(fixture.trustFence)))
                .thenReturn(Optional.empty());
        default -> throw new AssertionError(missing);
      }

      assertRejectedPublicRead(fixture);
      assertThatThrownBy(fixture.source::sourceIdentity)
          .isInstanceOf(SourceUnavailableException.class)
          .hasNoCause();
      verify(fixture.configMapClient, never()).observe();
    }
  }

  @Test
  void unreceiptedInitialPrepublicationIsTemporaryForExactIntendedOrEnrolledPriorProjection() {
    for (boolean intendedPublished : List.of(false, true)) {
      Fixture fixture = new Fixture();
      when(fixture.publicationRepository.readCurrentPublication(
              eq(fixture.accountBinding), eq(fixture.trustFence)))
          .thenReturn(
              Optional.of(
                  new PublicationEvidence(
                      fixture.publication.intent(), Optional.empty(), Optional.empty())));
      if (!intendedPublished) {
        when(fixture.configMapClient.observe())
            .thenReturn(new ConfigMapSnapshot(CONFIG_MAP_UID, "41", INITIAL_PRIOR_DATA));
      }
      assertThatThrownBy(fixture.source::currentPublicRead)
          .isExactlyInstanceOf(SourceUnavailableException.class)
          .hasNoCause();
      assertThatThrownBy(fixture.source::sourceIdentity)
          .isInstanceOf(SourceUnavailableException.class);
      MountObservation mount =
          MountObservation.create(
              fixture.publication.intent(), fixture.publication.receipt().orElseThrow());
      var recovered =
          new PublicationEvidence(
              fixture.publication.intent(), fixture.publication.receipt(), Optional.of(mount));
      when(fixture.publicationRepository.readCurrentPublication(
              eq(fixture.accountBinding), eq(fixture.trustFence)))
          .thenReturn(Optional.of(recovered));
      when(fixture.configMapClient.observe()).thenReturn(fixture.snapshot);
      var result = fixture.source.currentPublicRead();
      assertThat(result.preparedDeny()).isFalse();
      assertThat(result.committedRecoveryEligible()).isFalse();
      assertThat(result.jwksBytes()).isEqualTo(JWKS_1.getBytes(StandardCharsets.UTF_8));
      assertThat(result.sourceIdentity()).isEqualTo(fixture.source.sourceIdentity());
    }
  }

  @Test
  void unreceiptedAdditivePrepublicationReturnsNoNewKeysAndRecoversWithoutRepinning() {
    for (boolean intendedPublished : List.of(false, true)) {
      Fixture fixture = new Fixture();
      SourceIdentity pin = fixture.source.sourceIdentity();
      fixture.useCommittedState();
      fixture.removeCurrentPublicationReceipt();
      if (!intendedPublished) {
        var prior =
            new ConfigMapSnapshot(
                CONFIG_MAP_UID,
                "42",
                Map.of(
                    AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
                    JWKS_1,
                    AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
                    activeMarker(new ActiveSigner("1", "key_1")),
                    "operator.note",
                    "preserved"));
        when(fixture.configMapClient.observe()).thenReturn(prior);
      }
      for (int attempt = 0; attempt < 2; attempt++) {
        assertThatThrownBy(fixture.source::currentPublicRead)
            .isExactlyInstanceOf(SourceUnavailableException.class)
            .hasNoCause();
        assertThat(fixture.source.sourceIdentity()).isEqualTo(pin);
      }
      // Exact receipt/mount recording completes the same intended additive publication.
      fixture.useCommittedState();
      var receipt = fixture.nextPublication.receipt().orElseThrow();
      var mount = MountObservation.create(fixture.nextPublication.intent(), receipt);
      var recovered =
          new PublicationEvidence(
              fixture.nextPublication.intent(), Optional.of(receipt), Optional.of(mount));
      when(fixture.publicationRepository.readCurrentPublication(
              eq(fixture.accountBinding), eq(fixture.trustFence)))
          .thenReturn(Optional.of(recovered));
      var result = fixture.source.currentPublicRead();
      assertThat(result.sourceIdentity()).isEqualTo(pin);
      assertThat(result.preparedDeny()).isFalse();
      assertThat(result.committedRecoveryEligible()).isFalse();
      assertThat(result.jwksBytes()).isEqualTo(JWKS_1_AND_2.getBytes(StandardCharsets.UTF_8));
    }
  }

  @Test
  void unreceiptedAdditivePublicationPreservesOnlyOriginalFreshCachedKeys() {
    Fixture fixture = new Fixture();
    var pin = fixture.source.sourceIdentity();
    java.time.Clock clock = mock(java.time.Clock.class);
    var start = java.time.Instant.parse("2026-11-10T00:00:00Z");
    when(clock.instant()).thenReturn(start);
    var cache =
        new net.firedevops.firemud.common.security.AccountPublicJwksCache(
            () -> {
              var read = fixture.source.currentPublicRead();
              if (read.preparedDeny()) {
                throw new net.firedevops.firemud.common.security.AccountPublicJwksCache
                    .SourcePreparedDenyException();
              }
              return new PublicJwksSnapshot(
                  read.sourceIdentity(), read.jwksBytes(), read.committedRecoveryEligible());
            },
            pin,
            clock,
            java.time.Duration.ofSeconds(5));
    cache.refresh();
    assertThat(cache.keyFor("key_1")).isNotNull();
    fixture.useCommittedState();
    fixture.removeCurrentPublicationReceipt();
    when(clock.instant()).thenReturn(start.plusSeconds(4));
    cache.refresh();
    assertThat(cache.sourceIdentity()).isEqualTo(pin);
    assertThat(cache.keyFor("key_1")).isNotNull();
    assertThatThrownBy(() -> cache.keyFor("key_2"))
        .isInstanceOf(
            net.firedevops.firemud.common.security.AccountPublicJwksCache.UnknownKeyException
                .class);
    when(clock.instant()).thenReturn(start.plusSeconds(5));
    assertThatThrownBy(() -> cache.keyFor("key_1")).isInstanceOf(SourceUnavailableException.class);
    fixture.useCommittedState();
    cache.refresh();
    assertThat(cache.sourceIdentity()).isEqualTo(pin);
    assertThat(cache.keyFor("key_2")).isNotNull();
  }

  @Test
  void unreceiptedPublicationRejectsUnprovenPredecessorAndExtraAdditions() {
    Fixture initial = new Fixture();
    when(initial.publicationRepository.readCurrentPublication(
            eq(initial.accountBinding), eq(initial.trustFence)))
        .thenReturn(
            Optional.of(
                new PublicationEvidence(
                    initial.publication.intent(), Optional.empty(), Optional.empty())));
    var changed = new LinkedHashMap<>(initial.snapshot.data());
    changed.put("operator.note", "changed");
    when(initial.configMapClient.observe())
        .thenReturn(new ConfigMapSnapshot(CONFIG_MAP_UID, "42", changed));
    assertRejectedPublicRead(initial);

    Fixture extra = new Fixture();
    extra.useCommittedState();
    String unauthorized =
        "{\"keys\":[" + JWK_1 + "," + JWK_2 + "," + JWK_1.replace("\"key_1\"", "\"key_3\"") + "]}";
    assertThat(unauthorized).isNotEqualTo(JWKS_1_AND_2);
    extra.replaceNextJwks(unauthorized);
    extra.removeCurrentPublicationReceipt();
    assertRejectedPublicRead(extra);
  }

  @Test
  void unreceiptedPrepublicationRejectsUidMarkerPayloadAndGenerationMismatch() {
    for (boolean initial : List.of(false, true)) {
      for (String tamper : List.of("uid", "marker", "payload", "generation")) {
        Fixture fixture = new Fixture();
        if (!initial) fixture.useCommittedState();
        PublicationEvidence publication = initial ? fixture.publication : fixture.nextPublication;
        String marker = publication.intent().generationMarkerJson();
        String jwks = publication.intent().jwksJson();
        String liveJwks = tamper.equals("payload") ? jwks + " " : jwks;
        if (tamper.equals("payload")) {
          assertThat(liveJwks).isNotEqualTo(jwks);
        }
        var live =
            new ConfigMapSnapshot(
                tamper.equals("uid") ? "44444444-4444-4444-8444-444444444444" : CONFIG_MAP_UID,
                "43",
                Map.of(
                    AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
                    liveJwks,
                    AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
                    tamper.equals("marker") ? marker + " " : marker));
        when(fixture.publicationRepository.readCurrentPublication(
                eq(fixture.accountBinding), eq(fixture.trustFence)))
            .thenReturn(
                Optional.of(
                    new PublicationEvidence(
                        publication.intent(), Optional.empty(), Optional.empty())));
        when(fixture.configMapClient.observe()).thenReturn(live);
        if (tamper.equals("generation")) {
          GenerationRequest request =
              fixture.desiredStateRepository.readCurrentGenerationRequest(
                  fixture.accountBinding, fixture.trustFence);
          when(request.operationDigest()).thenReturn("0".repeat(64));
        }
        assertRejectedPublicRead(fixture);
      }
    }
  }

  @Test
  void unreceiptedAdditivePublicationRejectsSelfConsistentButUnqualifiedMarker() throws Exception {
    var json = JsonMapper.builder().build();
    ActiveSigner active = new ActiveSigner("1", "key_1");
    for (String tamper :
        List.of(
            "malformed",
            "unknown-phase",
            "active-phase",
            "unknown-field",
            "missing-field",
            "schema",
            "operation-id",
            "operation-digest",
            "request-digest",
            "receipt-digest",
            "trust-digest",
            "trust-revision",
            "api-digest",
            "api-revision",
            "namespace",
            "pending-generation",
            "pending-kid",
            "pending-fingerprint",
            "durable-absent",
            "durable-generation",
            "durable-kid",
            "published-absent",
            "published-generation",
            "published-kid")) {
      Fixture fixture = new Fixture();
      fixture.useCommittedState();
      String originalMarker = fixture.nextPublication.intent().generationMarkerJson();
      ObjectNode marker = (ObjectNode) json.readTree(originalMarker);
      ObjectNode binding = (ObjectNode) marker.get("binding");
      ObjectNode pending = (ObjectNode) marker.get("pending");
      ObjectNode durable = (ObjectNode) marker.get("expectedDurableActive");
      ObjectNode published = (ObjectNode) marker.get("expectedPublishedActive");
      switch (tamper) {
        case "malformed" -> {
          /* The serialized projection is replaced below. */
        }
        case "unknown-phase" -> marker.put("phase", "UNKNOWN");
        case "active-phase" -> marker.put("phase", "ACTIVE");
        case "unknown-field" -> marker.put("unexpected", true);
        case "missing-field" -> marker.remove("generationRequestDigest");
        case "schema" -> marker.put("schemaVersion", 2);
        case "operation-id" -> marker.put("operationId", INITIAL_OPERATION_ID.toString());
        case "operation-digest" -> marker.put("operationDigest", "0".repeat(64));
        case "request-digest" -> marker.put("generationRequestDigest", "0".repeat(64));
        case "receipt-digest" -> marker.put("generationReceiptDigest", "0".repeat(64));
        case "trust-digest" -> binding.put("trustBindingDigest", "0".repeat(64));
        case "trust-revision" -> binding.put("trustConfigRevision", "obsolete");
        case "api-digest" -> binding.put("apiBindingDigest", "0".repeat(64));
        case "api-revision" -> binding.put("apiConfigRevision", "obsolete");
        case "namespace" -> binding.put("namespace", "other-namespace");
        case "pending-generation" -> pending.put("generation", "3");
        case "pending-kid" -> pending.put("kid", "key_other");
        case "pending-fingerprint" -> pending.put("publicKeyFingerprint", "0".repeat(64));
        case "durable-absent" -> {
          durable.removeAll();
          durable.put("present", false);
        }
        case "durable-generation" -> durable.put("generation", "3");
        case "durable-kid" -> durable.put("kid", "key_other");
        case "published-absent" -> {
          published.removeAll();
          published.put("present", false);
        }
        case "published-generation" -> published.put("generation", "3");
        case "published-kid" -> published.put("kid", "key_other");
        default -> throw new AssertionError(tamper);
      }
      String alteredMarker = tamper.equals("malformed") ? "{" : json.writeValueAsString(marker);
      assertThat(alteredMarker).as(tamper).isNotEqualTo(originalMarker);
      PublicationEvidence altered =
          fixture.publication(
              JWKS_1_AND_2, alteredMarker, "42", "43", "2", "key_2", Optional.of(active));
      // Recompute all immutable intent hashes; rejection must come from marker semantics.
      assertThat(altered.intent().generationMarkerDigest()).isEqualTo(sha256(alteredMarker));
      assertThat(altered.intent().publicDataDigest())
          .isEqualTo(
              AccountJwtJwksPublicationRepository.publicDataDigest(JWKS_1_AND_2, alteredMarker));
      var unreceipted =
          new PublicationEvidence(altered.intent(), Optional.empty(), Optional.empty());
      var live =
          new ConfigMapSnapshot(
              CONFIG_MAP_UID,
              "43",
              Map.of(
                  AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
                  JWKS_1_AND_2,
                  AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
                  alteredMarker,
                  "operator.note",
                  "preserved"));
      when(fixture.publicationRepository.readCurrentPublication(
              eq(fixture.accountBinding), eq(fixture.trustFence)))
          .thenReturn(Optional.of(unreceipted));
      when(fixture.configMapClient.observe()).thenReturn(live);
      assertRejectedPublicRead(fixture);
    }
  }

  @Test
  void preparedDenyPinsCurrentResourceAndRejectsSubsequentProtectedTrustChange() {
    Fixture fixture = new Fixture();
    fixture.usePreparedState(false, false);
    fixture.source.currentPublicRead();
    when(fixture.materializerTrust.current())
        .thenReturn(Optional.of(binding(CLUSTER_UID, NAMESPACE_UID, "materializer-r2")));
    assertRejectedPublicRead(fixture);
  }

  @Test
  void onlyExactCommittedActiveProjectionIsRecoveryEligible() {
    Fixture initial = new Fixture();
    assertThat(initial.source.currentPublicRead().committedRecoveryEligible()).isFalse();
    Fixture nextPrepublication = new Fixture();
    nextPrepublication.useCommittedState();
    assertThat(nextPrepublication.source.currentPublicRead().committedRecoveryEligible()).isFalse();

    Fixture committed = new Fixture();
    committed.useCommittedState();
    when(committed.desiredStateRepository.readCurrentGenerationRequest(
            eq(committed.accountBinding), eq(committed.trustFence)))
        .thenReturn(mock(GenerationRequest.class));
    when(committed.configMapClient.observe())
        .thenReturn(
            new ConfigMapSnapshot(
                CONFIG_MAP_UID,
                "42",
                Map.of(
                    AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
                    JWKS_1,
                    AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
                    activeMarker(new ActiveSigner("1", "key_1")))));
    var result = committed.source.currentPublicRead();
    assertThat(result.preparedDeny()).isFalse();
    assertThat(result.committedRecoveryEligible()).isTrue();
    assertThat(result.jwksBytes()).isEqualTo(JWKS_1.getBytes(StandardCharsets.UTF_8));
  }

  private static void assertRejectedPublicRead(Fixture fixture) {
    var guard = mock(AccountPublicJwksReadWorkloadGuard.class);
    when(guard.requireCaller())
        .thenReturn(mock(AccountPublicJwksReadWorkloadGuard.ProtectedCaller.class));
    assertThatThrownBy(
            () ->
                new AccountPublicJwksReadService(fixture.source, guard)
                    .read(AccountPublicJwksReadCodec.request()))
        .isExactlyInstanceOf(
            net.firedevops.firemud.common.security.AccountPublicJwksCache.InvalidJwksException
                .class);
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
    private PublicationEvidence nextPublication;
    private final ConfigMapSnapshot snapshot;
    private final AccountJwtJwksTrustedSource source;

    private Fixture() {
      this(JWKS_1);
    }

    private Fixture(String jwks) {
      enrollmentIdentity =
          new EnrollmentIdentity(
              CLUSTER_UID,
              NAMESPACE_UID,
              binding.bindingDigest(),
              binding.configRevision(),
              API_BINDING_DIGEST,
              "api-r1",
              CONFIG_MAP_UID,
              "41",
              AccountJwtJwksPublicationRepository.snapshotDigest(INITIAL_PRIOR_DATA));
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
      publication = publication(jwks, initialMarker, "41", "42", "1", "key_1", Optional.empty());
      snapshot =
          new ConfigMapSnapshot(
              CONFIG_MAP_UID,
              "42",
              Map.of(
                  AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
                  jwks,
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

    private PromotionOperationEvidence usePreparedState(
        boolean liveActive, boolean hasActiveReceipt) {
      UUID promotionId = UUID.fromString("66666666-6666-4666-8666-000000000001");
      DesiredState preparedState =
          new DesiredState(
              accountBinding,
              3,
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.of(promotionId),
              Optional.of(enrollmentIdentity));
      PromotionOperationEvidence promotion = mock(PromotionOperationEvidence.class);
      PublicationReceipt receipt = publication.receipt().orElseThrow();
      MountObservation mount = MountObservation.create(publication.intent(), receipt);
      String activeMarker = initialMarker.replace("PREPUBLISHED", "ACTIVE");
      when(promotion.operationId()).thenReturn(promotionId);
      when(promotion.requestDigest()).thenReturn("7".repeat(64));
      when(promotion.status()).thenReturn("PREPARED");
      when(promotion.expectedRecordVersion()).thenReturn(2L);
      when(promotion.binding()).thenReturn(accountBinding);
      when(promotion.expectedPreviousActive()).thenReturn(Optional.empty());
      when(promotion.expectedPublishedActive()).thenReturn(Optional.empty());
      when(promotion.trustFence()).thenReturn(trustFence);
      when(promotion.apiBindingDigest()).thenReturn(API_BINDING_DIGEST);
      when(promotion.apiConfigRevision()).thenReturn("api-r1");
      when(promotion.generationOperationId()).thenReturn(INITIAL_OPERATION_ID);
      when(promotion.generationOperationDigest()).thenReturn(GENERATION_OPERATION_DIGEST);
      when(promotion.generationReceiptDigest()).thenReturn(GENERATION_RECEIPT_DIGEST);
      when(promotion.targetGeneration()).thenReturn("1");
      when(promotion.targetKid()).thenReturn("key_1");
      when(promotion.targetAlgorithm()).thenReturn("RS256");
      when(promotion.targetPublicKeyFingerprint()).thenReturn(publicFingerprint);
      when(promotion.publicConfigMapUid()).thenReturn(CONFIG_MAP_UID);
      when(promotion.expectedPublicResourceVersion()).thenReturn("42");
      when(promotion.expectedPublicJwksJson()).thenReturn(JWKS_1);
      when(promotion.expectedActiveMarkerJson()).thenReturn(activeMarker);
      when(promotion.prepublicationIntentDigest()).thenReturn(publication.intent().intentDigest());
      when(promotion.prepublicationReceiptDigest()).thenReturn(receipt.receiptDigest());
      when(promotion.mountedObservationDigest()).thenReturn(mount.observationDigest());
      String secretUid = "77777777-7777-4777-8777-777777777777";
      when(promotion.secretUid()).thenReturn(secretUid);
      when(promotion.expectedPrivateResourceVersion()).thenReturn("private-prior");
      when(promotion.privatePromotionDispatched()).thenReturn(true);
      Map<String, Object> privatePreimage = new LinkedHashMap<>();
      privatePreimage.put("receiptVersion", 1);
      privatePreimage.put("promotionOperationId", promotionId.toString());
      privatePreimage.put("promotionRequestDigest", promotion.requestDigest());
      privatePreimage.put("generationOperationId", INITIAL_OPERATION_ID.toString());
      privatePreimage.put("generationOperationDigest", GENERATION_OPERATION_DIGEST);
      privatePreimage.put("generationReceiptDigest", GENERATION_RECEIPT_DIGEST);
      privatePreimage.put(
          "secretResource",
          Map.of(
              "kind",
              "Secret",
              "name",
              AccountJwtSignerDesiredStateRepository.PRIVATE_SECRET_NAME));
      privatePreimage.put("secretUid", secretUid);
      privatePreimage.put("expectedPriorResourceVersion", "private-prior");
      privatePreimage.put("observedResourceVersion", "private-result");
      privatePreimage.put("action", "PROMOTE_PENDING");
      privatePreimage.put("resultingSlots", List.of("current"));
      privatePreimage.put(
          "current",
          Map.of("generation", "1", "kid", "key_1", "publicKeyFingerprint", publicFingerprint));
      privatePreimage.put("previous", Map.of("present", false));
      try {
        String privateDigest =
            sha256(
                new String(
                    Rfc8785CanonicalJson.canonicalizeUtf8(
                        JsonMapper.builder().build().writeValueAsString(privatePreimage)),
                    StandardCharsets.UTF_8));
        when(promotion.privatePromotionObservedResourceVersion())
            .thenReturn(Optional.of("private-result"));
        when(promotion.privatePromotionReceiptDigest()).thenReturn(Optional.of(privateDigest));
        var privateReceipt =
            new PrivatePromotionReceipt(
                promotionId, INITIAL_OPERATION_ID, "private-result", privateDigest);
        when(desiredStateRepository.readPreparedPrivatePromotionReceiptForRecovery(
                eq(accountBinding), eq(trustFence)))
            .thenReturn(privateReceipt);
      } catch (Exception invalidFixture) {
        throw new AssertionError(invalidFixture);
      }
      when(promotion.activeJwksObservedResourceVersion()).thenReturn(Optional.empty());
      when(promotion.activeJwksPublicDataDigest()).thenReturn(Optional.empty());
      when(promotion.activeJwksReceiptDigest()).thenReturn(Optional.empty());
      if (hasActiveReceipt) {
        String dataDigest =
            AccountJwtJwksPublicationRepository.publicDataDigest(JWKS_1, activeMarker);
        Map<String, Object> preimage = new LinkedHashMap<>();
        preimage.put("receiptVersion", "account-jwt-active-jwks-promotion-receipt/v1");
        preimage.put("promotionOperationId", promotionId.toString());
        preimage.put("promotionRequestDigest", promotion.requestDigest());
        preimage.put("generationOperationId", INITIAL_OPERATION_ID.toString());
        preimage.put("generationOperationDigest", GENERATION_OPERATION_DIGEST);
        preimage.put("generationReceiptDigest", GENERATION_RECEIPT_DIGEST);
        preimage.put("configMapName", AccountJwtJwksConfigMapClient.CONFIG_MAP_NAME);
        preimage.put("configMapUid", CONFIG_MAP_UID);
        preimage.put("priorResourceVersion", "42");
        preimage.put("observedResourceVersion", "43");
        preimage.put("action", "MARK_ACTIVE");
        preimage.put("publicDataDigest", dataDigest);
        try {
          String digest =
              sha256(
                  new String(
                      Rfc8785CanonicalJson.canonicalizeUtf8(
                          JsonMapper.builder().build().writeValueAsString(preimage)),
                      StandardCharsets.UTF_8));
          when(promotion.activeJwksObservedResourceVersion()).thenReturn(Optional.of("43"));
          when(promotion.activeJwksPublicDataDigest()).thenReturn(Optional.of(dataDigest));
          when(promotion.activeJwksReceiptDigest()).thenReturn(Optional.of(digest));
        } catch (Exception invalidFixture) {
          throw new AssertionError(invalidFixture);
        }
      }
      var prepared = new PreparedGenerationEvidence(preparedState, promotion, initialResult);
      when(desiredStateRepository.readEnrollmentState(eq(accountBinding)))
          .thenReturn(Optional.of(preparedState));
      when(desiredStateRepository.readPreparedGenerationForRecovery(
              eq(accountBinding), eq(trustFence)))
          .thenReturn(prepared);
      when(publicationRepository.readPreparedPublicationForRecovery(
              eq(accountBinding), eq(trustFence)))
          .thenReturn(new PromotionPublicationEvidence(publication.intent(), receipt, mount));
      when(configMapClient.observe())
          .thenReturn(
              new ConfigMapSnapshot(
                  CONFIG_MAP_UID,
                  liveActive ? "43" : "42",
                  Map.of(
                      AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
                      JWKS_1,
                      AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
                      liveActive ? activeMarker : initialMarker)));
      return promotion;
    }

    private void useCommittedState() {
      useCommittedState("42", "43");
    }

    private void useCommittedState(String activeResourceVersion, String nextResourceVersion) {
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
      when(promotion.activeJwksObservedResourceVersion())
          .thenReturn(Optional.of(activeResourceVersion));
      when(promotion.activeJwksPublicDataDigest()).thenReturn(Optional.of(activeDigest));
      when(receipt.configMapUid()).thenReturn(CONFIG_MAP_UID);
      when(receipt.observedResourceVersion()).thenReturn(activeResourceVersion);
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
      when(nextRequest.binding()).thenReturn(accountBinding);
      when(nextRequest.trustFence()).thenReturn(trustFence);
      when(nextRequest.targetAlgorithm()).thenReturn("RS256");
      when(nextRequest.publicJwkJson()).thenReturn(JWK_2);
      when(nextRequest.targetGeneration()).thenReturn("2");
      when(nextRequest.targetKid()).thenReturn("key_2");
      when(nextRequest.publicKeyFingerprint()).thenReturn(fingerprint(JWK_2));
      when(nextRequest.expectedActive()).thenReturn(Optional.of(active));
      when(nextRequest.expectedPublishedActive()).thenReturn(Optional.of(active));
      when(desiredStateRepository.readCurrentGenerationRequest(eq(accountBinding), eq(trustFence)))
          .thenReturn(nextRequest);

      GenerationResult nextResult = mock(GenerationResult.class);
      when(nextResult.operationId()).thenReturn(NEXT_OPERATION_ID);
      when(nextResult.binding()).thenReturn(accountBinding);
      when(nextResult.trustFence()).thenReturn(trustFence);
      when(nextResult.operationDigest()).thenReturn(GENERATION_OPERATION_DIGEST);
      when(nextResult.generationRequestDigest()).thenReturn(GENERATION_REQUEST_DIGEST);
      when(nextResult.receiptDigest()).thenReturn(GENERATION_RECEIPT_DIGEST);
      when(nextResult.desiredStateVersion()).thenReturn(2L);
      when(nextResult.targetGeneration()).thenReturn("2");
      when(nextResult.targetKid()).thenReturn("key_2");
      when(nextResult.targetAlgorithm()).thenReturn("RS256");
      when(nextResult.publicKeyFingerprint()).thenReturn(fingerprint(JWK_2));
      when(nextResult.publicJwkJson()).thenReturn(JWK_2);
      when(desiredStateRepository.readCurrentGenerationResult(eq(accountBinding), eq(trustFence)))
          .thenReturn(nextResult);

      String nextMarker =
          marker(NEXT_OPERATION_ID, "2", "key_2", fingerprint(JWK_2), Optional.of(active));
      nextPublication =
          publication(
              JWKS_1_AND_2,
              nextMarker,
              activeResourceVersion,
              nextResourceVersion,
              "2",
              "key_2",
              Optional.of(active));
      when(publicationRepository.readCurrentPublication(eq(accountBinding), eq(trustFence)))
          .thenReturn(Optional.of(nextPublication));
      when(configMapClient.observe())
          .thenReturn(
              new ConfigMapSnapshot(
                  CONFIG_MAP_UID,
                  nextResourceVersion,
                  Map.of(
                      AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
                      JWKS_1_AND_2,
                      AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
                      nextMarker,
                      "operator.note",
                      "preserved")));
    }

    private void removeCurrentPublicationReceipt() {
      PublicationEvidence unreceipted =
          new PublicationEvidence(nextPublication.intent(), Optional.empty(), Optional.empty());
      when(publicationRepository.readCurrentPublication(eq(accountBinding), eq(trustFence)))
          .thenReturn(Optional.of(unreceipted));
    }

    private void replaceNextJwks(String jwks) {
      String nextMarker =
          marker(
              NEXT_OPERATION_ID,
              "2",
              "key_2",
              fingerprint(JWK_2),
              Optional.of(new ActiveSigner("1", "key_1")));
      nextPublication =
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
              AccountJwtJwksPublicationRepository.snapshotDigest(
                  expectedActive.isEmpty()
                      ? INITIAL_PRIOR_DATA
                      : Map.of(
                          AccountJwtJwksPublicationRepository.JWKS_DATA_KEY,
                          JWKS_1,
                          AccountJwtJwksPublicationRepository.GENERATION_MARKER_DATA_KEY,
                          activeMarker(expectedActive.orElseThrow()),
                          "operator.note",
                          "preserved")),
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
    try {
      var mapper = JsonMapper.builder().build();
      var key = mapper.readTree(jwk);
      String preimage =
          mapper.writeValueAsString(
              Map.of(
                  "e", key.path("e").asText(),
                  "kty", key.path("kty").asText(),
                  "n", key.path("n").asText()));
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(Rfc8785CanonicalJson.canonicalizeUtf8(preimage)));
    } catch (Exception unavailable) {
      throw new AssertionError(unavailable);
    }
  }
}
