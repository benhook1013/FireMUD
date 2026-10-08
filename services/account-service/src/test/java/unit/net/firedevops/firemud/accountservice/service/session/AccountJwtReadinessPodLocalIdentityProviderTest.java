package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
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
import java.util.UUID;
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding.ProfileExpectation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationContext;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationPurpose;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.PodObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ReplicaSetObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ValidatorObservation;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;

class AccountJwtReadinessPodLocalIdentityProviderTest {
  private static final String URI_SAN = "spiffe://firemud/ns/firemud-prod/sa/account-service";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2030-06-01T00:10:00Z"), ZoneId.of("UTC"));

  @Test
  void derivesLocalPodIdentityFromProtectedInventoryAndMatchingServerBundleLeaf() throws Exception {
    Fixture fixture = new Fixture(false);

    var identity = fixture.provider.observe("c".repeat(64));

    assertThat(identity.selectorIdentity().podUid()).isEqualTo(Fixture.POD_UID);
    assertThat(identity.selectorIdentity().deploymentUid()).isEqualTo(Fixture.DEPLOYMENT_UID);
    assertThat(identity.selectorIdentity().podIp()).isEqualTo("10.0.0.12");
    assertThat(identity.selectorIdentity().serverLeafSpkiSha256()).isEqualTo(fixture.leafSpki);
    assertThat(identity.wireIdentity().getJwksTrustBindingRevision()).isEqualTo("api-r1");
    assertThat(identity.wireIdentity().getAccountJwksSourceIdentity().getConfigMapUid())
        .isEqualTo("55555555-5555-4555-8555-555555555555");
  }

  @Test
  void localIdentityRereadUsesTheOwnerBoundInitialCandidateContext() throws Exception {
    Fixture fixture = new Fixture(false);
    ObservationContext context =
        new ObservationContext(
            ObservationPurpose.INITIAL_NO_ACTIVE_SIGNER_CANDIDATES,
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            "9".repeat(64));
    when(fixture.inventorySource.observe(context)).thenReturn(fixture.inventory);

    var identity = fixture.provider.observe("c".repeat(64), context);

    assertThat(identity.selectorIdentity().podUid()).isEqualTo(Fixture.POD_UID);
    assertThat(identity.wireIdentity().getSourceInventoryDigest()).isEqualTo("d".repeat(64));
    org.mockito.Mockito.verify(fixture.inventorySource).observe(context);
  }

  @Test
  void refusesWhenActualSslBundleLeafDoesNotMatchProtectedPodPin() throws Exception {
    Fixture fixture = new Fixture(true);

    assertThatThrownBy(() -> fixture.provider.observe("c".repeat(64)))
        .isInstanceOf(
            AccountJwtReadinessPodLocalIdentityProvider.IdentityUnavailableException.class);
  }

  @Test
  void refusesWhenLocalPodHasNoMatchingReplicaSet() throws Exception {
    Fixture fixture = new Fixture(false);
    when(fixture.validator.replicaSets()).thenReturn(List.of());

    assertIdentityUnavailable(fixture);
  }

  @Test
  void refusesWhenLocalPodOwnerUidDoesNotMatchReplicaSet() throws Exception {
    Fixture fixture = new Fixture(false);
    when(fixture.pod.ownerUid()).thenReturn("66666666-6666-4666-8666-666666666666");

    assertIdentityUnavailable(fixture);
  }

  @Test
  void refusesWhenLocalPodOwnerNameDoesNotMatchReplicaSet() throws Exception {
    Fixture fixture = new Fixture(false);
    when(fixture.pod.ownerName()).thenReturn("different-replicaset");

    assertIdentityUnavailable(fixture);
  }

  @Test
  void refusesWhenReplicaSetOwnerNamesWrongDeployment() throws Exception {
    Fixture fixture = new Fixture(false);
    when(fixture.replicaSet.ownerDeploymentUid())
        .thenReturn("66666666-6666-4666-8666-666666666666");

    assertIdentityUnavailable(fixture);
  }

  @Test
  void refusesDuplicateMatchingReplicaSetObservations() throws Exception {
    Fixture fixture = new Fixture(false);
    when(fixture.validator.replicaSets())
        .thenReturn(List.of(fixture.replicaSet, fixture.replicaSet));

    assertIdentityUnavailable(fixture);
  }

  @Test
  void refusesPodMockWhoseOwnerIsDeploymentInsteadOfReplicaSet() throws Exception {
    Fixture fixture = new Fixture(false);
    when(fixture.pod.ownerName()).thenReturn("account-service");
    when(fixture.pod.ownerUid()).thenReturn(Fixture.DEPLOYMENT_UID);

    assertIdentityUnavailable(fixture);
  }

  private static void assertIdentityUnavailable(Fixture fixture) {
    assertThatThrownBy(() -> fixture.provider.observe("c".repeat(64)))
        .isInstanceOf(
            AccountJwtReadinessPodLocalIdentityProvider.IdentityUnavailableException.class);
  }

