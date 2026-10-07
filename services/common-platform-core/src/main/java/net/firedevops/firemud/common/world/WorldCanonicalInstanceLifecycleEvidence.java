package net.firedevops.firemud.common.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exact, non-admitting World lifecycle owner readback for an authenticated canonical caller.
 *
 * <p>Decoding this carrier does not authenticate World, establish current Account authority, or
 * grant lifecycle activation or gameplay admission. The transport endpoint owns workload
 * authentication; the consumer must keep admission and its current-authority checks separate.
 */
public record WorldCanonicalInstanceLifecycleEvidence(
    Request request,
    CompleteLaunchBindingEvidence launchBinding,
    RoomTemplateRef startLocation,
    long runtimeRoomInstanceId,
    String lifecycleStatus,
    long lifecycleEpoch,
    long rowVersion,
    UUID captureId,
    String graphSha256,
    String preparationInputDigest) {
  public static final String SCHEMA = "world-canonical-instance-lifecycle-evidence/v1";
  public static final String REQUEST_SCHEMA = "world-canonical-instance-lifecycle-read-request/v1";

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern PREFIXED_SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern BARE_SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Set<String> EVIDENCE_FIELDS =
      Set.of(
          "schema",
          "request",
          "launchBindingBytesBase64",
          "startLocation",
          "runtimeRoomInstanceId",
          "lifecycleStatus",
          "lifecycleEpoch",
          "rowVersion",
          "captureId",
          "graphSha256",
          "preparationInputDigest");
  private static final Set<String> REQUEST_FIELDS =
      Set.of(
          "schemaVersion",
          "readRequestId",
          "targetNamespace",
          "canonicalTenantId",
          "worldSlug",
          "canonicalGameInstanceId",
          "playableStateNamespaceId",
          "playableStateScope",
          "publicProduction",
          "controlPlaneRequestId",
          "canonicalVersionId",
          "expectedDescriptorRequestDigest",
          "expectedDescriptorResultDigest",
          "expectedReleaseAttestationDigest");
  private static final Set<String> REQUEST_ENVELOPE_FIELDS = Set.of("schema", "request");
  private static final Set<String> ROOM_TEMPLATE_REF_FIELDS =
      Set.of("tenantId", "versionId", "roomTemplateId");
  private static final Set<String> LIFECYCLE_STATUSES =
      Set.of("PREPARING", "ACTIVE", "FAILED_PRE_ACTIVATION", "TERMINATING", "TERMINATED");
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  public WorldCanonicalInstanceLifecycleEvidence {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(launchBinding, "launchBinding");
    Objects.requireNonNull(startLocation, "startLocation");
    if (runtimeRoomInstanceId <= 0) {
      throw new IllegalArgumentException("runtimeRoomInstanceId must be positive");
    }
    if (lifecycleStatus == null || !LIFECYCLE_STATUSES.contains(lifecycleStatus)) {
      throw new IllegalArgumentException("Unsupported World lifecycle status");
    }
    if (lifecycleEpoch <= 0) {
      throw new IllegalArgumentException("World lifecycleEpoch must be positive");
    }
    if (rowVersion < 0) {
      throw new IllegalArgumentException("World lifecycle rowVersion must be nonnegative");
    }
    requireNonNil(captureId, "captureId");
    requireDigest(graphSha256, BARE_SHA256, "graphSha256");
    requireDigest(preparationInputDigest, PREFIXED_SHA256, "preparationInputDigest");
    requireExactBinding(request, launchBinding, startLocation, graphSha256);
  }

  /** Closed canonical representation with the complete descriptor and separate attestation. */
  public byte[] canonicalBytes() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", SCHEMA);
    value.put("request", requestJson(request));
    value.put(
        "launchBindingBytesBase64",
        Base64.getEncoder().encodeToString(closedLaunchBindingBytes(launchBinding)));
    value.put("startLocation", roomTemplateRefJson(startLocation));
    value.put("runtimeRoomInstanceId", Long.toString(runtimeRoomInstanceId));
    value.put("lifecycleStatus", lifecycleStatus);
    value.put("lifecycleEpoch", Long.toString(lifecycleEpoch));
    value.put("rowVersion", Long.toString(rowVersion));
    value.put("captureId", captureId.toString());
    value.put("graphSha256", graphSha256);
    value.put("preparationInputDigest", preparationInputDigest);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "World canonical lifecycle evidence cannot be encoded", impossible);
    }
  }

  /** Decodes closed retained bytes; this does not authenticate the producing World workload. */
  public static WorldCanonicalInstanceLifecycleEvidence fromStored(byte[] stored) {
    Objects.requireNonNull(stored, "stored");
    try {
      JsonNode root = JSON.readTree(strictUtf8(stored));
      requireFields(root, EVIDENCE_FIELDS, "World canonical lifecycle evidence");
      if (!SCHEMA.equals(text(root, "schema"))) {
        throw new IllegalArgumentException("Unsupported World canonical lifecycle evidence schema");
      }
      Request request = parseRequest(root.get("request"));
      CompleteLaunchBindingEvidence launchBinding =
          parseLaunchBinding(base64(root, "launchBindingBytesBase64"));
      WorldCanonicalInstanceLifecycleEvidence evidence =
          new WorldCanonicalInstanceLifecycleEvidence(
              request,
              launchBinding,
              parseRoomTemplateRef(root.get("startLocation")),
              positiveLong(text(root, "runtimeRoomInstanceId"), "runtimeRoomInstanceId"),
              text(root, "lifecycleStatus"),
              positiveLong(text(root, "lifecycleEpoch"), "lifecycleEpoch"),
              nonNegativeLong(text(root, "rowVersion"), "rowVersion"),
              parseUuid(text(root, "captureId"), "captureId"),
              text(root, "graphSha256"),
              text(root, "preparationInputDigest"));
      if (!Arrays.equals(stored, evidence.canonicalBytes())) {
        throw new IllegalArgumentException("World canonical lifecycle evidence is not canonical");
      }
      return evidence;
    } catch (tools.jackson.core.JacksonException invalid) {
      throw new IllegalArgumentException("World canonical lifecycle evidence is invalid", invalid);
    }
  }

  private static void requireExactBinding(
      Request request,
      CompleteLaunchBindingEvidence binding,
      RoomTemplateRef startLocation,
      String graphSha256) {
    AuthoredWorldLaunchDescriptorEvidence descriptor = binding.descriptor();
    AuthoredWorldReleaseAttestationEvidence release = binding.releaseAttestation();
    descriptor.requireValid();
    release.requireValid(descriptor);
    if (!request.targetNamespace().equals(descriptor.targetNamespace())
        || !request.canonicalTenantId().equals(descriptor.canonicalTenantId())
        || !request.worldSlug().equals(descriptor.worldSlug())
        || !request.controlPlaneRequestId().equals(descriptor.controlPlaneRequestId())
        || !request.expectedDescriptorRequestDigest().equals(descriptor.requestDigest())
        || !request.expectedDescriptorResultDigest().equals(descriptor.resultDigest())
        || !request.expectedReleaseAttestationDigest().equals(release.evidenceDigest())
        || !request.canonicalVersionId().equals(release.canonicalVersionId())) {
      throw new IllegalArgumentException(
          "World lifecycle request differs from the complete launch binding");
    }
    if (release.schemaVersion() != AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION
        || release.worldStartLocationEvidence() == null) {
      throw new IllegalArgumentException(
          "World lifecycle evidence requires the complete selector/v2 release attestation");
    }
    WorldDraftStartLocationEvidence selectorReceipt =
        WorldDraftStartLocationEvidence.fromStored(
            release.worldStartLocationEvidence().selectorReceiptBytes());
    String selectedGraphSha256 = selectorReceipt.graphDigest().substring("sha256:".length());
    if (!selectorReceipt.startLocation().equals(startLocation)
        || !selectedGraphSha256.equals(graphSha256)) {
      throw new IllegalArgumentException(
          "World lifecycle ROOM selector or graph digest differs from the complete release selector");
    }
  }

  private static byte[] closedLaunchBindingBytes(CompleteLaunchBindingEvidence binding) {
    try {
      // Preserve every original descriptor/release value, including long numeric fields, inside
      // the opaque outer base64 envelope instead of subjecting those owner bytes to RFC 8785's
      // JavaScript-number range.
      return JSON.writeValueAsString(binding).getBytes(StandardCharsets.UTF_8);
    } catch (tools.jackson.core.JacksonException impossible) {
      throw new IllegalStateException("Complete launch binding cannot be encoded", impossible);
    }
  }

  private static CompleteLaunchBindingEvidence parseLaunchBinding(byte[] stored) {
    try {
      JsonNode node = JSON.readTree(strictUtf8(stored));
      requireFields(node, Set.of("descriptor", "releaseAttestation"), "complete launch binding");
      CompleteLaunchBindingEvidence binding =
          JSON.treeToValue(node, CompleteLaunchBindingEvidence.class);
      if (!Arrays.equals(stored, closedLaunchBindingBytes(binding))) {
        throw new IllegalArgumentException(
            "Nested complete launch binding bytes are not canonical");
      }
      return binding;
    } catch (tools.jackson.core.JacksonException invalid) {
      throw new IllegalArgumentException(
          "Nested complete launch binding bytes are invalid", invalid);
    }
  }

  private static Request parseRequest(JsonNode node) {
    requireFields(node, REQUEST_FIELDS, "World canonical lifecycle request");
    JsonNode publicProduction = node.get("publicProduction");
    if (publicProduction == null || !publicProduction.isBoolean()) {
      throw new IllegalArgumentException("publicProduction must be present as a boolean");
    }
    return new Request(
        intValue(node, "schemaVersion"),
        parseUuid(text(node, "readRequestId"), "readRequestId"),
        text(node, "targetNamespace"),
        parseUuid(text(node, "canonicalTenantId"), "canonicalTenantId"),
        text(node, "worldSlug"),
        parseUuid(text(node, "canonicalGameInstanceId"), "canonicalGameInstanceId"),
        parseUuid(text(node, "playableStateNamespaceId"), "playableStateNamespaceId"),
        text(node, "playableStateScope"),
        publicProduction.booleanValue(),
        text(node, "controlPlaneRequestId"),
        parseUuid(text(node, "canonicalVersionId"), "canonicalVersionId"),
        text(node, "expectedDescriptorRequestDigest"),
        text(node, "expectedDescriptorResultDigest"),
        text(node, "expectedReleaseAttestationDigest"));
  }

  private static RoomTemplateRef parseRoomTemplateRef(JsonNode node) {
    requireFields(node, ROOM_TEMPLATE_REF_FIELDS, "World lifecycle startLocation");
    return new RoomTemplateRef(
        parseUuid(text(node, "tenantId"), "startLocation.tenantId"),
        parseUuid(text(node, "versionId"), "startLocation.versionId"),
        parseUuid(text(node, "roomTemplateId"), "startLocation.roomTemplateId"));
  }

  private static Map<String, Object> requestJson(Request request) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schemaVersion", request.schemaVersion());
    value.put("readRequestId", request.readRequestId().toString());
    value.put("targetNamespace", request.targetNamespace());
    value.put("canonicalTenantId", request.canonicalTenantId().toString());
    value.put("worldSlug", request.worldSlug());
    value.put("canonicalGameInstanceId", request.canonicalGameInstanceId().toString());
    value.put("playableStateNamespaceId", request.playableStateNamespaceId().toString());
    value.put("playableStateScope", request.playableStateScope());
    value.put("publicProduction", request.publicProduction());
    value.put("controlPlaneRequestId", request.controlPlaneRequestId());
    value.put("canonicalVersionId", request.canonicalVersionId().toString());
    value.put("expectedDescriptorRequestDigest", request.expectedDescriptorRequestDigest());
    value.put("expectedDescriptorResultDigest", request.expectedDescriptorResultDigest());
    value.put("expectedReleaseAttestationDigest", request.expectedReleaseAttestationDigest());
    return value;
  }

  private static Map<String, Object> roomTemplateRefJson(RoomTemplateRef reference) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("tenantId", reference.tenantId().toString());
    value.put("versionId", reference.versionId().toString());
    value.put("roomTemplateId", reference.roomTemplateId().toString());
    return value;
  }

  private static byte[] base64(JsonNode node, String field) {
    try {
      return Base64.getDecoder().decode(text(node, field));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Invalid base64 field " + field, invalid);
    }
  }

  private static String text(JsonNode object, String field) {
    JsonNode value = object == null ? null : object.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.textValue();
  }

  private static int intValue(JsonNode object, String field) {
    JsonNode value = object == null ? null : object.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException(field + " must be an integer");
    }
    return value.intValue();
  }

  private static long positiveLong(String value, String field) {
    if (value == null || !value.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException(field + " must be canonical positive decimal");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException(field + " exceeds the supported integer range", invalid);
    }
  }

  private static long nonNegativeLong(String value, String field) {
    if (value == null || !value.matches("0|[1-9][0-9]*")) {
      throw new IllegalArgumentException(field + " must be canonical nonnegative decimal");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException(field + " exceeds the supported integer range", invalid);
    }
  }

  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (java.nio.charset.CharacterCodingException invalid) {
      throw new IllegalArgumentException(
          "Canonical World lifecycle bytes are not strict UTF-8", invalid);
    }
  }

  private static UUID parseUuid(String value, String label) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || NIL_UUID.equals(parsed)) {
        throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID", invalid);
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private static void requireDigest(String value, Pattern pattern, String label) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " is not a canonical SHA-256 digest");
    }
  }

  private static void requireFields(JsonNode object, Set<String> fields, String label) {
    if (object == null || !object.isObject()) {
      throw new IllegalArgumentException(label + " must be an object");
    }
    Set<String> actual = new java.util.HashSet<>();
    object.propertyNames().forEach(actual::add);
    if (!actual.equals(fields)) {
      throw new IllegalArgumentException(label + " has missing or unsupported fields");
    }
  }

  /** Exact shared request envelope for the read-only World lifecycle capture. */
  public record Request(
      int schemaVersion,
      UUID readRequestId,
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID canonicalGameInstanceId,
      UUID playableStateNamespaceId,
      String playableStateScope,
      boolean publicProduction,
      String controlPlaneRequestId,
      UUID canonicalVersionId,
      String expectedDescriptorRequestDigest,
      String expectedDescriptorResultDigest,
      String expectedReleaseAttestationDigest) {
    public static final int SCHEMA_VERSION = 1;

    public Request {
      if (schemaVersion != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported World canonical lifecycle read schema");
      }
      requireNonNil(readRequestId, "readRequestId");
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("targetNamespace must be a canonical DNS label");
      }
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
      requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
      requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
      if (!"SHARED".equals(playableStateScope)) {
        throw new IllegalArgumentException(
            "This lifecycle carrier accepts SHARED scope only; private/playtest proof is unavailable");
      }
      if (!publicProduction) {
        throw new IllegalArgumentException(
            "This lifecycle carrier requires public-production evidence");
      }
      requireText(controlPlaneRequestId, "controlPlaneRequestId", 128);
      requireNonNil(canonicalVersionId, "canonicalVersionId");
      requireDigest(
          expectedDescriptorRequestDigest, PREFIXED_SHA256, "expectedDescriptorRequestDigest");
      requireDigest(
          expectedDescriptorResultDigest, PREFIXED_SHA256, "expectedDescriptorResultDigest");
      requireDigest(
          expectedReleaseAttestationDigest, PREFIXED_SHA256, "expectedReleaseAttestationDigest");
    }

    /** Closed canonical request bytes used by the standalone World read RPC. */
    public byte[] canonicalBytes() {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("schema", REQUEST_SCHEMA);
      value.put("request", requestJson(this));
      try {
        return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      } catch (IOException impossible) {
        throw new IllegalStateException(
            "World canonical lifecycle request cannot be encoded", impossible);
      }
    }

    /** Decodes only a complete closed request; this does not authenticate its sender. */
    public static Request fromStored(byte[] stored) {
      Objects.requireNonNull(stored, "stored");
      try {
        JsonNode root = JSON.readTree(strictUtf8(stored));
        requireFields(root, REQUEST_ENVELOPE_FIELDS, "World canonical lifecycle read request");
        if (!REQUEST_SCHEMA.equals(text(root, "schema"))) {
          throw new IllegalArgumentException(
              "Unsupported World canonical lifecycle request schema");
        }
        Request request = parseRequest(root.get("request"));
        if (!Arrays.equals(stored, request.canonicalBytes())) {
          throw new IllegalArgumentException("World canonical lifecycle request is not canonical");
        }
        return request;
      } catch (tools.jackson.core.JacksonException invalid) {
        throw new IllegalArgumentException("World canonical lifecycle request is invalid", invalid);
      }
    }

    private static void requireText(String value, String label, int maxLength) {
      if (value == null || value.isBlank() || value.length() > maxLength) {
        throw new IllegalArgumentException(label + " is required and must fit its limit");
      }
    }
  }
}
