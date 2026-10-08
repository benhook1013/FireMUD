package net.firedevops.firemud.accountservice.service.session;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ApiCall;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ApiResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ParsedBinding;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Account-only Kubernetes adapter for the pre-created public {@code jwt-jwks} ConfigMap. Every
 * write is a fixed-name, resourceVersion-guarded merge patch followed by exact readback. This class
 * is intentionally not a lifecycle, readiness, or signer-promotion authority.
 */
public final class AccountJwtJwksConfigMapClient {
  public static final String CONFIG_MAP_NAME = "jwt-jwks";
  public static final String JWKS_DATA_KEY = "jwks.json";
  public static final String GENERATION_DATA_KEY = "jwt-generation.json";
  public static final int MAX_DESIRED_VALUE_BYTES = 768 * 1024;
  private static final int MAX_CONFIG_MAP_DATA_KEYS = 128;
  private static final int MAX_CONFIG_MAP_DATA_BYTES = 1024 * 1024;
  private static final Pattern RESOURCE_VERSION = Pattern.compile("[!-~]{1,256}");
  private static final Pattern UID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final Set<String> ACCOUNT_DATA_KEYS = Set.of(JWKS_DATA_KEY, GENERATION_DATA_KEY);
  private static final Set<String> PRIVATE_JWK_FIELDS =
      Set.of("d", "p", "q", "dp", "dq", "qi", "oth", "k");
  private static final byte[] SELF_SUBJECT_REVIEW_REQUEST =
      "{\"apiVersion\":\"authentication.k8s.io/v1\",\"kind\":\"SelfSubjectReview\",\"spec\":{}}"
          .getBytes(StandardCharsets.UTF_8);
  private static final JsonMapper STRICT_JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final Supplier<ApiSession> operationSupplier;

  /** Production constructor; API TLS and bearer handling remain inside the protected binding. */
  public AccountJwtJwksConfigMapClient(AccountJwtJwksApiBinding binding) {
    AccountJwtJwksApiBinding requiredBinding =
        Objects.requireNonNull(binding, "Account JWT JWKS API binding is required");
    this.operationSupplier =
        () -> {
          AccountJwtJwksApiBinding.ApiOperation operation = requiredBinding.beginOperation();
          return new ApiSession() {
            @Override
            public ParsedBinding binding() {
              return operation.binding();
            }

            @Override
            public ApiResponse send(ApiCall call, byte[] body) {
              return operation.send(call, body);
            }

            @Override
            public void close() {
              operation.close();
            }
          };
        };
  }

  /** Deterministic bounded transport seam for focused tests. */
  AccountJwtJwksConfigMapClient(Supplier<ApiSession> operationSupplier) {
    this.operationSupplier =
        Objects.requireNonNull(operationSupplier, "API operation supplier is required");
  }

  /**
   * Returns only the independently protected non-secret API/destination pins. Callers can compare
   * these with Account's materializer binding before creating durable publication intent.
   */
  public BindingIdentity identity() {
    try (ApiSession operation = beginOperation()) {
      return toIdentity(operation.binding());
    }
  }

  /**
   * Reads only the fixed pre-created public ConfigMap after checking Account API identity and both
   * namespace incarnation pins. No list, create, delete, or Secret endpoint is available.
   */
  public ConfigMapSnapshot observe() {
    try (ApiSession operation = beginOperation()) {
      verifyEnvironment(operation);
      return readConfigMap(operation);
    }
  }

  /**
   * Applies an exact owner-authored public data map using Kubernetes resourceVersion CAS. Only
   * {@code jwks.json} and {@code jwt-generation.json} are accepted; unrelated data is preserved. An
   * ambiguous write is successful only after a fresh exact-content readback on the same UID.
   */
  public CasObservation publish(ConfigMapSnapshot expected, Map<String, String> desiredPublicData) {
    Objects.requireNonNull(expected, "expected ConfigMap snapshot is required");
    Map<String, String> desired = validateDesiredPublicData(desiredPublicData);
    requireSnapshotIdentity(expected);

    try (ApiSession operation = beginOperation()) {
      return publish(operation, expected, desired);
    }
  }

