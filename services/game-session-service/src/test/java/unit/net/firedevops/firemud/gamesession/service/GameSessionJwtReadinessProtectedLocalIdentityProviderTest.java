package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.account.v1.AccountSourceIdentity;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataResponse;
import net.firedevops.firemud.account.v1.ReadinessReceiverLocalIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameSessionJwtReadinessProtectedLocalIdentityProviderTest {
  private static final String NAMESPACE = "firemud-prod";
  private static final String SERVICE_URI =
      "spiffe://firemud/ns/firemud-prod/sa/game-session-service";
  private static final String POD_UID = "44444444-4444-4444-8444-444444444444";
  private static final String OTHER_POD_UID = "55555555-5555-4555-8555-555555555555";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2030-06-01T00:10:00Z"), ZoneId.of("UTC"));

  @Test
  void returnsAccountSelectedCurrentMetadataBracketingTheAuthenticatedReadWithLocalPins()
      throws Exception {
    Fixture fixture = new Fixture();
    AtomicInteger uidReads = new AtomicInteger();
    var provider =
        fixture.provider(
            () -> {
              uidReads.incrementAndGet();
              return POD_UID;
            });

    var observation = provider.observe();

    assertThat(observation.wireIdentity()).isEqualTo(fixture.identity);
    assertThat(observation.jwksSourceIdentity()).isEqualTo(fixture.sourceIdentity);
    assertThat(observation.wireIdentity().getPodUid()).isEqualTo(POD_UID);
    ArgumentCaptor<GetCurrentReadinessReceiverMetadataRequest> request =
        ArgumentCaptor.forClass(GetCurrentReadinessReceiverMetadataRequest.class);
    verify(fixture.metadataReadPort).readCurrent(request.capture());
    assertThat(request.getValue().getSchemaVersion()).isEqualTo(1);
    assertThat(request.getValue().getProjectedPodUid()).isEqualTo(POD_UID);
    assertThat(request.getValue().getServerLeafSpkiSha256()).isEqualTo(fixture.leafDigest);
    assertThat(request.getValue().getUnknownFields().asMap()).isEmpty();
    assertThat(uidReads.get()).isEqualTo(2);
  }

  @Test
  void deniesWhenProjectedPodUidChangesAroundCurrentMetadataRead() throws Exception {
    Fixture fixture = new Fixture();
    AtomicInteger reads = new AtomicInteger();
    var provider = fixture.provider(() -> reads.getAndIncrement() == 0 ? POD_UID : OTHER_POD_UID);

    assertUnavailable(provider);
    assertThat(reads.get()).isEqualTo(2);
  }

  @Test
  void deniesWhenActiveTlsLeafChangesAroundCurrentMetadataRead() throws Exception {
    Fixture fixture = new Fixture();
    fixture.useDifferentSecondLeaf();
    var provider = fixture.provider(() -> POD_UID);

    assertUnavailable(provider);
  }

  @Test
  void deniesMissingProtectedUidWithoutCallingAccount() throws Exception {
    Fixture fixture = new Fixture();
    var provider = fixture.provider(() -> null);

    assertUnavailable(provider);
    verify(fixture.metadataReadPort, never()).readCurrent(any());
  }

  @Test
  void deniesUnavailableAccountMetadataTransport() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.metadataReadPort.readCurrent(any()))
        .thenThrow(new GameSessionJwtReadinessProbeOwnerClient.OwnerReadUnavailableException());
    var provider = fixture.provider(() -> POD_UID);

    assertUnavailable(provider);
  }

  @Test
  void deniesObservationInsideAmbientSqlTransactionBeforeMetadataRead() throws Exception {
    Fixture fixture = new Fixture();
    var provider = fixture.provider(() -> POD_UID);
    boolean previousTransactionState =
        TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertUnavailable(provider);
      verify(fixture.metadataReadPort, never()).readCurrent(any());
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previousTransactionState);
    }
  }

  @Test
  void deniesNonGameSessionLocalTlsPeerBeforeAccountRead() throws Exception {
    Fixture fixture = new Fixture();
    fixture.useCertificateWithUri("spiffe://firemud/ns/firemud-prod/sa/game-design-service");
    var provider = fixture.provider(() -> POD_UID);

    assertUnavailable(provider);
    verify(fixture.metadataReadPort, never()).readCurrent(any());
  }

  @Test
  void rejectsWrongUidLeafValidatorSourceNamespaceRevisionAndPublicJwksHash() throws Exception {
    Fixture fixture = new Fixture();
    List<GetCurrentReadinessReceiverMetadataResponse> rejectedResponses =
        List.of(
            fixture.response(fixture.identity.toBuilder().setPodUid(OTHER_POD_UID).build()),
            fixture.response(
                fixture.identity.toBuilder().setServerLeafSpkiSha256("f".repeat(64)).build()),
            fixture.response(
                fixture.identity.toBuilder().setValidatorId("account-service").build()),
            fixture.response(
                fixture.identity.toBuilder()
                    .setCanonicalServiceUri("spiffe://firemud/ns/other-ns/sa/game-session-service")
                    .build()),
            fixture.response(
                fixture.identity.toBuilder()
                    .setAccountJwksSourceIdentity(
                        fixture.sourceProto.toBuilder().setNamespace("other-ns").build())
                    .build()),
            fixture.response(
                fixture.identity.toBuilder()
                    .setAccountJwksSourceIdentity(
                        fixture.sourceProto.toBuilder().setConfigMapUid("invalid").build())
                    .build()),
            fixture.response(fixture.identity.toBuilder().clearAccountJwksSourceIdentity().build()),
            fixture.response(
                fixture.identity.toBuilder()
                    .setAccountJwksTrustBindingRevision("changed-revision")
                    .build()),
            fixture.response(
                fixture.identity.toBuilder().setAccountPublicJwksSha256("z".repeat(64)).build()));

    for (GetCurrentReadinessReceiverMetadataResponse rejected : rejectedResponses) {
      when(fixture.metadataReadPort.readCurrent(any())).thenReturn(rejected);
      assertUnavailable(fixture.provider(() -> POD_UID));
    }
  }

  @Test
  void rejectsInvalidTargetShapeAndPreservesCanonicalIpv6PodEndpoints() throws Exception {
    Fixture fixture = new Fixture();
    List<ReadinessReceiverLocalIdentity> rejectedIdentities =
        List.of(
            fixture.identity.toBuilder().setDeploymentUid("deployment").build(),
            fixture.identity.toBuilder().setImage("registry.example/game-session:latest").build(),
            fixture.identity.toBuilder().setSourceInventoryRevision(" ").build(),
            fixture.identity.toBuilder().setSourceInventoryRevision("r".repeat(129)).build(),
            fixture.identity.toBuilder().setPodIp("10.0.0.13").build(),
            fixture.identity.toBuilder().setDirectPodEndpoint("https://10.0.0.12:6565").build(),
            fixture.identity.toBuilder().setDirectPodEndpoint("grpcs://10.0.0.12:06565").build(),
            fixture.identity.toBuilder()
                .setDirectPodEndpoint("grpcs://user@10.0.0.12:6565")
                .build(),
            fixture.identity.toBuilder()
                .setDirectPodEndpoint("grpcs://10.0.0.12:6565?route=other")
                .build(),
            fixture.identity.toBuilder()
                .setDirectPodEndpoint("grpcs://10.0.0.12:6565/path")
                .build(),
            fixture.identity.toBuilder().setPodIp("010.0.0.12").build());

    for (ReadinessReceiverLocalIdentity rejected : rejectedIdentities) {
      when(fixture.metadataReadPort.readCurrent(any())).thenReturn(fixture.response(rejected));
      assertUnavailable(fixture.provider(() -> POD_UID));
    }

    ReadinessReceiverLocalIdentity ipv6Identity =
        fixture.identity.toBuilder()
            .setPodIp("2001:db8::12")
            .setDirectPodEndpoint("grpcs://[2001:db8::12]:6565")
            .build();
    when(fixture.metadataReadPort.readCurrent(any())).thenReturn(fixture.response(ipv6Identity));

    assertThat(fixture.provider(() -> POD_UID).observe().wireIdentity()).isEqualTo(ipv6Identity);
  }

  @Test
  void rejectsUnknownFieldsAndMalformedCurrentOperationMetadata() throws Exception {
    Fixture fixture = new Fixture();
    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    List<GetCurrentReadinessReceiverMetadataResponse> rejectedResponses =
        List.of(
            fixture.response(fixture.identity, response -> response.setUnknownFields(unknown)),
            fixture.response(fixture.identity.toBuilder().setUnknownFields(unknown).build()),
            fixture.response(
                fixture.identity.toBuilder()
                    .setAccountJwksSourceIdentity(
                        fixture.sourceProto.toBuilder().setUnknownFields(unknown).build())
                    .build()),
            fixture.response(fixture.identity, response -> response.setRotationOperationId("1")),
            fixture.response(
                fixture.identity,
                response ->
                    response.setRotationOperationId("11111111-1111-1111-8111-111111111111")),
            fixture.response(
                fixture.identity, response -> response.setOperationDigest("A".repeat(64))),
            fixture.response(fixture.identity, response -> response.setPlanDigest("not-a-digest")),
            fixture.response(fixture.identity, response -> response.setSchemaVersion(2)));

    for (GetCurrentReadinessReceiverMetadataResponse rejected : rejectedResponses) {
      when(fixture.metadataReadPort.readCurrent(any())).thenReturn(rejected);
      assertUnavailable(fixture.provider(() -> POD_UID));
    }
  }

  private static void assertUnavailable(
      GameSessionJwtReadinessProtectedLocalIdentityProvider provider) {
    assertThatThrownBy(provider::observe)
        .isInstanceOf(
            GameSessionJwtReadinessProtectedLocalIdentityProvider.IdentityUnavailableException
                .class);
  }

  private static final class Fixture {
    private final byte[] encodedPublicKey =
        "game-session server public key".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private final String leafDigest = sha256(encodedPublicKey);
    private final String publicJwksDigest =
        sha256(
            "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"fixture\"}]}"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    private final GameSessionJwtReadinessReceiverMetadataReadPort metadataReadPort =
        mock(GameSessionJwtReadinessReceiverMetadataReadPort.class);
    private final SslBundles sslBundles = mock(SslBundles.class);
    private final KeyStore keyStore = mock(KeyStore.class);
    private final X509Certificate certificate = certificate(SERVICE_URI, encodedPublicKey);
    private final AccountSourceIdentity sourceProto = sourceProto();
    private final SourceIdentity sourceIdentity = sourceIdentity(sourceProto);
    private final ReadinessReceiverLocalIdentity identity = identity();

    private Fixture() throws Exception {
      when(keyStore.aliases())
          .thenAnswer(ignored -> Collections.enumeration(List.of("game-session")));
      when(keyStore.isKeyEntry("game-session")).thenReturn(true);
      when(keyStore.getCertificate("game-session")).thenReturn(certificate);
      SslStoreBundle stores = mock(SslStoreBundle.class);
      when(stores.getKeyStore()).thenReturn(keyStore);
      SslBundle bundle = mock(SslBundle.class);
      when(bundle.getStores()).thenReturn(stores);
      when(sslBundles.getBundle("firemud-grpc")).thenReturn(bundle);
      when(metadataReadPort.readCurrent(any())).thenReturn(response(identity));
    }

    private GameSessionJwtReadinessProtectedLocalIdentityProvider provider(
        Supplier<String> projectedUidSource) {
      return new GameSessionJwtReadinessProtectedLocalIdentityProvider(
          metadataReadPort, sslBundles, NAMESPACE, CLOCK, projectedUidSource);
    }

    private void useDifferentSecondLeaf() throws Exception {
      X509Certificate secondCertificate =
          certificate(
              SERVICE_URI,
              "different server public key".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      when(keyStore.getCertificate("game-session")).thenReturn(certificate, secondCertificate);
    }

    private void useCertificateWithUri(String uri) throws Exception {
      X509Certificate replacement = certificate(uri, encodedPublicKey);
      when(keyStore.getCertificate("game-session")).thenReturn(replacement);
    }

    private ReadinessReceiverLocalIdentity identity() {
      return ReadinessReceiverLocalIdentity.newBuilder()
          .setValidatorId("game-session-service")
          .setDeploymentUid("33333333-3333-4333-8333-333333333333")
          .setPodUid(POD_UID)
          .setPodIp("10.0.0.12")
          .setDirectPodEndpoint("grpcs://10.0.0.12:6565")
          .setCanonicalServiceUri(SERVICE_URI)
          .setImage("registry.example/game-session@sha256:" + "a".repeat(64))
          .setVerifierConfigSha256("b".repeat(64))
          .setApplicabilityMatrixDigest("c".repeat(64))
          .setSourceInventoryRevision("inventory-r1")
          .setSourceInventoryDigest("d".repeat(64))
          .setServerLeafSpkiSha256(leafDigest)
          .setAccountJwksSourceIdentity(sourceProto)
          .setAccountJwksTrustBindingRevision(sourceIdentity.bindingRevision())
          .setAccountPublicJwksSha256(publicJwksDigest)
          .build();
    }

    private GetCurrentReadinessReceiverMetadataResponse response(
        ReadinessReceiverLocalIdentity value) {
      return response(value, ignored -> {});
    }

    private GetCurrentReadinessReceiverMetadataResponse response(
        ReadinessReceiverLocalIdentity value,
        java.util.function.Consumer<GetCurrentReadinessReceiverMetadataResponse.Builder> change) {
      var builder =
          GetCurrentReadinessReceiverMetadataResponse.newBuilder()
              .setSchemaVersion(1)
              .setRotationOperationId("66666666-6666-4666-8666-666666666666")
              .setOperationDigest("e".repeat(64))
              .setPlanDigest("f".repeat(64))
              .setCurrentIdentity(value);
      change.accept(builder);
      return builder.build();
    }

    private AccountSourceIdentity sourceProto() {
      return AccountSourceIdentity.newBuilder()
          .setEnvironmentId("prod")
          .setClusterId("cluster-a")
          .setClusterIncarnationUid("11111111-1111-4111-8111-111111111111")
          .setNamespace(NAMESPACE)
          .setNamespaceUid("22222222-2222-4222-8222-222222222222")
          .setConfigMapUid("55555555-5555-4555-8555-555555555555")
          .setBindingRevision("account-api-r1")
          .setApiServerOrigin("https://kubernetes.example.test:6443")
          .setServingCaSha256("9".repeat(64))
          .build();
    }

    private static SourceIdentity sourceIdentity(AccountSourceIdentity source) {
      return new SourceIdentity(
          source.getEnvironmentId(),
          source.getClusterId(),
          source.getClusterIncarnationUid(),
          source.getNamespace(),
          source.getNamespaceUid(),
          source.getConfigMapUid(),
          source.getBindingRevision(),
          source.getApiServerOrigin(),
          source.getServingCaSha256());
    }
  }

  private static X509Certificate certificate(String uri, byte[] encodedPublicKey) throws Exception {
    X509Certificate certificate = mock(X509Certificate.class);
    PublicKey publicKey = mock(PublicKey.class);
    when(certificate.getPublicKey()).thenReturn(publicKey);
    when(publicKey.getEncoded()).thenReturn(encodedPublicKey);
    when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, uri)));
    return certificate;
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception unavailable) {
      throw new AssertionError(unavailable);
    }
  }
}
