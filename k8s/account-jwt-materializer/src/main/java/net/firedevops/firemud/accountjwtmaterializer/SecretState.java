package net.firedevops.firemud.accountjwtmaterializer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict, bounded view of the one pre-created Kubernetes Secret. */
final class SecretState {
  static final String NAME = "jwt-signing-keys";
  static final String CURRENT = "current.key";
  static final String PENDING = "pending.key";
  static final String PREVIOUS = "previous.key";
  static final String GENERATION_OPERATION = "firemud.dev/jwt-generation-operation-id";
  static final String GENERATION_REQUEST = "firemud.dev/jwt-generation-request-digest";
  static final String GENERATION_PRIOR_RV = "firemud.dev/jwt-generation-expected-prior-rv";
  static final String PROMOTION_OPERATION = "firemud.dev/jwt-promotion-operation-id";
  static final String PROMOTION_REQUEST = "firemud.dev/jwt-promotion-request-digest";
  static final String PROMOTION_PRIOR_RV = "firemud.dev/jwt-promotion-expected-prior-rv";
  static final int MAX_SECRET_JSON_BYTES = 2 * 1024 * 1024;
  private static final Pattern RESOURCE_VERSION = Pattern.compile("[!-~]{1,256}");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final String namespace;
  private final String uid;
  private final String resourceVersion;
  private final Map<String, String> data;
  private final Map<String, String> annotations;

  private SecretState(
      String namespace,
      String uid,
      String resourceVersion,
      Map<String, String> data,
      Map<String, String> annotations) {
    this.namespace = namespace;
    this.uid = uid;
    this.resourceVersion = resourceVersion;
    this.data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    this.annotations = Collections.unmodifiableMap(new LinkedHashMap<>(annotations));
  }