  /**
   * Reconciles Account's exact active-marker transition from its durable prepublication RV. The
   * freshly observed full snapshot preserves unrelated entries on a first write; an exact replay is
   * accepted only when the live Account-owned data already matches and its RV advanced beyond the
   * original prepublication fence.
   */
  public CasObservation publishActiveProjection(
      ConfigMapSnapshot observed,
      String expectedPriorResourceVersion,
      Map<String, String> desiredPublicData) {
    Objects.requireNonNull(observed, "fresh ConfigMap observation is required");
    Map<String, String> desired = validateDesiredPublicData(desiredPublicData);
    requireSnapshotIdentity(observed);
    if (expectedPriorResourceVersion == null
        || !RESOURCE_VERSION.matcher(expectedPriorResourceVersion).matches()) {
      throw new IllegalArgumentException("Active ConfigMap prior resourceVersion is malformed");
    }

    try (ApiSession operation = beginOperation()) {
      verifyEnvironment(operation);
      ConfigMapSnapshot before = readConfigMap(operation);
      if (!before.uid().equals(observed.uid())
          || !before.resourceVersion().equals(observed.resourceVersion())
          || !before.data().equals(observed.data())) {
        throw conflict(before);
      }
      if (sameAccountData(before.data(), desired)) {
        if (before.resourceVersion().equals(expectedPriorResourceVersion)) {
          throw conflict(before);
        }
        return observationWithPrior(
            expectedPriorResourceVersion, before, desired, Outcome.EXACT_REPLAY);
      }
      if (!before.resourceVersion().equals(expectedPriorResourceVersion)) {
        throw conflict(before);
      }

      byte[] patch = createMergePatch(before, desired);
      verifyEnvironment(operation);
      ApiResponse response;
      try {
        response = operation.send(ApiCall.PATCH_JWKS_CONFIG_MAP, patch);
      } catch (AccountJwtJwksApiBinding.BindingRejectedException ex) {
        throw new BindingRejectedException();
      } catch (AccountJwtJwksApiBinding.ApiTransportException ex) {
        return reconcileUnknownWrite(operation, before, before, desired);
      }
      if (response.statusCode() == 409) {
        verifyEnvironment(operation);
        throw conflict(readConfigMap(operation));
      }
      if (response.statusCode() == 404) {
        throw new ResourceMissingException();
      }
      if (response.statusCode() >= 500 || response.statusCode() == 408) {
        return reconcileUnknownWrite(operation, before, before, desired);
      }
      if (!isSuccess(response.statusCode())) {
        throw new ApiFailureException(response.statusCode());
      }
      ConfigMapSnapshot readback;
      try {
        verifyEnvironment(operation);
        readback = readConfigMap(operation);
      } catch (RuntimeException ex) {
        throw new UncertainOutcomeException();
      }
      requireExactPostcondition(before, before, readback, desired);
      return observationWithPrior(expectedPriorResourceVersion, readback, desired, Outcome.APPLIED);
    }
  }

  private CasObservation publish(
      ApiSession operation, ConfigMapSnapshot expected, Map<String, String> desired) {
    verifyEnvironment(operation);
    ConfigMapSnapshot before = readConfigMap(operation);
    if (!before.uid().equals(expected.uid())) {
      throw conflict(before);
    }
    if (sameAccountData(before.data(), desired)) {
      if (before.resourceVersion().equals(expected.resourceVersion())) {
        throw conflict(before);
      }
      return observation(expected, before, desired, Outcome.EXACT_REPLAY);
    }
    if (!before.resourceVersion().equals(expected.resourceVersion())) {
      throw conflict(before);
    }

    byte[] patch = createMergePatch(expected, desired);
    // Identity checks are repeated immediately before the only write call.
    verifyEnvironment(operation);
    ApiResponse response;
    try {
      response = operation.send(ApiCall.PATCH_JWKS_CONFIG_MAP, patch);
    } catch (AccountJwtJwksApiBinding.BindingRejectedException ex) {
      throw new BindingRejectedException();
    } catch (AccountJwtJwksApiBinding.ApiTransportException ex) {
      return reconcileUnknownWrite(operation, expected, before, desired);
    }
    if (response.statusCode() == 409) {
      verifyEnvironment(operation);
      throw conflict(readConfigMap(operation));
    }
    if (response.statusCode() == 404) {
      throw new ResourceMissingException();
    }
    if (response.statusCode() >= 500 || response.statusCode() == 408) {
      return reconcileUnknownWrite(operation, expected, before, desired);
    }
    if (!isSuccess(response.statusCode())) {
      throw new ApiFailureException(response.statusCode());
    }

    ConfigMapSnapshot readback;
    try {
      verifyEnvironment(operation);
      readback = readConfigMap(operation);
    } catch (RuntimeException ex) {
      throw new UncertainOutcomeException();
    }
    requireExactPostcondition(expected, before, readback, desired);
    return observation(expected, readback, desired, Outcome.APPLIED);
  }

