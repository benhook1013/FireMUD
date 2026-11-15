package net.firedevops.firemud.accountjwtmaterializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.account.v1.EnrollBootstrapResponse;
import net.firedevops.firemud.account.v1.GetCurrentGenerationRequestResponse;
import net.firedevops.firemud.account.v1.GetCurrentPromotionRequestResponse;
import net.firedevops.firemud.account.v1.RecordGenerationResultResponse;
import net.firedevops.firemud.account.v1.RecordPrivatePromotionResultResponse;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MaterializerFlowTest {
  private static final String SECRET_UID = "d1d5a1cb-31f7-4b87-a542-00cbd52fba92";
  private static final String OPERATION_ID = "00000000-0000-4000-8000-000000000001";
  private static final String PROMOTION_ID = "00000000-0000-4000-8000-000000000002";
  private static final String OPERATION_DIGEST = "a".repeat(64);
  private static final String GENERATION_REQUEST_DIGEST = "b".repeat(64);
  private static final String GENERATION_RECEIPT_DIGEST = "c".repeat(64);
  private static final String PROMOTION_DIGEST = "d".repeat(64);
  private static final String PRIOR_RV = "42";
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  @Test
  void ambiguousGenerationPatchRecoversExactStoredKeyAndReportsOnlyItsPublicJwk() throws Exception {
    ProtectedBinding.Snapshot snapshot = snapshot();
    MemorySecretApi secret = new MemorySecretApi(secret(PRIOR_RV, Map.of(), Map.of()));
    secret.throwAfterApply = true;
    MemoryAccount account = new MemoryAccount(snapshot.binding());

    Materializer.Outcome outcome = new Materializer(snapshot, account, secret).reconcileOnce();

    assertEquals(Materializer.Outcome.GENERATION_RECORDED, outcome);
    assertEquals(1, secret.patchCount);
    assertEquals(1, account.observationCount);
    assertEquals(1, account.generationResultCount);
    assertEquals(1, account.enrollmentCount);
    assertEquals(1, account.generationRequestCount);
    assertEquals(2, account.promotionNotFoundCount);
    assertEquals(List.of("enroll", "promotion", "generation", "promotion"), account.events);
    assertTrue(account.publicJwk.contains("\"kty\":\"RSA\""));
    assertFalse(account.publicJwk.contains("\"d\""));
    assertFalse(outcome.publicName().contains("privateKey"));
    SecretState readback = secret.read();
    assertTrue(readback.containsSlot(SecretState.PENDING));
    assertTrue(
        readback.operationMarkerMatches(
            SecretState.GENERATION_OPERATION,
            SecretState.GENERATION_REQUEST,
            SecretState.GENERATION_PRIOR_RV,
            OPERATION_ID,
            GENERATION_REQUEST_DIGEST,
            PRIOR_RV));
  }

  @Test
  void observationOnlyRecordsTheExactEmptySecretAndStopsBeforeGenerationOrPromotion() {
    ProtectedBinding.Snapshot snapshot = snapshot();
    MemorySecretApi secret = new MemorySecretApi(secret(PRIOR_RV, Map.of(), Map.of()));
    MemoryAccount account = new MemoryAccount(snapshot.binding());

    Materializer.Outcome outcome = new Materializer(snapshot, account, secret).observeSecretOnce();

    assertEquals(Materializer.Outcome.SECRET_OBSERVATION_RECORDED, outcome);
    assertEquals("secret_observation_recorded", outcome.publicName());
    assertEquals(1, account.enrollmentCount);
    assertEquals(1, account.promotionNotFoundCount);
    assertEquals(1, account.generationRequestCount);
    assertEquals(1, account.observationCount);
    assertEquals(0, account.generationResultCount);
    assertEquals(0, account.promotionReceiptCount);
    assertEquals(0, secret.patchCount);
    assertEquals(PRIOR_RV, secret.read().resourceVersion());
    assertTrue(secret.read().signerSlots().isEmpty());
  }

  @Test
  void observationOnlyRejectsExistingOrMixedSignerMaterialBeforeRecording() {
    ProtectedBinding.Snapshot snapshot = snapshot();
    List<Map<String, String>> nonemptyData =
        List.of(
            Map.of(SecretState.CURRENT, Base64.getEncoder().encodeToString(new byte[] {1})),
            Map.of(SecretState.PENDING, Base64.getEncoder().encodeToString(new byte[] {1})),
            Map.of(
                SecretState.CURRENT,
                Base64.getEncoder().encodeToString(new byte[] {1}),
                SecretState.PENDING,
                Base64.getEncoder().encodeToString(new byte[] {2})));

    for (Map<String, String> data : nonemptyData) {
      MemorySecretApi secret = new MemorySecretApi(secret(PRIOR_RV, data, Map.of()));
      MemoryAccount account = new MemoryAccount(snapshot.binding());

      assertThrows(
          IllegalStateException.class,
          () -> new Materializer(snapshot, account, secret).observeSecretOnce());

      assertEquals(0, account.observationCount);
      assertEquals(0, account.generationResultCount);
      assertEquals(0, secret.patchCount);
      assertEquals(PRIOR_RV, secret.read().resourceVersion());
    }
  }

  @Test
  void enrollmentAcknowledgmentMismatchDeniesBeforeAnySecretMutationOrGenerationRead() {
    ProtectedBinding.Snapshot snapshot = snapshot();
    EnrollBootstrapResponse valid = enrollmentResponse(snapshot.binding());
    List<EnrollBootstrapResponse> mismatches =
        List.of(
            valid.toBuilder().setEnvironmentId("other-env").build(),
            valid.toBuilder().setClusterId("other-cluster").build(),
            valid.toBuilder().setNamespace("other-namespace").build(),
            valid.toBuilder().setExpectedClusterIncarnationUid("other-cluster-uid").build(),
            valid.toBuilder().setExpectedNamespaceUid("other-namespace-uid").build(),
            valid.toBuilder().setMaterializerTrustBindingDigest("d".repeat(64)).build(),
            valid.toBuilder().setMaterializerTrustConfigRevision("other-revision").build());

    for (EnrollBootstrapResponse acknowledgement : mismatches) {
      MemorySecretApi secret = new MemorySecretApi(secret(PRIOR_RV, Map.of(), Map.of()));
      MemoryAccount account = new MemoryAccount(snapshot.binding());
      account.enrollmentResponse = acknowledgement;

      assertThrows(
          IllegalStateException.class,
          () -> new Materializer(snapshot, account, secret).reconcileOnce());

      assertEquals(1, account.enrollmentCount);
      assertEquals(0, account.generationRequestCount);
      assertEquals(List.of("enroll"), account.events);
      assertEquals(0, secret.patchCount);
    }
  }

  @Test
  void enrollmentAcknowledgmentRejectsNoncanonicalAndNilConfigMapUids() {
    ProtectedBinding.Snapshot snapshot = snapshot();
    for (String uid :
        List.of("33333333-3333-4333-8333-33333333333A", "00000000-0000-0000-0000-000000000000")) {
      MemorySecretApi secret = new MemorySecretApi(secret(PRIOR_RV, Map.of(), Map.of()));
      MemoryAccount account = new MemoryAccount(snapshot.binding());
      account.enrollmentResponse =
          account.enrollmentResponse.toBuilder().setPublicConfigMapUid(uid).build();

      assertThrows(
          IllegalStateException.class,
          () -> new Materializer(snapshot, account, secret).reconcileOnce());

      assertEquals(1, account.enrollmentCount);
      assertEquals(0, account.generationRequestCount);
      assertEquals(List.of("enroll"), account.events);
      assertEquals(0, secret.patchCount);
    }
  }

  @Test
  void generationConflictIsDeniedWithoutPatchRetryOrAccountReceipt() throws Exception {
    ProtectedBinding.Snapshot snapshot = snapshot();
    MemorySecretApi secret = new MemorySecretApi(secret(PRIOR_RV, Map.of(), Map.of()));
    secret.rejectPatch = true;
    MemoryAccount account = new MemoryAccount(snapshot.binding());

    assertThrows(
        IllegalStateException.class,
        () -> new Materializer(snapshot, account, secret).reconcileOnce());

    assertEquals(1, secret.patchCount);
    assertEquals(0, account.generationResultCount);
    assertEquals(PRIOR_RV, secret.read().resourceVersion());
  }

  @Test
  void generationRejectsExistingPendingMaterialWithoutOverwritingIt() throws Exception {
    ProtectedBinding.Snapshot snapshot = snapshot();
    Map<String, String> data =
        Map.of(SecretState.PENDING, Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
    MemorySecretApi secret = new MemorySecretApi(secret(PRIOR_RV, data, Map.of()));
    MemoryAccount account = new MemoryAccount(snapshot.binding());

    assertThrows(
        IllegalStateException.class,
        () -> new Materializer(snapshot, account, secret).reconcileOnce());

    assertEquals(0, secret.patchCount);
    assertEquals(0, account.generationResultCount);
  }

  @Test
  void generationRejectsRequestTrustMismatchBeforeSecretWrite() throws Exception {
    ProtectedBinding.Snapshot snapshot = snapshot();
    MemorySecretApi secret = new MemorySecretApi(secret(PRIOR_RV, Map.of(), Map.of()));
    MemoryAccount account = new MemoryAccount(snapshot.binding());
    account.generationRequest =
        generationRequest(snapshot.binding(), false).toBuilder()
            .setTrustBindingDigest("e".repeat(64))
            .build();

    assertThrows(
        IllegalStateException.class,
        () -> new Materializer(snapshot, account, secret).reconcileOnce());

    assertEquals(0, secret.patchCount);
    assertEquals(0, account.observationCount);
  }

  @Test
  void generationRejectsStaleAccountResourceVersionWithoutWriting() throws Exception {
    ProtectedBinding.Snapshot snapshot = snapshot();
    MemorySecretApi secret = new MemorySecretApi(secret(PRIOR_RV, Map.of(), Map.of()));
    MemoryAccount account = new MemoryAccount(snapshot.binding());
    account.observationResourceVersion = "41";

    assertThrows(
        IllegalStateException.class,
        () -> new Materializer(snapshot, account, secret).reconcileOnce());

    assertEquals(0, secret.patchCount);
    assertEquals(0, account.generationResultCount);
  }

  @Test
  void generationRejectsMalformedAllowedSlotsAndNeverInventsAnAccountReceipt() throws Exception {
    ProtectedBinding.Snapshot snapshot = snapshot();
    MemorySecretApi secret = new MemorySecretApi(secret(PRIOR_RV, Map.of(), Map.of()));
    MemoryAccount account = new MemoryAccount(snapshot.binding());
    account.generationRequest =
        generationRequest(snapshot.binding(), false).toBuilder()
            .clearAllowedPrivateSlots()
            .addAllowedPrivateSlots("current")
            .build();

    assertThrows(
        IllegalStateException.class,
        () -> new Materializer(snapshot, account, secret).reconcileOnce());

    assertEquals(0, secret.patchCount);
    assertEquals(0, account.generationResultCount);
  }

  @Test
  void promotionMovesOnlyAuthorizedPrivateSlotsAndReportsAccountSlotNames() throws Exception {
    ProtectedBinding.Snapshot snapshot = snapshot();
    try (SignerKeyBundle.Generated target =
        SignerKeyBundle.generate(snapshot.binding(), OPERATION_ID, "7", "kid_7")) {
      Map<String, String> data =
          Map.of(SecretState.PENDING, Base64.getEncoder().encodeToString(target.bundleBytes()));
      MemorySecretApi secret = new MemorySecretApi(secret(PRIOR_RV, data, Map.of("keep", "yes")));
      MemoryAccount account = new MemoryAccount(snapshot.binding());
      account.promotionRequest = promotionRequest(snapshot.binding(), target.identity());

      Materializer.Outcome outcome = new Materializer(snapshot, account, secret).reconcileOnce();

      assertEquals(Materializer.Outcome.PRIVATE_PROMOTION_RECEIPT_RECORDED, outcome);
      assertEquals(1, secret.patchCount);
      assertEquals(List.of("current"), account.resultingSlots);
      assertEquals(1, account.promotionReceiptCount);
      assertEquals(1, account.enrollmentCount);
      assertEquals(0, account.generationRequestCount);
      assertEquals(List.of("enroll", "promotion"), account.events);
      SecretState readback = secret.read();
      assertTrue(readback.containsSlot(SecretState.CURRENT));
      assertFalse(readback.containsSlot(SecretState.PENDING));
      assertFalse(readback.containsSlot(SecretState.PREVIOUS));
      assertEquals("yes", readback.annotation("keep"));
    }
  }

  @Test
  void ambiguousPromotionPatchRecoversExactTransitionWithoutRepeatingPatch() throws Exception {
    ProtectedBinding.Snapshot snapshot = snapshot();
    try (SignerKeyBundle.Generated target =
        SignerKeyBundle.generate(snapshot.binding(), OPERATION_ID, "7", "kid_7")) {
      MemorySecretApi secret =
          new MemorySecretApi(
              secret(
                  PRIOR_RV,
                  Map.of(
                      SecretState.PENDING,
                      Base64.getEncoder().encodeToString(target.bundleBytes())),
                  Map.of()));
      secret.throwAfterApply = true;
      MemoryAccount account = new MemoryAccount(snapshot.binding());
      account.promotionRequest = promotionRequest(snapshot.binding(), target.identity());

      Materializer.Outcome outcome = new Materializer(snapshot, account, secret).reconcileOnce();

      assertEquals(Materializer.Outcome.PRIVATE_PROMOTION_RECEIPT_RECORDED, outcome);
      assertEquals(1, secret.patchCount);
      assertEquals(1, account.promotionReceiptCount);
      assertEquals(List.of("current"), account.resultingSlots);
    }
  }

  @Test
  void promotionReceiptRetryUsesStoredTransitionWithoutAnotherPatch() throws Exception {
    ProtectedBinding.Snapshot snapshot = snapshot();
    try (SignerKeyBundle.Generated target =
        SignerKeyBundle.generate(snapshot.binding(), OPERATION_ID, "7", "kid_7")) {
      MemorySecretApi secret =
          new MemorySecretApi(
              secret(
                  PRIOR_RV,
                  Map.of(
                      SecretState.PENDING,
                      Base64.getEncoder().encodeToString(target.bundleBytes())),
                  Map.of()));
      MemoryAccount account = new MemoryAccount(snapshot.binding());
      account.promotionRequest = promotionRequest(snapshot.binding(), target.identity());
      account.failFirstPromotionReceipt = true;

      assertThrows(
          IllegalStateException.class,
          () -> new Materializer(snapshot, account, secret).reconcileOnce());
      Materializer.Outcome retry = new Materializer(snapshot, account, secret).reconcileOnce();

      assertEquals(Materializer.Outcome.PRIVATE_PROMOTION_RECEIPT_RECORDED, retry);
      assertEquals(1, secret.patchCount);
      assertEquals(2, account.promotionReceiptCount);
      assertEquals(0, account.generationRequestCount);
      assertEquals(List.of("enroll", "promotion", "enroll", "promotion"), account.events);
    }
  }

  @Test
  void promotionRejectsTargetMismatchWithoutPrivateSlotMutation() throws Exception {
    ProtectedBinding.Snapshot snapshot = snapshot();
    try (SignerKeyBundle.Generated target =
        SignerKeyBundle.generate(snapshot.binding(), OPERATION_ID, "7", "kid_7")) {
      MemorySecretApi secret =
          new MemorySecretApi(
              secret(
                  PRIOR_RV,
                  Map.of(
                      SecretState.PENDING,
                      Base64.getEncoder().encodeToString(target.bundleBytes())),
                  Map.of()));
      MemoryAccount account = new MemoryAccount(snapshot.binding());
      account.promotionRequest =
          promotionRequest(snapshot.binding(), target.identity()).toBuilder()
              .setTargetKid("kid_other")
              .build();

      assertThrows(
          IllegalArgumentException.class,
          () -> new Materializer(snapshot, account, secret).reconcileOnce());

      assertEquals(0, secret.patchCount);
      assertTrue(secret.read().containsSlot(SecretState.PENDING));
    }
  }

  private static ProtectedBinding.Snapshot snapshot() {
    return new ProtectedBinding.Snapshot(MaterializerBoundaryTest.binding(), "test-snapshot");
  }

  private static Map<String, Object> secret(
      String resourceVersion, Map<String, String> data, Map<String, String> annotations) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", SecretState.NAME);
    metadata.put("namespace", "account");
    metadata.put("uid", SECRET_UID);
    metadata.put("resourceVersion", resourceVersion);
    metadata.put("annotations", new LinkedHashMap<>(annotations));
    Map<String, Object> secret = new LinkedHashMap<>();
    secret.put("apiVersion", "v1");
    secret.put("kind", "Secret");
    secret.put("metadata", metadata);
    secret.put("type", "Opaque");
    secret.put("data", new LinkedHashMap<>(data));
    return secret;
  }

  private static GetCurrentGenerationRequestResponse generationRequest(
      ProtectedBinding.Binding binding, boolean observed) {
    var builder =
        GetCurrentGenerationRequestResponse.newBuilder()
            .setSchemaVersion(1)
            .setPhase(
                observed
                    ? GetCurrentGenerationRequestResponse.Phase.GENERATE_PENDING
                    : GetCurrentGenerationRequestResponse.Phase.OBSERVE_PRIVATE_SECRET)
            .setOperationId(OPERATION_ID)
            .setOperationDigest(OPERATION_DIGEST)
            .setDesiredStateVersion("2")
            .setEnvironmentId(binding.environmentId())
            .setClusterId(binding.clusterId())
            .setNamespace(binding.namespace())
            .setExpectedClusterIncarnationUid(binding.expectedClusterIncarnationUid())
            .setExpectedNamespaceUid(binding.expectedNamespaceUid())
            .setTrustBindingDigest(binding.accountTrustBindingDigest())
            .setTrustConfigRevision(binding.configRevision())
            .setPrivateSecretName(SecretState.NAME)
            .setTargetGeneration("7")
            .setTargetKid("kid_7")
            .setTargetAlgorithm("RS256")
            .setOperationAction("MATERIALIZE_PENDING")
            .addAllowedPrivateSlots("pending");
    if (observed) {
      builder
          .setGenerationRequestDigest(GENERATION_REQUEST_DIGEST)
          .setSecretUid(SECRET_UID)
          .setExpectedSecretResourceVersion(PRIOR_RV);
    }
    return builder.build();
  }

  private static GetCurrentPromotionRequestResponse promotionRequest(
      ProtectedBinding.Binding binding, SignerKeyBundle.PublicIdentity target) {
    return GetCurrentPromotionRequestResponse.newBuilder()
        .setSchemaVersion(1)
        .setPromotionOperationId(PROMOTION_ID)
        .setPromotionRequestDigest(PROMOTION_DIGEST)
        .setGenerationOperationId(target.operationId())
        .setGenerationOperationDigest(OPERATION_DIGEST)
        .setGenerationReceiptDigest(GENERATION_RECEIPT_DIGEST)
        .setEnvironmentId(binding.environmentId())
        .setClusterId(binding.clusterId())
        .setNamespace(binding.namespace())
        .setExpectedClusterIncarnationUid(binding.expectedClusterIncarnationUid())
        .setExpectedNamespaceUid(binding.expectedNamespaceUid())
        .setTrustBindingDigest(binding.accountTrustBindingDigest())
        .setTrustConfigRevision(binding.configRevision())
        .setPrivateSecretName(SecretState.NAME)
        .setTargetGeneration(target.generation())
        .setTargetKid(target.kid())
        .setTargetAlgorithm("RS256")
        .setTargetPublicKeyFingerprint(target.fingerprint())
        .setExpectedSecretUid(SECRET_UID)
        .setExpectedSecretResourceVersion(PRIOR_RV)
        .setOperationAction("PROMOTE_PENDING")
        .addAllowedPrivateSlots(SecretState.CURRENT)
        .addAllowedPrivateSlots(SecretState.PENDING)
        .addAllowedPrivateSlots(SecretState.PREVIOUS)
        .build();
  }

  private static String thumbprint(String jwkJson) throws Exception {
    JsonNode jwk = JSON.readTree(jwkJson);
    Map<String, Object> thumbprint = new LinkedHashMap<>();
    thumbprint.put("e", jwk.get("e").textValue());
    thumbprint.put("kty", "RSA");
    thumbprint.put("n", jwk.get("n").textValue());
    byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(thumbprint));
    try {
      return java.util.HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } finally {
      java.util.Arrays.fill(canonical, (byte) 0);
    }
  }

  private static byte[] json(Object value) {
    try {
      return JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new AssertionError(ex);
    }
  }

  private static final class MemorySecretApi implements Materializer.KubernetesOperations {
    private final Map<String, Object> resource;
    private int patchCount;
    private boolean throwAfterApply;
    private boolean rejectPatch;

    MemorySecretApi(Map<String, Object> resource) {
      this.resource = resource;
    }

    @Override
    public void verifyClusterIdentity() {}

    @Override
    public SecretState getFixedSecret() {
      return read();
    }

    SecretState read() {
      return SecretState.parse(json(resource), "account");
    }

    @Override
    @SuppressWarnings("unchecked")
    public void patchFixedSecret(SecretState before, byte[] mergePatch) {
      patchCount++;
      try {
        if (rejectPatch) {
          throw new IllegalStateException("simulated resourceVersion conflict");
        }
        JsonNode patch;
        try {
          patch = JSON.readTree(mergePatch);
        } catch (RuntimeException ex) {
          throw new IllegalStateException("simulated malformed patch");
        }
        JsonNode metadataPatch = patch.get("metadata");
        if (!before.uid().equals(metadataPatch.get("uid").textValue())
            || !before.resourceVersion().equals(metadataPatch.get("resourceVersion").textValue())) {
          throw new IllegalStateException("simulated stale Secret precondition");
        }
        Map<String, Object> metadata = (Map<String, Object>) resource.get("metadata");
        Map<String, String> annotations = (Map<String, String>) metadata.get("annotations");
        if (annotations == null) {
          annotations = new LinkedHashMap<>();
          metadata.put("annotations", annotations);
        }
        JsonNode annotationPatch = metadataPatch.get("annotations");
        for (Map.Entry<String, JsonNode> entry : annotationPatch.properties()) {
          annotations.put(entry.getKey(), entry.getValue().textValue());
        }
        Map<String, String> data = (Map<String, String>) resource.get("data");
        for (Map.Entry<String, JsonNode> entry : patch.get("data").properties()) {
          if (entry.getValue().isNull()) {
            data.remove(entry.getKey());
          } else {
            data.put(entry.getKey(), entry.getValue().textValue());
          }
        }
        int next = Integer.parseInt(before.resourceVersion()) + 1;
        metadata.put("resourceVersion", Integer.toString(next));
        if (throwAfterApply) {
          throw new IllegalStateException("simulated lost patch response");
        }
      } finally {
        java.util.Arrays.fill(mergePatch, (byte) 0);
      }
    }
  }

  private static final class MemoryAccount implements Materializer.AccountOperations {
    private final ProtectedBinding.Binding binding;
    private EnrollBootstrapResponse enrollmentResponse;
    private GetCurrentGenerationRequestResponse generationRequest;
    private GetCurrentPromotionRequestResponse promotionRequest;
    private String observationResourceVersion = PRIOR_RV;
    private String publicJwk;
    private int observationCount;
    private int generationResultCount;
    private int enrollmentCount;
    private int generationRequestCount;
    private int promotionNotFoundCount;
    private int promotionReceiptCount;
    private boolean failFirstPromotionReceipt;
    private List<String> resultingSlots = List.of();
    private final List<String> events = new java.util.ArrayList<>();

    MemoryAccount(ProtectedBinding.Binding binding) {
      this.binding = binding;
      this.enrollmentResponse = enrollmentResponse(binding);
      this.generationRequest = generationRequest(binding, false);
    }

    @Override
    public EnrollBootstrapResponse enrollBootstrap() {
      enrollmentCount++;
      events.add("enroll");
      return enrollmentResponse;
    }

    @Override
    public GetCurrentGenerationRequestResponse getCurrentGenerationRequest() {
      generationRequestCount++;
      events.add("generation");
      if (generationRequest == null) {
        throw new StatusRuntimeException(Status.FAILED_PRECONDITION);
      }
      return generationRequest;
    }

    @Override
    public GetCurrentGenerationRequestResponse recordSecretObservation(
        String operationId,
        String operationDigest,
        String secretUid,
        String observedResourceVersion) {
      observationCount++;
      if (!OPERATION_ID.equals(operationId)
          || !OPERATION_DIGEST.equals(operationDigest)
          || !SECRET_UID.equals(secretUid)) {
        throw new IllegalStateException("simulated Account observation mismatch");
      }
      return generationRequest(binding, true).toBuilder()
          .setExpectedSecretResourceVersion(observationResourceVersion)
          .build();
    }

    @Override
    public RecordGenerationResultResponse recordGenerationResult(
        String operationId,
        String generationRequestDigest,
        String secretUid,
        String expectedPriorResourceVersion,
        String observedResourceVersion,
        String publicJwkJson) {
      generationResultCount++;
      this.publicJwk = publicJwkJson;
      try {
        return RecordGenerationResultResponse.newBuilder()
            .setSchemaVersion(1)
            .setOperationId(operationId)
            .setOperationDigest(OPERATION_DIGEST)
            .setGenerationRequestDigest(generationRequestDigest)
            .setGenerationReceiptDigest(GENERATION_RECEIPT_DIGEST)
            .setDesiredStateVersion("3")
            .setEnvironmentId(binding.environmentId())
            .setClusterId(binding.clusterId())
            .setNamespace(binding.namespace())
            .setExpectedClusterIncarnationUid(binding.expectedClusterIncarnationUid())
            .setExpectedNamespaceUid(binding.expectedNamespaceUid())
            .setTrustBindingDigest(binding.accountTrustBindingDigest())
            .setTrustConfigRevision(binding.configRevision())
            .setPrivateSecretName(SecretState.NAME)
            .setSecretUid(secretUid)
            .setExpectedPriorResourceVersion(expectedPriorResourceVersion)
            .setObservedResourceVersion(observedResourceVersion)
            .setTargetGeneration("7")
            .setTargetKid("kid_7")
            .setTargetAlgorithm("RS256")
            .setPublicKeyFingerprint(thumbprint(publicJwkJson))
            .setPublicJwkJson(publicJwkJson)
            .build();
      } catch (Exception ex) {
        throw new IllegalStateException("simulated Account generation receipt error");
      }
    }

    @Override
    public GetCurrentPromotionRequestResponse getCurrentPromotionRequest() {
      events.add("promotion");
      if (promotionRequest == null) {
        promotionNotFoundCount++;
        throw new StatusRuntimeException(
            Status.NOT_FOUND.withDescription("No Account-authorized signer promotion is pending"));
      }
      return promotionRequest;
    }

    @Override
    public RecordPrivatePromotionResultResponse recordPrivatePromotionResult(
        GetCurrentPromotionRequestResponse request,
        String secretUid,
        String expectedPriorResourceVersion,
        String observedResourceVersion,
        SignerKeyBundle.PublicIdentity current,
        SignerKeyBundle.PublicIdentity previous,
        List<String> resultingSlots) {
      promotionReceiptCount++;
      this.resultingSlots = List.copyOf(resultingSlots);
      if (failFirstPromotionReceipt && promotionReceiptCount == 1) {
        throw new IllegalStateException("simulated lost Account receipt response");
      }
      return RecordPrivatePromotionResultResponse.newBuilder()
          .setSchemaVersion(1)
          .setPromotionOperationId(request.getPromotionOperationId())
          .setGenerationOperationId(request.getGenerationOperationId())
          .setObservedResourceVersion(observedResourceVersion)
          .setPublicReceiptDigest("f".repeat(64))
          .build();
    }
  }

  private static EnrollBootstrapResponse enrollmentResponse(ProtectedBinding.Binding binding) {
    return EnrollBootstrapResponse.newBuilder()
        .setSchemaVersion(1)
        .setEnvironmentId(binding.environmentId())
        .setClusterId(binding.clusterId())
        .setNamespace(binding.namespace())
        .setExpectedClusterIncarnationUid(binding.expectedClusterIncarnationUid())
        .setExpectedNamespaceUid(binding.expectedNamespaceUid())
        .setMaterializerTrustBindingDigest(binding.accountTrustBindingDigest())
        .setMaterializerTrustConfigRevision(binding.configRevision())
        .setApiBindingDigest("a".repeat(64))
        .setApiConfigRevision("api-revision-1")
        .setApiServerUrl("https://kubernetes.default.svc:6443")
        .setApiTlsServerName("kubernetes.default.svc")
        .setApiServingCaSha256("b".repeat(64))
        .setApiExpectedUsername("system:serviceaccount:" + binding.namespace() + ":account-service")
        .setPrivateSecretName(SecretState.NAME)
        .setPublicConfigMapName("jwt-jwks")
        .setPublicConfigMapUid("33333333-3333-4333-8333-333333333333")
        .setPublicConfigMapResourceVersion("41")
        .setPublicConfigMapSnapshotDigest("c".repeat(64))
        .build();
  }
}
