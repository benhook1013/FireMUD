package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
  private static final String POD_UID_ONE = "44444444-4444-4444-8444-444444444444";
  private static final String POD_UID_TWO = "55555555-5555-4555-8555-555555555555";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void defaultBindingIsInactiveWithoutReadingFilesystem() {
    assertThat(new AccountJwtValidatorInventoryBinding().current()).isEmpty();
  }

  @Test
  void acceptsProtectedDirectoryWhenTheFullAncestorChainPasses() throws Exception {
    Path leaf = Path.of(".").toRealPath();
    var verifier = mock(AccountJwtValidatorInventoryBinding.ProtectedDirectoryVerifier.class);

    assertThatCode(
            () -> AccountJwtValidatorInventoryBinding.verifyProtectedDirectoryChain(leaf, verifier))
        .doesNotThrowAnyException();

    for (Path current = leaf; current != null; current = current.getParent()) {
      verify(verifier).verify(current);
    }
    verifyNoMoreInteractions(verifier);
  }

  @Test
  void rejectsWritableAncestorAfterAcceptingTheProtectedLeaf() throws Exception {
    Path leaf = Path.of(".").toRealPath();
    Path writableAncestor = leaf.getParent();
    var verifier = mock(AccountJwtValidatorInventoryBinding.ProtectedDirectoryVerifier.class);
    doAnswer(
            invocation -> {
              Path directory = invocation.getArgument(0);
              if (writableAncestor.equals(directory)) {
                throw new IllegalStateException("Directory is writable by an untrusted principal");
              }
              return null;
            })
        .when(verifier)
        .verify(any());

    assertThatThrownBy(
            () -> AccountJwtValidatorInventoryBinding.verifyProtectedDirectoryChain(leaf, verifier))
        .isInstanceOf(IllegalStateException.class);

    verify(verifier).verify(leaf);
    verify(verifier).verify(writableAncestor);
    verifyNoMoreInteractions(verifier);
  }

  @Test
  @SuppressFBWarnings(
      value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
      justification = "Canonical protected mount paths are fixed inputs for this binding proof")
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
    assertThat(validator.receiverServiceUri())
        .isEqualTo("spiffe://firemud/ns/firemud-test/sa/player-service");
    assertThat(validator.receiverPort()).isEqualTo(8443);
    assertThat(validator.receiverPods()).hasSize(2);
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

  @Test
  void rejectsUnsortedDuplicateAndNoncanonicalPodIdentityPins() throws Exception {
    Map<String, Object> unsorted = parseMap(validBindingBytes());
    @SuppressWarnings("unchecked")
    Map<String, Object> validator =
        (Map<String, Object>) ((List<?>) unsorted.get("validators")).get(0);
    List<?> pins = (List<?>) validator.get("receiverPods");
    validator.put("receiverPods", List.of(pins.get(1), pins.get(0)));
    rebindDigest(unsorted);
    assertThatThrownBy(
            () ->
                AccountJwtValidatorInventoryBinding.parseProtectedBytes(
                    JSON.writeValueAsBytes(unsorted)))
        .isInstanceOf(IllegalStateException.class);

    Map<String, Object> noncanonicalIp = parseMap(validBindingBytes());
    @SuppressWarnings("unchecked")
    Map<String, Object> noncanonicalValidator =
        (Map<String, Object>) ((List<?>) noncanonicalIp.get("validators")).get(0);
    @SuppressWarnings("unchecked")
    Map<String, Object> firstPin =
        (Map<String, Object>) ((List<?>) noncanonicalValidator.get("receiverPods")).get(0);
    firstPin.put("podIp", "010.0.0.10");
    rebindDigest(noncanonicalIp);
    assertThatThrownBy(
            () ->
                AccountJwtValidatorInventoryBinding.parseProtectedBytes(
                    JSON.writeValueAsBytes(noncanonicalIp)))
        .isInstanceOf(IllegalStateException.class);

    Map<String, Object> uppercaseSpki = parseMap(validBindingBytes());
    @SuppressWarnings("unchecked")
    Map<String, Object> uppercaseValidator =
        (Map<String, Object>) ((List<?>) uppercaseSpki.get("validators")).get(0);
    @SuppressWarnings("unchecked")
    Map<String, Object> secondPin =
        (Map<String, Object>) ((List<?>) uppercaseValidator.get("receiverPods")).get(1);
    secondPin.put("leafSpkiSha256", "D".repeat(64));
    rebindDigest(uppercaseSpki);
    assertThatThrownBy(
            () ->
                AccountJwtValidatorInventoryBinding.parseProtectedBytes(
                    JSON.writeValueAsBytes(uppercaseSpki)))
        .isInstanceOf(IllegalStateException.class);

    Map<String, Object> sharedPodKey = parseMap(validBindingBytes());
    @SuppressWarnings("unchecked")
    Map<String, Object> sharedKeyValidator =
        (Map<String, Object>) ((List<?>) sharedPodKey.get("validators")).get(0);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> sharedKeyPins =
        (List<Map<String, Object>>) sharedKeyValidator.get("receiverPods");
    sharedKeyPins.get(1).put("leafSpkiSha256", sharedKeyPins.get(0).get("leafSpkiSha256"));
    rebindDigest(sharedPodKey);
    assertThatThrownBy(
            () ->
                AccountJwtValidatorInventoryBinding.parseProtectedBytes(
                    JSON.writeValueAsBytes(sharedPodKey)))
        .isInstanceOf(IllegalStateException.class);

    Map<String, Object> canonicalIpv6 = parseMap(validBindingBytes());
    @SuppressWarnings("unchecked")
    Map<String, Object> ipv6Validator =
        (Map<String, Object>) ((List<?>) canonicalIpv6.get("validators")).get(0);
    @SuppressWarnings("unchecked")
    Map<String, Object> ipv6Pin =
        (Map<String, Object>) ((List<?>) ipv6Validator.get("receiverPods")).get(0);
    ipv6Pin.put("podIp", "2001:db8::1");
    rebindDigest(canonicalIpv6);
    assertThat(
            AccountJwtValidatorInventoryBinding.parseProtectedBytes(
                    JSON.writeValueAsBytes(canonicalIpv6))
                .validators()
                .get(0)
                .receiverPods()
                .get(0)
                .podIp())
        .isEqualTo("2001:db8::1");

    ipv6Pin.put("podIp", "2001:0db8:0:0:0:0:0:1");
    rebindDigest(canonicalIpv6);
    assertThatThrownBy(
            () ->
                AccountJwtValidatorInventoryBinding.parseProtectedBytes(
                    JSON.writeValueAsBytes(canonicalIpv6)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsOnePodLeafKeyPinnedToDifferentValidatorPods() throws Exception {
    Map<String, Object> sharedIdentity = parseMap(validBindingBytes());
    @SuppressWarnings("unchecked")
    Map<String, Object> firstValidator =
        (Map<String, Object>) ((List<?>) sharedIdentity.get("validators")).get(0);
    Map<String, Object> secondValidator = new LinkedHashMap<>(firstValidator);
    secondValidator.put("validatorId", "other-service");
    secondValidator.put("deploymentName", "other-service");
    secondValidator.put("deploymentUid", "66666666-6666-4666-8666-666666666666");
    secondValidator.put("selector", Map.of("app", "other-service"));
    secondValidator.put("containerName", "other-service");
    secondValidator.put("receiverServiceUri", "spiffe://firemud/ns/firemud-test/sa/other-service");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> firstPins =
        (List<Map<String, Object>>) firstValidator.get("receiverPods");
    Map<String, Object> otherFirstPin = new LinkedHashMap<>(firstPins.get(0));
    otherFirstPin.put("podUid", "77777777-7777-4777-8777-777777777777");
    otherFirstPin.put("podIp", "10.0.1.10");
    Map<String, Object> otherSecondPin = new LinkedHashMap<>(firstPins.get(1));
    otherSecondPin.put("podUid", "88888888-8888-4888-8888-888888888888");
    otherSecondPin.put("podIp", "10.0.1.11");
    secondValidator.put("receiverPods", List.of(otherFirstPin, otherSecondPin));
    sharedIdentity.put("validators", List.of(firstValidator, secondValidator));
    rebindDigest(sharedIdentity);

    assertThatThrownBy(
            () ->
                AccountJwtValidatorInventoryBinding.parseProtectedBytes(
                    JSON.writeValueAsBytes(sharedIdentity)))
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
    Map<String, Object> validator = new LinkedHashMap<>();
    validator.put("validatorId", "player-service");
    validator.put("deploymentName", "player-service");
    validator.put("deploymentUid", DEPLOYMENT_UID);
    validator.put("selector", Map.of("app", "player-service"));
    validator.put("replicas", 2);
    validator.put("containerName", "player-service");
    validator.put("image", image);
    validator.put("jwksUri", jwksUri);
    validator.put("maxCacheAgeSeconds", 60);
    validator.put("profiles", List.of(profile));
    validator.put("receiverServiceUri", "spiffe://firemud/ns/firemud-test/sa/player-service");
    validator.put("receiverPort", 8443);
    validator.put(
        "receiverPods",
        List.of(
            Map.of("podUid", POD_UID_ONE, "podIp", "10.0.0.10", "leafSpkiSha256", "c".repeat(64)),
            Map.of("podUid", POD_UID_TWO, "podIp", "10.0.0.11", "leafSpkiSha256", "d".repeat(64))));
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("version", "account-jwt-validator-inventory-binding/v2");
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

  private static void rebindDigest(Map<String, Object> root) throws Exception {
    root.put("bindingDigest", "0".repeat(64));
    JsonNode unsigned = JSON.readTree(JSON.writeValueAsBytes(root));
    root.put("bindingDigest", AccountJwtValidatorInventoryBinding.computeBindingDigest(unsigned));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseMap(byte[] value) throws Exception {
    JsonNode root = JSON.readTree(value);
    return JSON.readValue(JSON.writeValueAsBytes(root), Map.class);
  }
}
