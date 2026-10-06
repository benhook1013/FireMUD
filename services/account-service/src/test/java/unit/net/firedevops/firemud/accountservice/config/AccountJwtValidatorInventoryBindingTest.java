package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ParsedBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding.ProtectedInventory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AccountJwtValidatorInventoryBindingTest {
  private static final String API_DIGEST = "a".repeat(64);
  private static final String IMAGE = "registry.example/firemud/player@sha256:" + "b".repeat(64);
  private static final String CLUSTER_UID = "11111111-1111-4111-8111-111111111111";
  private static final String NAMESPACE_UID = "22222222-2222-4222-8222-222222222222";
  private static final String DEPLOYMENT_UID = "33333333-3333-4333-8333-333333333333";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void defaultBindingIsInactiveWithoutReadingFilesystem() {
    assertThat(new AccountJwtValidatorInventoryBinding().current()).isEmpty();
  }

  @Test
  void parsesOnlyCompletePinnedRs256ApplicabilityAndProducesCanonicalRuntimeConfig() {
    ProtectedInventory inventory =
        AccountJwtValidatorInventoryBinding.parseProtectedBytes(validBindingBytes());

    assertThat(inventory.environmentId()).isEqualTo("test");
    assertThat(inventory.clusterId()).isEqualTo("cluster-test");
    assertThat(inventory.namespace()).isEqualTo("firemud-test");
    assertThat(inventory.expectedClusterIncarnationUid()).isEqualTo(CLUSTER_UID);
    assertThat(inventory.expectedNamespaceUid()).isEqualTo(NAMESPACE_UID);
    assertThat(inventory.validators()).hasSize(1);
    var validator = inventory.validators().get(0);
    assertThat(validator.deploymentUid()).isEqualTo(DEPLOYMENT_UID);
    assertThat(validator.image()).isEqualTo(IMAGE);
    assertThat(validator.canonicalRuntimeConfig())
        .contains("\"algorithm\":\"RS256\"")
        .contains("\"maxCacheAgeSeconds\":60")
        .contains("\"jwksUri\":\"https://account-api.test/.well-known/jwks.json\"");

    ParsedBinding matchingApi =
        new ParsedBinding(
            "api-r1",
            "test",
            "cluster-test",
            URI.create("https://kubernetes.test:6443"),
            "kubernetes.test",
            Path.of("/etc/firemud/account-jwt-api/serving-ca.pem"),
            "c".repeat(64),
            Path.of("/var/run/secrets/firemud/account-jwt-api-token/token"),
            "firemud-test",
            CLUSTER_UID,
            NAMESPACE_UID,
            "system:serviceaccount:firemud-test:account-service",
            API_DIGEST);
    assertThat(inventory.matchesApiBinding(matchingApi)).isTrue();
    assertThat(inventory.matchesApiBinding(null)).isFalse();
  }

  @Test
  void rejectsDigestTamperingUnknownFieldsAndUnpinnedImage() throws Exception {
    byte[] valid = validBindingBytes();
    assertThatThrownBy(
            () ->
                AccountJwtValidatorInventoryBinding.parseProtectedBytes(
                    new String(valid, StandardCharsets.UTF_8)
                        .replace("cluster-test", "other-cluster")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalStateException.class);

    Map<String, Object> withUnknown = parseMap(valid);
    withUnknown.put("ambientTrust", true);
    assertThatThrownBy(
            () ->
                AccountJwtValidatorInventoryBinding.parseProtectedBytes(
                    JSON.writeValueAsBytes(withUnknown)))
        .isInstanceOf(IllegalStateException.class);

    byte[] unpinnedImage = validBindingBytes("registry.example/firemud/player:latest");
    assertThatThrownBy(() -> AccountJwtValidatorInventoryBinding.parseProtectedBytes(unpinnedImage))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsNonHttpsOrNonCanonicalJwksUri() {
    byte[] malformed = validBindingBytes("http://account-api.test/.well-known/jwks.json", IMAGE);
    assertThatThrownBy(() -> AccountJwtValidatorInventoryBinding.parseProtectedBytes(malformed))
        .isInstanceOf(IllegalStateException.class);
  }

  private static byte[] validBindingBytes() {
    return validBindingBytes("https://account-api.test/.well-known/jwks.json", IMAGE);
  }

  private static byte[] validBindingBytes(String image) {
    return validBindingBytes("https://account-api.test/.well-known/jwks.json", image);
  }

  private static byte[] validBindingBytes(String jwksUri, String image) {
    Map<String, Object> profile =
        Map.of("tokenProfile", "game-session-account-delegation", "audience", "account-service");
    Map<String, Object> validator =
        Map.of(
            "validatorId",
            "player-service",
            "deploymentName",
            "player-service",
            "deploymentUid",
            DEPLOYMENT_UID,
            "selector",
            Map.of("app", "player-service"),
            "replicas",
            2,
            "containerName",
            "player-service",
            "image",
            image,
            "jwksUri",
            jwksUri,
            "maxCacheAgeSeconds",
            60,
            "profiles",
            List.of(profile));
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("version", "account-jwt-validator-inventory-binding/v1");
    root.put("enabled", "true");
    root.put("configRevision", "inventory-r1");
    root.put("environmentId", "test");
    root.put("clusterId", "cluster-test");
    root.put("namespace", "firemud-test");
    root.put("expectedClusterIncarnationUid", CLUSTER_UID);
    root.put("expectedNamespaceUid", NAMESPACE_UID);
    root.put("apiBindingRevision", "api-r1");
    root.put("apiBindingDigest", API_DIGEST);
    root.put("validators", List.of(validator));
    root.put("bindingDigest", "0".repeat(64));
    try {
      JsonNode unsigned = JSON.readTree(JSON.writeValueAsBytes(root));
      root.put("bindingDigest", AccountJwtValidatorInventoryBinding.computeBindingDigest(unsigned));
      return JSON.writeValueAsBytes(root);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseMap(byte[] value) throws Exception {
    JsonNode root = JSON.readTree(value);
    return JSON.readValue(JSON.writeValueAsBytes(root), Map.class);
  }
}