  private static final class Fixture {
    private static final String POD_UID = "33333333-3333-4333-8333-333333333333";
    private static final String DEPLOYMENT_UID = "44444444-4444-4444-8444-444444444444";
    private static final String REPLICA_SET_UID = "77777777-7777-4777-8777-777777777777";
    private static final String TEMPLATE_HASH = "account12345";
    private final byte[] encodedPublicKey =
        "test server public key".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private final String leafSpki = sha256(encodedPublicKey);
    private final AccountJwtValidatorInventorySource inventorySource =
        mock(AccountJwtValidatorInventorySource.class);
    private final InventorySnapshot inventory = mock(InventorySnapshot.class);
    private final ValidatorObservation validator = mock(ValidatorObservation.class);
    private final PodObservation pod = mock(PodObservation.class);
    private final ReplicaSetObservation replicaSet = mock(ReplicaSetObservation.class);
    private final AccountJwtJwksTrustedSource trustedSource =
        mock(AccountJwtJwksTrustedSource.class);
    private final SslBundles sslBundles = mock(SslBundles.class);
    private final AccountJwtReadinessPodLocalIdentityProvider provider;

    private Fixture(boolean mismatchProtectedLeafPin) throws Exception {
      when(inventory.validators()).thenReturn(List.of(validator));
      when(inventory.environmentId()).thenReturn("prod");
      when(inventory.clusterId()).thenReturn("cluster-a");
      when(inventory.clusterIncarnationUid()).thenReturn("11111111-1111-4111-8111-111111111111");
      when(inventory.namespace()).thenReturn("firemud-prod");
      when(inventory.namespaceUid()).thenReturn("22222222-2222-4222-8222-222222222222");
      when(inventory.inventoryBindingRevision()).thenReturn("inventory-r1");
      when(inventory.digest()).thenReturn("d".repeat(64));
      when(validator.validatorId()).thenReturn("account-service");
      when(validator.deploymentName()).thenReturn("account-service");
      when(validator.deploymentUid()).thenReturn(DEPLOYMENT_UID);
      when(validator.image()).thenReturn("registry.example/account@sha256:" + "a".repeat(64));
      when(validator.verifierConfigSha256()).thenReturn("b".repeat(64));
      when(validator.profiles())
          .thenReturn(
              AccountJwtReadinessProbeCrypto.representativeProfiles().values().stream()
                  .map(value -> new ProfileExpectation(value.profile(), value.audience()))
                  .toList());
      when(validator.pods()).thenReturn(List.of(pod));
      when(validator.replicaSets()).thenReturn(List.of(replicaSet));
      when(pod.name()).thenReturn("account-0");
      when(pod.uid()).thenReturn(POD_UID);
      when(pod.ownerName()).thenReturn("account-service-abcde");
      when(pod.ownerUid()).thenReturn(REPLICA_SET_UID);
      when(pod.podTemplateHash()).thenReturn(TEMPLATE_HASH);
      when(pod.podIp()).thenReturn("10.0.0.12");
      when(pod.endpoint()).thenReturn(URI.create("https://10.0.0.12:6565"));
      when(pod.receiverServiceUri()).thenReturn(URI_SAN);
      when(pod.image()).thenReturn("registry.example/account@sha256:" + "a".repeat(64));
      when(pod.verifierConfigSha256()).thenReturn("b".repeat(64));
      when(pod.leafSpkiSha256()).thenReturn(mismatchProtectedLeafPin ? "f".repeat(64) : leafSpki);
      when(replicaSet.name()).thenReturn("account-service-abcde");
      when(replicaSet.uid()).thenReturn(REPLICA_SET_UID);
      when(replicaSet.ownerDeploymentName()).thenReturn("account-service");
      when(replicaSet.ownerDeploymentUid()).thenReturn(DEPLOYMENT_UID);
      when(replicaSet.podTemplateHash()).thenReturn(TEMPLATE_HASH);
      when(replicaSet.image()).thenReturn("registry.example/account@sha256:" + "a".repeat(64));
      when(replicaSet.verifierConfigSha256()).thenReturn("b".repeat(64));
      when(inventorySource.observe()).thenReturn(inventory);

      var sourceIdentity =
          new AccountPublicJwksCache.SourceIdentity(
              "prod",
              "cluster-a",
              "11111111-1111-4111-8111-111111111111",
              "firemud-prod",
              "22222222-2222-4222-8222-222222222222",
              "55555555-5555-4555-8555-555555555555",
              "api-r1",
              "https://kubernetes.example.test:6443",
              "9".repeat(64));
      when(trustedSource.load())
          .thenReturn(
              new AccountPublicJwksCache.PublicJwksSnapshot(
                  sourceIdentity,
                  "{\"keys\":[]}".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));

      KeyStore keyStore = mock(KeyStore.class);
      when(keyStore.aliases()).thenReturn(Collections.enumeration(List.of("account-server")));
      when(keyStore.isKeyEntry("account-server")).thenReturn(true);
      X509Certificate certificate = mock(X509Certificate.class);
      PublicKey publicKey = mock(PublicKey.class);
      when(certificate.getPublicKey()).thenReturn(publicKey);
      when(publicKey.getEncoded()).thenReturn(encodedPublicKey);
      when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, URI_SAN)));
      when(keyStore.getCertificate("account-server")).thenReturn(certificate);
      SslStoreBundle stores = mock(SslStoreBundle.class);
      when(stores.getKeyStore()).thenReturn(keyStore);
      SslBundle sslBundle = mock(SslBundle.class);
      when(sslBundle.getStores()).thenReturn(stores);
      when(sslBundles.getBundle("firemud-grpc")).thenReturn(sslBundle);
      provider =
          new AccountJwtReadinessPodLocalIdentityProvider(
              inventorySource, trustedSource, sslBundles, CLOCK, "account-0");
    }
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception unavailable) {
      throw new AssertionError(unavailable);
    }
  }
}