  private CasObservation reconcileUnknownWrite(
      ApiSession operation,
      ConfigMapSnapshot expected,
      ConfigMapSnapshot before,
      Map<String, String> desired) {
    try {
      verifyEnvironment(operation);
      ConfigMapSnapshot observed = readConfigMap(operation);
      if (observed.uid().equals(expected.uid()) && sameAccountData(observed.data(), desired)) {
        requirePreservedUnrelatedData(before, observed);
        if (observed.resourceVersion().equals(expected.resourceVersion())) {
          throw new UncertainOutcomeException();
        }
        return observation(expected, observed, desired, Outcome.EXACT_REPLAY);
      }
    } catch (UncertainOutcomeException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      // The write outcome remains unknown when reconciliation itself is unavailable.
    }
    throw new UncertainOutcomeException();
  }

  private void requireExactPostcondition(
      ConfigMapSnapshot expected,
      ConfigMapSnapshot before,
      ConfigMapSnapshot readback,
      Map<String, String> desired) {
    if (!readback.uid().equals(expected.uid())
        || readback.resourceVersion().equals(expected.resourceVersion())
        || !sameAccountData(readback.data(), desired)) {
      throw new UncertainOutcomeException();
    }
    requirePreservedUnrelatedData(before, readback);
  }

  private static void requirePreservedUnrelatedData(
      ConfigMapSnapshot before, ConfigMapSnapshot after) {
    Map<String, String> previous = unrelatedData(before.data());
    Map<String, String> current = unrelatedData(after.data());
    if (!previous.equals(current)) {
      throw new UncertainOutcomeException();
    }
  }

  private static Map<String, String> unrelatedData(Map<String, String> data) {
    Map<String, String> unrelated = new LinkedHashMap<>();
    data.forEach(
        (key, value) -> {
          if (!ACCOUNT_DATA_KEYS.contains(key)) {
            unrelated.put(key, value);
          }
        });
    return Map.copyOf(unrelated);
  }

  private ConfigMapSnapshot readConfigMap(ApiSession operation) {
    ApiResponse response = send(operation, ApiCall.READ_JWKS_CONFIG_MAP, null);
    if (response.statusCode() == 404) {
      throw new ResourceMissingException();
    }
    if (!isSuccess(response.statusCode())) {
      throw new ApiFailureException(response.statusCode());
    }
    return parseConfigMap(response.body(), operation.binding());
  }

  private void verifyEnvironment(ApiSession operation) {
    ParsedBinding binding = operation.binding();
    ApiResponse selfReview =
        send(operation, ApiCall.REVIEW_AUTHENTICATED_PRINCIPAL, SELF_SUBJECT_REVIEW_REQUEST);
    if ((selfReview.statusCode() != 200 && selfReview.statusCode() != 201)
        || !binding.expectedApiUsername().equals(parseAuthenticatedUsername(selfReview.body()))) {
      throw new BindingRejectedException();
    }
    verifyNamespace(
        send(operation, ApiCall.READ_KUBE_SYSTEM_NAMESPACE, null),
        "kube-system",
        binding.expectedClusterIncarnationUid());
    verifyNamespace(
        send(operation, ApiCall.READ_TARGET_NAMESPACE, null),
        binding.namespace(),
        binding.expectedNamespaceUid());
  }

