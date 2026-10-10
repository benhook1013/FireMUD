package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountJwtReadinessTrustBindingTest {
  private static final long NOW = 1_800_000_000L;

  @Test
  void missingOrDisabledConfigurationNeverCreatesReadinessTrust() {
    assertThat(new AccountJwtReadinessTrustBinding(false, "/unused", Clock.systemUTC()).current())
        .isEmpty();
    assertThat(new AccountJwtReadinessTrustBinding(true, "", Clock.systemUTC()).current())
        .isEmpty();
  }

  @Test
  void strictBindingPinsAccountRuntimeAndDedicatedHarnessIdentity() {
    var binding =
        AccountJwtReadinessTrustBinding.parseProtectedBytes(
            protectedConfig(NOW + 60).getBytes(StandardCharsets.UTF_8), NOW);

    assertThat(binding.environmentId()).isEqualTo("prod");
    assertThat(binding.clusterId()).isEqualTo("prod-cluster-1");
    assertThat(binding.namespace()).isEqualTo("firemud-prod");
    assertThat(binding.expectedPeerUri())
        .isEqualTo("spiffe://firemud/ns/firemud-prod/sa/account-jwt-readiness-harness");
    assertThat(binding.validatorId()).isEqualTo("account-service");
    assertThat(binding.validatorInstanceId()).isEqualTo("account-validator-instance-7");
    assertThat(binding.validUntilEpochSecond()).isEqualTo(NOW + 60);
    assertThat(binding.isCurrentAt(NOW)).isTrue();
    assertThat(binding.isCurrentAt(NOW + 60)).isFalse();
  }

  @Test
  void missingExpiredDuplicateUnknownAndChangedIdentityBindingsAreRejected() {
    String valid = protectedConfig(NOW + 60);
    assertThatThrownBy(
            () ->
                AccountJwtReadinessTrustBinding.parseProtectedBytes(
                    valid.getBytes(StandardCharsets.UTF_8), NOW + 60))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountJwtReadinessTrustBinding.parseProtectedBytes(
                    (valid + "validatorInstanceId=other\n").getBytes(StandardCharsets.UTF_8), NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountJwtReadinessTrustBinding.parseProtectedBytes(
                    (valid + "untrusted=true\n").getBytes(StandardCharsets.UTF_8), NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountJwtReadinessTrustBinding.parseProtectedBytes(
                    valid
                        .replace(
                            "validatorInstanceId=account-validator-instance-7",
                            "validatorInstanceId=other")
                        .getBytes(StandardCharsets.UTF_8),
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountJwtReadinessTrustBinding.parseProtectedBytes(
                    valid.replace("enabled=true", "enabled=false").getBytes(StandardCharsets.UTF_8),
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String protectedConfig(long validUntil) {
    String clusterUid = "11111111-1111-4111-8111-111111111111";
    String namespaceUid = "22222222-2222-4222-8222-222222222222";
    String uri = "spiffe://firemud/ns/firemud-prod/sa/account-jwt-readiness-harness";
    List<String> pins = List.of("a".repeat(64));
    String digest =
        AccountJwtReadinessTrustBinding.computeBindingDigest(
            "revision-7",
            "prod",
            "prod-cluster-1",
            "firemud-prod",
            clusterUid,
            namespaceUid,
            uri,
            pins,
            "account-validator-instance-7",
            validUntil);
    return "enabled=true\n"
        + "configRevision=revision-7\n"
        + "environmentId=prod\n"
        + "clusterId=prod-cluster-1\n"
        + "namespace=firemud-prod\n"
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
        + "validatorId=account-service\n"
        + "validatorInstanceId=account-validator-instance-7\n"
        + "validUntilEpochSecond="
        + validUntil
        + "\n"
        + "bindingDigest="
        + digest
        + "\n";
  }
}
