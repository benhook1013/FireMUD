package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.lettuce.core.RedisURI;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Controlled configuration proof only; no production credentials or protected mounts are read. */
class AccountGameplayLoginProtectedSourcesConfigurationTest {
  static final String API_PATH = "/etc/firemud/account-jwt-api/binding.json";
  private final AccountGameplayLoginProtectedSourcesConfiguration configuration =
      new AccountGameplayLoginProtectedSourcesConfiguration();

  @Test
  void dedicatedUriRetainsExplicitTlsAclDatabaseAndCommandDeadline() {
    RedisURI uri =
        AccountGameplayLoginProtectedSourcesConfiguration.requiredCoordinationUri(
            "rediss://account_coord_app:test-only-password@coordination.example.invalid:6380/0",
            875);

    assertThat(uri.getHost()).isEqualTo("coordination.example.invalid");
    assertThat(uri.getPort()).isEqualTo(6380);
    assertThat(uri.getDatabase()).isZero();
    assertThat(uri.isSsl()).isTrue();
    assertThat(uri.isVerifyPeer()).isTrue();
    var credentials =
        uri.getCredentialsProvider().resolveCredentials().block(Duration.ofSeconds(1));
    assertThat(credentials).isNotNull();
    assertThat(credentials.getUsername()).isEqualTo("account_coord_app");
    assertThat(credentials.getPassword()).containsExactly("test-only-password".toCharArray());
    assertThat(uri.getTimeout()).isEqualTo(Duration.ofMillis(875));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "redis://account_coord_app:secret@coordination.example.invalid:6380/0",
        "rediss://default:secret@coordination.example.invalid:6380/0",
        "rediss://account_coord_app:@coordination.example.invalid:6380/0",
        "rediss://account_coord_app:secret@coordination.example.invalid/0",
        "rediss://account_coord_app:secret@coordination.example.invalid:6380",
        "rediss://account_coord_app:secret@coordination.example.invalid:6380/1",
        "rediss://account_coord_app:secret@coordination.example.invalid:6380/0?verifyPeer=false",
        "rediss://account_coord_app:secret@coordination.example.invalid:6380/0#extra",
        "rediss://account_coord_app:secret%0a@coordination.example.invalid:6380/0",
        "not a URI containing secret"
      })
  void refusesMissingAmbiguousOrWeakerUriWithoutRetainingCredentialCauses(String uri) {
    assertThatThrownBy(
            () ->
                AccountGameplayLoginProtectedSourcesConfiguration.requiredCoordinationUri(uri, 500))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Protected Account gameplay LOGIN configuration required")
        .hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 60_001})
  void commandDeadlineMustBeExplicitAndBounded(int timeout) {
    assertThatThrownBy(
            () ->
                AccountGameplayLoginProtectedSourcesConfiguration.requiredCoordinationUri(
                    "rediss://account_coord_app:secret@coordination.example.invalid:6380/0",
                    timeout))
        .isInstanceOf(IllegalArgumentException.class)
        .hasNoCause();
  }

  @Test
  void accountAcknowledgementsCannotUseTheGameSessionSingleNodeException() {
    var ack = configuration.accountGameplayLoginAcknowledgements(1, 2, 750);
    assertThat(ack.requiredLocalAofCount()).isEqualTo(1);
    assertThat(ack.requiredReplicaAofCount()).isEqualTo(2);
    assertThat(ack.timeoutMillis()).isEqualTo(750);
    assertThatThrownBy(() -> configuration.accountGameplayLoginAcknowledgements(1, 0, 750))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> configuration.accountGameplayLoginAcknowledgements(0, 1, 750))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> configuration.accountGameplayLoginAcknowledgements(1, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void protectedApiAndMaterializerMustMatchTheConfiguredNamespaceAndIncarnations() {
    var trust = mock(AccountJwtSignerMaterializerTrustBinding.class);
    when(trust.current()).thenReturn(Optional.of(materializerBinding()));
    try (var construction =
        mockConstruction(
            AccountJwtJwksApiBinding.class,
            (api, context) -> when(api.current()).thenReturn(apiBinding("test", "cluster-a")))) {
      assertThat(
              new AccountProtectedJwksSourceConfiguration()
                  .accountPublicJwksApiBinding(trust, API_PATH, "test"))
          .isSameAs(construction.constructed().getFirst());
      assertThatThrownBy(
              () ->
                  new AccountProtectedJwksSourceConfiguration()
                      .accountPublicJwksApiBinding(trust, API_PATH, "other"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasNoCause();
    }
    try (var construction =
        mockConstruction(
            AccountJwtJwksApiBinding.class,
            (api, context) ->
                when(api.current()).thenReturn(apiBinding("test", "other-cluster")))) {
      assertThatThrownBy(
              () ->
                  new AccountProtectedJwksSourceConfiguration()
                      .accountPublicJwksApiBinding(trust, API_PATH, "test"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasNoCause();
      assertThat(construction.constructed()).hasSize(1);
    }
  }

  @Test
  void missingWithdrawnOrAlternateProtectedSourcesNeverProduceAnApiBinding() {
    var trust = mock(AccountJwtSignerMaterializerTrustBinding.class);
    when(trust.current()).thenReturn(Optional.empty());
    try (var construction = mockConstruction(AccountJwtJwksApiBinding.class)) {
      assertThatThrownBy(
              () ->
                  new AccountProtectedJwksSourceConfiguration()
                      .accountPublicJwksApiBinding(trust, API_PATH, "test"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasNoCause();
      assertThat(construction.constructed()).hasSize(1);
    }
    when(trust.current()).thenReturn(Optional.of(materializerBinding()));
    try (var construction =
        mockConstruction(
            AccountJwtJwksApiBinding.class,
            (api, context) ->
                when(api.current())
                    .thenThrow(new AccountJwtJwksApiBinding.BindingRejectedException()))) {
      assertThatThrownBy(
              () ->
                  new AccountProtectedJwksSourceConfiguration()
                      .accountPublicJwksApiBinding(trust, API_PATH, "test"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasNoCause();
      assertThat(construction.constructed()).hasSize(1);
    }
    assertThatThrownBy(
            () ->
                new AccountProtectedJwksSourceConfiguration()
                    .accountPublicJwksApiBinding(trust, "/alternate/binding", "test"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountProtectedJwksSourceConfiguration()
                    .accountPublicJwksApiBinding(trust, API_PATH, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "relative/keyring", "/run/../other/keyring"})
  void missingRelativeOrUnnormalizedMountSelectorsFail(String path) {
    assertThatThrownBy(() -> AccountGameplayLoginProtectedSourcesConfiguration.requiredMount(path))
        .isInstanceOf(IllegalArgumentException.class)
        .hasNoCause();
  }

  static AccountJwtSignerMaterializerTrustBinding.Binding materializerBinding() {
    String clusterUid = "11111111-1111-4111-8111-111111111111";
    String namespaceUid = "22222222-2222-4222-8222-222222222222";
    String peer = "spiffe://firemud/ns/test/sa/account-jwt-signer-materializer";
    List<String> pins = List.of("a".repeat(64));
    String digest =
        AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
            "test-revision", "test", "cluster-a", "test", clusterUid, namespaceUid, peer, pins);
    return new AccountJwtSignerMaterializerTrustBinding.Binding(
        "test", "cluster-a", "test", clusterUid, namespaceUid, peer, pins, "test-revision", digest);
  }

  @SuppressFBWarnings(
      value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
      justification =
          "Fixed nonexistent paths identify controlled API metadata in this wiring-only test; no protected mount, token or certificate is read.")
  static AccountJwtJwksApiBinding.ParsedBinding apiBinding(String namespace, String cluster) {
    var trust = materializerBinding();
    return new AccountJwtJwksApiBinding.ParsedBinding(
        "test-api-revision",
        trust.environmentId(),
        cluster,
        URI.create("https://api.example.invalid:6443/"),
        "api.example.invalid",
        Path.of("/not-read-by-configuration-test/serving-ca.pem"),
        "b".repeat(64),
        Path.of("/not-read-by-configuration-test/token"),
        namespace,
        trust.expectedClusterIncarnationUid(),
        trust.expectedNamespaceUid(),
        "system:serviceaccount:" + namespace + ":account-service",
        "c".repeat(64));
  }
}