  private void verifyNamespace(ApiResponse response, String name, String expectedUid) {
    if (!isSuccess(response.statusCode())) {
      if (response.statusCode() == 404) {
        throw new BindingRejectedException();
      }
      throw new ApiFailureException(response.statusCode());
    }
    JsonNode root = parseJson(response.body());
    JsonNode metadata = root.get("metadata");
    JsonNode status = root.get("status");
    if (!"v1".equals(text(root.get("apiVersion")))
        || !"Namespace".equals(text(root.get("kind")))
        || metadata == null
        || !name.equals(text(metadata.get("name")))
        || !expectedUid.equals(canonicalUid(text(metadata.get("uid"))))
        || metadata.hasNonNull("deletionTimestamp")
        || status == null
        || !"Active".equals(text(status.get("phase")))) {
      throw new BindingRejectedException();
    }
  }

  private static String parseAuthenticatedUsername(byte[] bytes) {
    JsonNode root = parseJson(bytes);
    JsonNode status = root.get("status");
    JsonNode userInfo = status == null ? null : status.get("userInfo");
    String username = userInfo == null ? null : text(userInfo.get("username"));
    JsonNode evaluationError = status == null ? null : status.get("evaluationError");
    if (!"authentication.k8s.io/v1".equals(text(root.get("apiVersion")))
        || !"SelfSubjectReview".equals(text(root.get("kind")))
        || username == null
        || (evaluationError != null
            && !evaluationError.isNull()
            && (!evaluationError.isTextual() || !evaluationError.asText().isEmpty()))) {
      throw new BindingRejectedException();
    }
    return username;
  }

  private static ConfigMapSnapshot parseConfigMap(byte[] bytes, ParsedBinding binding) {
    JsonNode root = parseJson(bytes);
    JsonNode metadata = root.get("metadata");
    if (!"v1".equals(text(root.get("apiVersion")))
        || !"ConfigMap".equals(text(root.get("kind")))
        || metadata == null
        || !CONFIG_MAP_NAME.equals(text(metadata.get("name")))
        || !binding.namespace().equals(text(metadata.get("namespace")))
        || metadata.hasNonNull("deletionTimestamp")
        || Boolean.TRUE.equals(booleanValue(root.get("immutable")))
        || (root.has("binaryData") && !root.get("binaryData").isNull())) {
      throw new ApiFailureException(0);
    }
    String uid = canonicalUid(text(metadata.get("uid")));
    String resourceVersion = text(metadata.get("resourceVersion"));
    if (resourceVersion == null || !RESOURCE_VERSION.matcher(resourceVersion).matches()) {
      throw new ApiFailureException(0);
    }
    Map<String, String> data = parseData(root.get("data"));
    return new ConfigMapSnapshot(uid, resourceVersion, data);
  }

  private static Map<String, String> parseData(JsonNode dataNode) {
    if (dataNode == null || dataNode.isNull()) {
      return Map.of();
    }
    if (!dataNode.isObject() || dataNode.size() > MAX_CONFIG_MAP_DATA_KEYS) {
      throw new ApiFailureException(0);
    }
    Map<String, String> data = new LinkedHashMap<>();
    int totalBytes = 0;
    for (Map.Entry<String, JsonNode> entry : dataNode.properties()) {
      JsonNode value = entry.getValue();
      if (!value.isTextual()) {
        throw new ApiFailureException(0);
      }
      String text = value.asText();
      totalBytes += text.getBytes(StandardCharsets.UTF_8).length;
      if (totalBytes > MAX_CONFIG_MAP_DATA_BYTES) {
        throw new ApiFailureException(0);
      }
      data.put(entry.getKey(), text);
    }
    return Map.copyOf(data);
  }

  private ApiSession beginOperation() {
    try {
      return Objects.requireNonNull(operationSupplier.get(), "API operation is unavailable");
    } catch (AccountJwtJwksApiBinding.BindingRejectedException ex) {
      throw new BindingRejectedException();
    } catch (RuntimeException ex) {
      throw new BindingRejectedException();
    }
  }

  private ApiResponse send(ApiSession operation, ApiCall call, byte[] body) {
    try {
      return operation.send(call, body);
    } catch (AccountJwtJwksApiBinding.BindingRejectedException ex) {
      throw new BindingRejectedException();
    } catch (AccountJwtJwksApiBinding.ApiTransportException ex) {
      throw new ApiFailureException(0);
    }
  }

