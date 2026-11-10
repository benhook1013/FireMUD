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
  void allowsAbsentKeyOperationsOnlyForTrustedPublishedKeysAndRejectsOtherShapes() {
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

    for (String malformedJwk :
        List.of(
            JWK_1.replace("\"use\":\"sig\"", "\"use\":\"sig\",\"unexpected\":true"),
            JWK_1.replace("\"use\":\"sig\"", "\"use\":\"sig\",\"d\":\"AQID\""),
            JWK_1.replace("\"n\":\"AQID\",", ""),
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
      when(nextRequest.targetGeneration()).thenReturn("2");
      when(nextRequest.targetKid()).thenReturn("key_2");
      when(nextRequest.publicKeyFingerprint()).thenReturn(fingerprint(JWK_2));
      when(nextRequest.expectedActive()).thenReturn(Optional.of(active));
      when(nextRequest.expectedPublishedActive()).thenReturn(Optional.of(active));
      when(desiredStateRepository.readCurrentGenerationRequest(eq(accountBinding), eq(trustFence)))
          .thenReturn(nextRequest);

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
