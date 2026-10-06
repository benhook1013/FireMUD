package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountJwtSignerMaterializerTrustBindingTest {
  @Test
  void strictProtectedFormatBindsClusterNamespaceUidsUriPinsAndRevision() {
    String config =
        protectedConfig("prod", "prod-cluster-1", "firemud-prod", "revision-7", "a".repeat(64));

    var binding =
        AccountJwtSignerMaterializerTrustBinding.parseProtectedBytes(
            config.getBytes(StandardCharsets.UTF_8));

    assertThat(binding.environmentId()).isEqualTo("prod");
    assertThat(binding.clusterId()).isEqualTo("prod-cluster-1");
    assertThat(binding.namespace()).isEqualTo("firemud-prod");
    assertThat(binding.expectedClusterIncarnationUid())
        .isEqualTo("11111111-1111-4111-8111-111111111111");
    assertThat(binding.expectedNamespaceUid()).isEqualTo("22222222-2222-4222-8222-222222222222");
    assertThat(binding.expectedPeerUri())
        .isEqualTo("spiffe://firemud/ns/firemud-prod/sa/jwt-signer-materializer");
    assertThat(binding.peerSpkiSha256Pins()).containsExactly("a".repeat(64));
    assertThat(binding.bindingDigest()).matches("[0-9a-f]{64}");
  }

  @Test
  void disabledOrIncompleteProtectedBindingCannotAuthorizeMaterializer() {
    assertThatThrownBy(
            () ->
                AccountJwtSignerMaterializerTrustBinding.parseProtectedBytes(
                    "enabled=false\n".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountJwtSignerMaterializerTrustBinding.parseProtectedBytes(
                    protectedConfig("prod", "prod-cluster-1", "firemud-prod", "revision-7", "")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void duplicateFieldsUnknownFieldsAndWrongNamespaceUriAreRejected() {
    String valid =
        protectedConfig("prod", "prod-cluster-1", "firemud-prod", "revision-7", "a".repeat(64));
    assertThatThrownBy(
            () ->
                AccountJwtSignerMaterializerTrustBinding.parseProtectedBytes(
                    (valid + "clusterId=prod-cluster-1\n").getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountJwtSignerMaterializerTrustBinding.parseProtectedBytes(
                    (valid + "anotherField=not-accepted\n").getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);

    String wrongNamespaceUri =
        valid.replace(
            "spiffe://firemud/ns/firemud-prod/sa/jwt-signer-materializer",
            "spiffe://firemud/ns/other-namespace/sa/jwt-signer-materializer");
    assertThatThrownBy(
            () ->
                AccountJwtSignerMaterializerTrustBinding.parseProtectedBytes(
                    wrongNamespaceUri.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String protectedConfig(
      String environment, String cluster, String namespace, String revision, String pin) {
    String clusterUid = "11111111-1111-4111-8111-111111111111";
    String namespaceUid = "22222222-2222-4222-8222-222222222222";
    String uri = "spiffe://firemud/ns/" + namespace + "/sa/jwt-signer-materializer";
    List<String> pins = pin.isEmpty() ? List.of() : List.of(pin);
    String digest =
        AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
            revision, environment, cluster, namespace, clusterUid, namespaceUid, uri, pins);
    return "enabled=true\n"
        + "configRevision="
        + revision
        + "\n"
        + "environmentId="
        + environment
        + "\n"
        + "clusterId="
        + cluster
        + "\n"
        + "namespace="
        + namespace
        + "\n"
        + "expectedClusterIncarnationUid="
        + clusterUid
        + "\n"
        + "expectedNamespaceUid="
        + namespaceUid
        + "\n"
        + "expectedPeerUri="
        + uri
        + "\n"
        + "peerSpkiSha256Pins="
        + String.join(",", pins)
        + "\n"
        + "bindingDigest="
        + digest
        + "\n";
  }
}