  private static BindingIdentity toIdentity(ParsedBinding binding) {
    return new BindingIdentity(
        binding.bindingDigest(),
        binding.configRevision(),
        binding.environmentId(),
        binding.clusterId(),
        binding.namespace(),
        binding.expectedClusterIncarnationUid(),
        binding.expectedNamespaceUid(),
        binding.apiServer().toString(),
        binding.tlsServerName(),
        binding.servingCaSha256(),
        binding.expectedApiUsername());
  }

  private static Map<String, String> validateDesiredPublicData(Map<String, String> desired) {
    if (desired == null || !desired.keySet().equals(ACCOUNT_DATA_KEYS)) {
      throw new IllegalArgumentException("Account public JWKS data keys are invalid");
    }
    Map<String, String> copy = new LinkedHashMap<>();
    int total = 0;
    for (String key : ACCOUNT_DATA_KEYS) {
      String value = desired.get(key);
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("Account public JWKS data is incomplete");
      }
      int length = value.getBytes(StandardCharsets.UTF_8).length;
      if (length > MAX_DESIRED_VALUE_BYTES) {
        throw new IllegalArgumentException("Account public JWKS data exceeds its bound");
      }
      total += length;
      copy.put(key, value);
    }
    if (total > MAX_CONFIG_MAP_DATA_BYTES) {
      throw new IllegalArgumentException("Account public JWKS data exceeds its bound");
    }
    validatePublicJwks(copy.get(JWKS_DATA_KEY));
    return Map.copyOf(copy);
  }

  private static void validatePublicJwks(String jwks) {
    JsonNode root = parseJson(jwks.getBytes(StandardCharsets.UTF_8));
    JsonNode keys = root.get("keys");
    if (!root.isObject() || root.size() != 1 || keys == null || !keys.isArray() || keys.isEmpty()) {
      throw new IllegalArgumentException("Account public JWKS is malformed");
    }
    for (JsonNode key : keys) {
      if (!key.isObject()) {
        throw new IllegalArgumentException("Account public JWKS is malformed");
      }
      for (String privateField : PRIVATE_JWK_FIELDS) {
        if (key.has(privateField)) {
          throw new IllegalArgumentException("Account public JWKS contains private material");
        }
      }
    }
  }

  private static byte[] createMergePatch(ConfigMapSnapshot expected, Map<String, String> desired) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("uid", expected.uid());
    metadata.put("resourceVersion", expected.resourceVersion());
    Map<String, Object> patch = new LinkedHashMap<>();
    patch.put("metadata", metadata);
    patch.put("data", desired);
    try {
      return STRICT_JSON.writeValueAsBytes(patch);
    } catch (Exception ex) {
      throw new IllegalStateException("Account public JWKS patch could not be encoded");
    }
  }

  private static boolean sameAccountData(Map<String, String> current, Map<String, String> desired) {
    return desired.get(JWKS_DATA_KEY).equals(current.get(JWKS_DATA_KEY))
        && desired.get(GENERATION_DATA_KEY).equals(current.get(GENERATION_DATA_KEY));
  }

  private static CasObservation observation(
      ConfigMapSnapshot expected,
      ConfigMapSnapshot observed,
      Map<String, String> desired,
      Outcome outcome) {
    Map<String, String> exactOutput = new LinkedHashMap<>();
    exactOutput.put(JWKS_DATA_KEY, desired.get(JWKS_DATA_KEY));
    exactOutput.put(GENERATION_DATA_KEY, desired.get(GENERATION_DATA_KEY));
    return new CasObservation(
        observed.uid(),
        expected.resourceVersion(),
        observed.resourceVersion(),
        exactOutput,
        observed.data(),
        outcome);
  }

  private static CasObservation observationWithPrior(
      String priorResourceVersion,
      ConfigMapSnapshot observed,
      Map<String, String> desired,
      Outcome outcome) {
    Map<String, String> exactOutput = new LinkedHashMap<>();
    exactOutput.put(JWKS_DATA_KEY, desired.get(JWKS_DATA_KEY));
    exactOutput.put(GENERATION_DATA_KEY, desired.get(GENERATION_DATA_KEY));
    return new CasObservation(
        observed.uid(),
        priorResourceVersion,
        observed.resourceVersion(),
        exactOutput,
        observed.data(),
        outcome);
  }

  private static CasConflictException conflict(ConfigMapSnapshot observed) {
    return new CasConflictException(observed.uid(), observed.resourceVersion());
  }

  private static void requireSnapshotIdentity(ConfigMapSnapshot snapshot) {
    if (!validUid(snapshot.uid())
        || snapshot.resourceVersion() == null
        || !RESOURCE_VERSION.matcher(snapshot.resourceVersion()).matches()) {
      throw new IllegalArgumentException("Expected ConfigMap snapshot identity is malformed");
    }
  }

  private static boolean isSuccess(int statusCode) {
    return statusCode >= 200 && statusCode < 300;
  }

  private static JsonNode parseJson(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_CONFIG_MAP_DATA_BYTES) {
      throw new ApiFailureException(0);
    }
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      JsonNode root = STRICT_JSON.readTree(text);
      if (root == null || !root.isObject()) {
        throw new ApiFailureException(0);
      }
      return root;
    } catch (CharacterCodingException | RuntimeException ex) {
      throw new ApiFailureException(0);
    }
  }

  private static String text(JsonNode node) {
    return node != null && node.isTextual() && !node.asText().isEmpty() ? node.asText() : null;
  }

  @SuppressFBWarnings(
      value = "NP_BOOLEAN_RETURN_NULL",
      justification =
          "The parser intentionally preserves absent or malformed booleans as unknown; callers require explicit true.")
  private static Boolean booleanValue(JsonNode node) {
    return node != null && node.isBoolean() ? node.booleanValue() : null;
  }

  private static String canonicalUid(String value) {
    if (!validUid(value)) {
      throw new ApiFailureException(0);
    }
    return value;
  }

  private static boolean validUid(String value) {
    return value != null
        && UID.matcher(value).matches()
        && !"00000000-0000-0000-0000-000000000000".equals(value);
  }

  interface ApiSession extends AutoCloseable {
    ParsedBinding binding();

    ApiResponse send(ApiCall call, byte[] body);

    @Override
    void close();
  }

  public record BindingIdentity(
      String bindingDigest,
      String configRevision,
      String environmentId,
      String clusterId,
      String namespace,
      String expectedClusterIncarnationUid,
      String expectedNamespaceUid,
      String apiServerUrl,
      String tlsServerName,
      String servingCaSha256,
      String expectedApiUsername) {}

  public record ConfigMapSnapshot(String uid, String resourceVersion, Map<String, String> data) {
    public ConfigMapSnapshot {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  public record CasObservation(
      String uid,
      String priorResourceVersion,
      String resourceVersion,
      Map<String, String> publishedData,
      Map<String, String> readbackData,
      Outcome outcome) {
    public CasObservation {
      publishedData = Map.copyOf(publishedData);
      readbackData = Map.copyOf(readbackData);
    }
  }

  public enum Outcome {
    APPLIED,
    EXACT_REPLAY
  }

  public static class ResourceMissingException extends RuntimeException {
    public ResourceMissingException() {
      super("Pre-created Account public JWKS ConfigMap is unavailable");
    }
  }

  public static class CasConflictException extends RuntimeException {
    private final String observedUid;
    private final String observedResourceVersion;

    public CasConflictException(String observedUid, String observedResourceVersion) {
      super("Account public JWKS ConfigMap compare-and-set conflict");
      this.observedUid = observedUid;
      this.observedResourceVersion = observedResourceVersion;
    }

    public String observedUid() {
      return observedUid;
    }

    public String observedResourceVersion() {
      return observedResourceVersion;
    }
  }

  public static class UncertainOutcomeException extends RuntimeException {
    public UncertainOutcomeException() {
      super("Account public JWKS ConfigMap write outcome is uncertain");
    }
  }

  public static class ApiFailureException extends RuntimeException {
    private final int statusCode;

    public ApiFailureException(int statusCode) {
      super("Account public JWKS Kubernetes API request failed");
      this.statusCode = statusCode;
    }

    public int statusCode() {
      return statusCode;
    }
  }

  public static class BindingRejectedException extends RuntimeException {
    public BindingRejectedException() {
      super("Account public JWKS API binding or identity was rejected");
    }
  }
}