  static SecretState parse(byte[] bytes, String expectedNamespace) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_SECRET_JSON_BYTES) {
      throw denied();
    }
    try {
      JsonNode root = JSON.readTree(bytes);
      if (root == null
          || !"v1".equals(text(root, "apiVersion"))
          || !"Secret".equals(text(root, "kind"))
          || !NAME.equals(text(root, "metadata", "name"))
          || !expectedNamespace.equals(text(root, "metadata", "namespace"))
          || !"Opaque".equals(text(root, "type"))) {
        throw denied();
      }
      String uid = text(root, "metadata", "uid");
      java.util.UUID parsedUid = java.util.UUID.fromString(uid);
      String resourceVersion = text(root, "metadata", "resourceVersion");
      if (!parsedUid.toString().equals(uid)
          || !RESOURCE_VERSION.matcher(resourceVersion).matches()) {
        throw denied();
      }
      return new SecretState(
          expectedNamespace,
          uid,
          resourceVersion,
          stringMap(root.get("data")),
          stringMap(child(root, "metadata", "annotations")));
    } catch (RuntimeException ex) {
      throw denied();
    }
  }

  String namespace() {
    return namespace;
  }

  String uid() {
    return uid;
  }

  String resourceVersion() {
    return resourceVersion;
  }

  Map<String, String> annotations() {
    return annotations;
  }

  Set<String> signerSlots() {
    Set<String> slots = new LinkedHashSet<>();
    if (data.containsKey(CURRENT)) slots.add(CURRENT);
    if (data.containsKey(PENDING)) slots.add(PENDING);
    if (data.containsKey(PREVIOUS)) slots.add(PREVIOUS);
    return Set.copyOf(slots);
  }

  boolean containsSlot(String slot) {
    return data.containsKey(slot);
  }

  byte[] slotBytes(String slot) {
    String encoded = data.get(slot);
    if (encoded == null || encoded.isEmpty()) {
      return null;
    }
    try {
      return Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException ex) {
      throw denied();
    }
  }

  String encodedSlot(String slot) {
    return data.get(slot);
  }

  boolean hasAnnotation(String key) {
    return annotations.containsKey(key);
  }

  String annotation(String key) {
    return annotations.get(key);
  }

  byte[] generationPatch(
      byte[] pendingBundle,
      String operationId,
      String requestDigest,
      String expectedPriorResourceVersion) {
    Map<String, Object> dataChanges = new LinkedHashMap<>();
    dataChanges.put(PENDING, Base64.getEncoder().encodeToString(pendingBundle));
    Map<String, Object> annotationChanges =
        Map.of(
            GENERATION_OPERATION, operationId,
            GENERATION_REQUEST, requestDigest,
            GENERATION_PRIOR_RV, expectedPriorResourceVersion);
    return patchBody(dataChanges, annotationChanges);
  }

  byte[] promotionPatch(
      byte[] targetBundle,
      byte[] priorCurrentBundle,
      String operationId,
      String requestDigest,
      String expectedPriorResourceVersion) {
    Map<String, Object> dataChanges = new LinkedHashMap<>();
    dataChanges.put(CURRENT, Base64.getEncoder().encodeToString(targetBundle));
    dataChanges.put(PENDING, null);
    dataChanges.put(
        PREVIOUS,
        priorCurrentBundle == null ? null : Base64.getEncoder().encodeToString(priorCurrentBundle));
    Map<String, Object> annotationChanges =
        Map.of(
            PROMOTION_OPERATION, operationId,
            PROMOTION_REQUEST, requestDigest,
            PROMOTION_PRIOR_RV, expectedPriorResourceVersion);
    return patchBody(dataChanges, annotationChanges);
  }

  boolean operationMarkerMatches(
      String idKey,
      String digestKey,
      String priorRvKey,
      String operationId,
      String digest,
      String priorRv) {
    return operationId.equals(annotations.get(idKey))
        && digest.equals(annotations.get(digestKey))
        && priorRv.equals(annotations.get(priorRvKey));
  }

  void requireUnchangedExcept(
      Set<String> changedSlots, Set<String> changedAnnotations, SecretState after) {
    if (!uid.equals(after.uid) || !namespace.equals(after.namespace)) {
      throw denied();
    }
    for (Map.Entry<String, String> entry : data.entrySet()) {
      if (!changedSlots.contains(entry.getKey())
          && !entry.getValue().equals(after.data.get(entry.getKey()))) {
        throw denied();
      }
    }
    for (String key : after.data.keySet()) {
      if (!changedSlots.contains(key) && !data.containsKey(key)) {
        throw denied();
      }
    }
    for (Map.Entry<String, String> entry : annotations.entrySet()) {
      if (!changedAnnotations.contains(entry.getKey())
          && !entry.getValue().equals(after.annotations.get(entry.getKey()))) {
        throw denied();
      }
    }
    for (String key : after.annotations.keySet()) {
      if (!changedAnnotations.contains(key) && !annotations.containsKey(key)) {
        throw denied();
      }
    }
  }

  private byte[] patchBody(Map<String, Object> dataChanges, Map<String, Object> annotationChanges) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("uid", uid);
    metadata.put("resourceVersion", resourceVersion);
    metadata.put("annotations", annotationChanges);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("metadata", metadata);
    body.put("data", dataChanges);
    try {
      return JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw denied();
    }
  }

  private static Map<String, String> stringMap(JsonNode node) {
    if (node == null || node.isNull()) {
      return Map.of();
    }
    if (!node.isObject()) {
      throw denied();
    }
    Map<String, String> values = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : node.properties()) {
      if (!entry.getValue().isTextual()) {
        throw denied();
      }
      values.put(entry.getKey(), entry.getValue().textValue());
    }
    return values;
  }

  private static JsonNode child(JsonNode root, String... path) {
    JsonNode current = root;
    for (String field : path) {
      current = current == null ? null : current.get(field);
    }
    return current;
  }

  private static String text(JsonNode root, String... path) {
    JsonNode node = child(root, path);
    if (node == null || !node.isTextual() || node.textValue().isBlank()) {
      throw denied();
    }
    return node.textValue();
  }

  private static IllegalArgumentException denied() {
    return new IllegalArgumentException("Fixed JWT Secret state is unavailable");
  }
}
