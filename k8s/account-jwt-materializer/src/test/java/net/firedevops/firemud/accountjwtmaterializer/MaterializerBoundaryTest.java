package net.firedevops.firemud.accountjwtmaterializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MaterializerBoundaryTest {
  private static final String CLUSTER_UID = "8e265cfe-fda2-4c54-9ab6-8fc17fb4052c";
  private static final String NAMESPACE_UID = "120b32b7-5582-4220-9906-b241e776d10b";
  private static final String ACCOUNT_SA_UID = "63be9f06-06cc-4877-9d7f-8ca63581ddbb";
  private static final String OPERATION_ID = "00000000-0000-4000-8000-000000000001";
  private static final String REQUEST_DIGEST = "a".repeat(64);
  private static final String PRIOR_RV = "42";
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  @Test
  void protectedBindingParsesExactIdentityAndAccountTrustDigest() throws Exception {
    ProtectedBinding.Binding parsed = ProtectedBinding.parse(bindingJson());
    assertEquals("dev", parsed.environmentId());
    assertEquals("account", parsed.namespace());
    assertEquals(
        "spiffe://firemud/ns/account/sa/account-jwt-materializer",
        parsed.expectedMaterializerSpiffeUri());
    assertTrue(parsed.accountTrustBindingDigest().matches("[0-9a-f]{64}"));
  }

  @Test
  void protectedBindingRejectsUnknownField() throws Exception {
    Map<String, Object> fields = bindingFields();
    fields.put("fallbackKubeconfig", "/tmp/kubeconfig");
    assertThrows(IllegalArgumentException.class, () -> ProtectedBinding.parse(json(fields)));
  }

  @Test
  void protectedBindingRejectsDisabledConfiguration() throws Exception {
    Map<String, Object> fields = bindingFields();
    fields.put("enabled", false);
    assertThrows(IllegalArgumentException.class, () -> ProtectedBinding.parse(json(fields)));
  }

  @Test
  void protectedBindingRejectsDifferentClusterIncarnation() throws Exception {
    Map<String, Object> fields = bindingFields();
    fields.put("expectedKubeSystemNamespaceUid", NAMESPACE_UID);
    assertThrows(IllegalArgumentException.class, () -> ProtectedBinding.parse(json(fields)));
  }

  @Test
  void protectedBindingRejectsWrongMaterializerSpiffeIdentity() throws Exception {
    Map<String, Object> fields = bindingFields();
    fields.put(
        "expectedMaterializerSpiffeUri", "spiffe://firemud/ns/other/sa/account-jwt-materializer");
    assertThrows(IllegalArgumentException.class, () -> ProtectedBinding.parse(json(fields)));
  }

  @Test
  void protectedBindingRejectsAccountDnsAndTargetMismatch() throws Exception {
    Map<String, Object> fields = bindingFields();
    fields.put("accountTarget", "other.account.svc:9090");
    assertThrows(IllegalArgumentException.class, () -> ProtectedBinding.parse(json(fields)));
  }

  @Test
  void protectedBindingRejectsHttpApiEndpoint() throws Exception {
    Map<String, Object> fields = bindingFields();
    fields.put("kubernetesApiServerUri", "http://kubernetes.default.svc:6443");
    assertThrows(IllegalArgumentException.class, () -> ProtectedBinding.parse(json(fields)));
  }

  @Test
  void protectedBindingRejectsApiEndpointWithPathOrQuery() throws Exception {
    Map<String, Object> fields = bindingFields();
    fields.put("kubernetesApiServerUri", "https://kubernetes.default.svc:6443/api?discover=true");
    assertThrows(IllegalArgumentException.class, () -> ProtectedBinding.parse(json(fields)));
  }

  @Test
  void protectedBindingRejectsDuplicateClientPins() throws Exception {
    Map<String, Object> fields = bindingFields();
    fields.put("materializerSpkiSha256Pins", java.util.List.of("0".repeat(64), "0".repeat(64)));
    assertThrows(IllegalArgumentException.class, () -> ProtectedBinding.parse(json(fields)));
  }

  @Test
  void secretParserRequiresFixedPrecreatedSecretName() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> SecretState.parse(secretJson("other-secret", "account", "Opaque", "42"), "account"));
  }

  @Test
  void secretParserRequiresPinnedNamespace() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> SecretState.parse(secretJson(SecretState.NAME, "other", "Opaque", "42"), "account"));
  }

  @Test
  void secretParserRequiresOpaqueSecret() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SecretState.parse(
                secretJson(SecretState.NAME, "account", "kubernetes.io/tls", "42"), "account"));
  }

  @Test
  void secretParserRejectsMalformedResourceVersion() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SecretState.parse(secretJson(SecretState.NAME, "account", "Opaque", "\n"), "account"));
  }

  @Test
  void generationPatchIsResourceVersionUidGuardedAndTouchesOnlyPendingSlot() throws Exception {
    SecretState before = secretWithUnrelatedDataAndAnnotation();
    byte[] patch =
        before.generationPatch(new byte[] {1, 2, 3}, OPERATION_ID, REQUEST_DIGEST, PRIOR_RV);
    JsonNode root = JSON.readTree(patch);
    assertEquals(before.uid(), root.get("metadata").get("uid").textValue());
    assertEquals(PRIOR_RV, root.get("metadata").get("resourceVersion").textValue());
    assertTrue(root.get("data").has(SecretState.PENDING));
    assertEquals(1, root.get("data").size());
    assertEquals(
        Set.of(
            SecretState.GENERATION_OPERATION,
            SecretState.GENERATION_REQUEST,
            SecretState.GENERATION_PRIOR_RV),
        fieldNames(root.get("metadata").get("annotations")));
  }

  @Test
  void generationRecoveryMarkerRequiresExactAuthenticatedOperationRequestAndPriorVersion()
      throws Exception {
    SecretState state = secretWithGenerationMarker(OPERATION_ID, REQUEST_DIGEST, PRIOR_RV);
    assertTrue(
        state.operationMarkerMatches(
            SecretState.GENERATION_OPERATION,
            SecretState.GENERATION_REQUEST,
            SecretState.GENERATION_PRIOR_RV,
            OPERATION_ID,
            REQUEST_DIGEST,
            PRIOR_RV));
    assertFalse(
        state.operationMarkerMatches(
            SecretState.GENERATION_OPERATION,
            SecretState.GENERATION_REQUEST,
            SecretState.GENERATION_PRIOR_RV,
            OPERATION_ID,
            "b".repeat(64),
            PRIOR_RV));
    assertFalse(
        state.operationMarkerMatches(
            SecretState.GENERATION_OPERATION,
            SecretState.GENERATION_REQUEST,
            SecretState.GENERATION_PRIOR_RV,
            OPERATION_ID,
            REQUEST_DIGEST,
            "43"));
  }

  @Test
  void readbackRequiresUnrelatedSecretDataAndAnnotationsToRemainUnchanged() throws Exception {
    SecretState before = secretWithUnrelatedDataAndAnnotation();
    SecretState exactReadback = secretWithGenerationReadback("opaque-value");
    before.requireUnchangedExcept(
        Set.of(SecretState.PENDING),
        Set.of(
            SecretState.GENERATION_OPERATION,
            SecretState.GENERATION_REQUEST,
            SecretState.GENERATION_PRIOR_RV),
        exactReadback);
    SecretState alteredReadback = secretWithGenerationReadback("changed");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            before.requireUnchangedExcept(
                Set.of(SecretState.PENDING),
                Set.of(
                    SecretState.GENERATION_OPERATION,
                    SecretState.GENERATION_REQUEST,
                    SecretState.GENERATION_PRIOR_RV),
                alteredReadback));
  }

  @Test
  void promotionPatchChangesOnlyTheThreeAccountAuthorizedSlots() throws Exception {
    SecretState before = secretWithUnrelatedDataAndAnnotation();
    byte[] patch =
        before.promotionPatch(
            new byte[] {1, 2}, new byte[] {3, 4}, OPERATION_ID, REQUEST_DIGEST, PRIOR_RV);
    JsonNode root = JSON.readTree(patch);
    JsonNode data = root.get("data");
    assertEquals(
        Set.of(SecretState.CURRENT, SecretState.PENDING, SecretState.PREVIOUS), fieldNames(data));
    assertTrue(data.get(SecretState.PENDING).isNull());
    assertEquals(before.uid(), root.get("metadata").get("uid").textValue());
    assertEquals(PRIOR_RV, root.get("metadata").get("resourceVersion").textValue());
    assertTrue(root.get("metadata").get("annotations").has(SecretState.PROMOTION_OPERATION));
  }

  @Test
  void promotionPatchRemovesPreviousWhenAccountHasNoPriorCurrentSigner() throws Exception {
    SecretState before = secretWithUnrelatedDataAndAnnotation();
    JsonNode patch =
        JSON.readTree(
            before.promotionPatch(new byte[] {1}, null, OPERATION_ID, REQUEST_DIGEST, PRIOR_RV));
    assertTrue(patch.get("data").get(SecretState.PREVIOUS).isNull());
  }

  @Test
  void signerGenerationUsesOnlyRsa3072Exponent65537AndPublicOnlyReceipt() throws Exception {
    try (SignerKeyBundle.Generated generated =
        SignerKeyBundle.generate(binding(), OPERATION_ID, "7", "kid_7")) {
      assertEquals(64, generated.identity().fingerprint().length());
      JsonNode jwk = JSON.readTree(generated.publicJwkJson());
      assertEquals("RSA", jwk.get("kty").textValue());
      assertEquals("RS256", jwk.get("alg").textValue());
      assertEquals("kid_7", jwk.get("kid").textValue());
      assertEquals(Set.of("alg", "e", "key_ops", "kid", "kty", "n", "use"), fieldNames(jwk));
      assertFalse(jwk.has("d"));
      byte[] bytes = generated.bundleBytes();
      SignerKeyBundle.PublicIdentity parsed =
          SignerKeyBundle.verify(
              bytes, binding(), OPERATION_ID, "7", "kid_7", generated.identity().fingerprint());
      assertEquals(generated.identity(), parsed);
      JsonNode privateBundle = JSON.readTree(bytes);
      byte[] privateDer =
          Base64.getUrlDecoder().decode(privateBundle.get("privateKeyPkcs8").textValue());
      try {
        RSAPrivateCrtKey privateKey =
            (RSAPrivateCrtKey)
                KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateDer));
        assertEquals(3_072, privateKey.getModulus().bitLength());
        assertEquals(java.math.BigInteger.valueOf(65_537), privateKey.getPublicExponent());
      } finally {
        java.util.Arrays.fill(privateDer, (byte) 0);
      }
    }
  }

  @Test
  void signerBundleRejectsAnotherOperationIdentity() throws Exception {
    try (SignerKeyBundle.Generated generated =
        SignerKeyBundle.generate(binding(), OPERATION_ID, "8", "kid_8")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              SignerKeyBundle.verify(
                  generated.bundleBytes(),
                  binding(),
                  "00000000-0000-4000-8000-000000000002",
                  "8",
                  "kid_8",
                  null));
    }
  }

  @Test
  void signerBundleRejectsPaddedNonCanonicalPrivateKeyEncoding() throws Exception {
    try (SignerKeyBundle.Generated generated =
        SignerKeyBundle.generate(binding(), OPERATION_ID, "9", "kid_9")) {
      JsonNode root = JSON.readTree(generated.bundleBytes());
      Map<String, Object> fields = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> entry : root.properties()) {
        fields.put(
            entry.getKey(),
            entry.getValue().isIntegralNumber()
                ? entry.getValue().intValue()
                : entry.getValue().textValue());
      }
      fields.put("privateKeyPkcs8", fields.get("privateKeyPkcs8") + "=");
      assertThrows(
          IllegalArgumentException.class,
          () -> SignerKeyBundle.verify(json(fields), binding(), OPERATION_ID, "9", "kid_9", null));
    }
  }

  @Test
  void generatedBundleUsesCanonicalJsonAndCanonicalUrlBase64() throws Exception {
    try (SignerKeyBundle.Generated generated =
        SignerKeyBundle.generate(binding(), OPERATION_ID, "10", "kid_10")) {
      byte[] canonical =
          Rfc8785CanonicalJson.canonicalizeUtf8(
              new String(generated.bundleBytes(), StandardCharsets.UTF_8));
      assertTrue(java.util.Arrays.equals(canonical, generated.bundleBytes()));
      JsonNode root = JSON.readTree(generated.bundleBytes());
      String encoded = root.get("privateKeyPkcs8").textValue();
      assertFalse(encoded.contains("="));
      assertTrue(Base64.getUrlDecoder().decode(encoded).length > 0);
    }
  }

  @Test
  void secretStateDoesNotExposeDataInToString() throws Exception {
    SecretState state = secretWithUnrelatedDataAndAnnotation();
    assertFalse(state.toString().contains("private"));
    assertFalse(state.toString().contains("opaque-value"));
  }

  private static byte[] bindingJson() throws Exception {
    return json(bindingFields());
  }

  private static Map<String, Object> bindingFields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schemaVersion", 1);
    fields.put("enabled", true);
    fields.put("configRevision", "rev-1");
    fields.put("environmentId", "dev");
    fields.put("clusterId", "local-cluster");
    fields.put("namespace", "account");
    fields.put("expectedClusterIncarnationUid", CLUSTER_UID);
    fields.put("expectedNamespaceUid", NAMESPACE_UID);
    fields.put("expectedKubeSystemNamespaceUid", CLUSTER_UID);
    fields.put("expectedServiceAccountName", "account-jwt-materializer");
    fields.put("expectedServiceAccountUid", ACCOUNT_SA_UID);
    fields.put(
        "expectedMaterializerSpiffeUri", "spiffe://firemud/ns/account/sa/account-jwt-materializer");
    fields.put("materializerSpkiSha256", "0".repeat(64));
    fields.put("materializerSpkiSha256Pins", java.util.List.of("0".repeat(64)));
    fields.put("accountTarget", "account.account.svc:9090");
    fields.put("accountServerDnsName", "account.account.svc");
    fields.put("expectedAccountSpiffeUri", "spiffe://firemud/ns/account/sa/account-service");
    fields.put("accountServerSpkiSha256Pins", java.util.List.of("1".repeat(64)));
    fields.put("accountCaPath", "/etc/firemud/account-jwt-materializer/account-ca.pem");
    fields.put("clientCertificatePath", "/etc/firemud/account-jwt-materializer/client.pem");
    fields.put("clientPrivateKeyPath", "/etc/firemud/account-jwt-materializer/client-key.pem");
    fields.put("kubernetesApiServerUri", "https://kubernetes.default.svc:6443");
    fields.put("kubernetesApiCaPath", "/etc/firemud/account-jwt-materializer/kubernetes-ca.pem");
    fields.put("kubernetesTokenPath", "/etc/firemud/account-jwt-materializer/token.jwt");
    return fields;
  }

  static ProtectedBinding.Binding binding() {
    return ProtectedBinding.parse(bindingUnchecked());
  }

  private static byte[] bindingUnchecked() {
    try {
      return bindingJson();
    } catch (Exception ex) {
      throw new AssertionError(ex);
    }
  }

  private static byte[] secretJson(String name, String namespace, String type, String rv)
      throws Exception {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", name);
    metadata.put("namespace", namespace);
    metadata.put("uid", "d1d5a1cb-31f7-4b87-a542-00cbd52fba92");
    metadata.put("resourceVersion", rv);
    Map<String, Object> secret = new LinkedHashMap<>();
    secret.put("apiVersion", "v1");
    secret.put("kind", "Secret");
    secret.put("metadata", metadata);
    secret.put("type", type);
    secret.put("data", Map.of());
    return json(secret);
  }

  private static SecretState secretWithUnrelatedDataAndAnnotation() throws Exception {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", SecretState.NAME);
    metadata.put("namespace", "account");
    metadata.put("uid", "d1d5a1cb-31f7-4b87-a542-00cbd52fba92");
    metadata.put("resourceVersion", PRIOR_RV);
    metadata.put("annotations", Map.of("operator.example/keep", "retained"));
    Map<String, Object> secret = new LinkedHashMap<>();
    secret.put("apiVersion", "v1");
    secret.put("kind", "Secret");
    secret.put("metadata", metadata);
    secret.put("type", "Opaque");
    secret.put(
        "data",
        Map.of(
            "unrelated",
            Base64.getEncoder().encodeToString("opaque-value".getBytes(StandardCharsets.UTF_8))));
    return SecretState.parse(json(secret), "account");
  }

  private static SecretState secretWithGenerationMarker(String operation, String digest, String rv)
      throws Exception {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", SecretState.NAME);
    metadata.put("namespace", "account");
    metadata.put("uid", "d1d5a1cb-31f7-4b87-a542-00cbd52fba92");
    metadata.put("resourceVersion", "43");
    metadata.put(
        "annotations",
        Map.of(
            SecretState.GENERATION_OPERATION, operation,
            SecretState.GENERATION_REQUEST, digest,
            SecretState.GENERATION_PRIOR_RV, rv));
    Map<String, Object> secret = new LinkedHashMap<>();
    secret.put("apiVersion", "v1");
    secret.put("kind", "Secret");
    secret.put("metadata", metadata);
    secret.put("type", "Opaque");
    secret.put("data", Map.of());
    return SecretState.parse(json(secret), "account");
  }

  private static SecretState secretWithGenerationReadback(String unrelatedValue) throws Exception {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", SecretState.NAME);
    metadata.put("namespace", "account");
    metadata.put("uid", "d1d5a1cb-31f7-4b87-a542-00cbd52fba92");
    metadata.put("resourceVersion", "43");
    metadata.put(
        "annotations",
        Map.of(
            "operator.example/keep",
            "retained",
            SecretState.GENERATION_OPERATION,
            OPERATION_ID,
            SecretState.GENERATION_REQUEST,
            REQUEST_DIGEST,
            SecretState.GENERATION_PRIOR_RV,
            PRIOR_RV));
    Map<String, Object> secret = new LinkedHashMap<>();
    secret.put("apiVersion", "v1");
    secret.put("kind", "Secret");
    secret.put("metadata", metadata);
    secret.put("type", "Opaque");
    secret.put(
        "data",
        Map.of(
            "unrelated",
            Base64.getEncoder().encodeToString(unrelatedValue.getBytes(StandardCharsets.UTF_8)),
            SecretState.PENDING,
            Base64.getEncoder().encodeToString(new byte[] {1, 2, 3})));
    return SecretState.parse(json(secret), "account");
  }

  private static Set<String> fieldNames(JsonNode object) {
    Set<String> names = new java.util.LinkedHashSet<>();
    for (Map.Entry<String, JsonNode> entry : object.properties()) {
      names.add(entry.getKey());
    }
    return Set.copyOf(names);
  }

  private static byte[] json(Object value) throws Exception {
    return JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
  }
}
