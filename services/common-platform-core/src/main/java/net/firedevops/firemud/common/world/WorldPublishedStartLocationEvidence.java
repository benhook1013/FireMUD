package net.firedevops.firemud.common.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exact World selector readback for an immutable frozen publication selection.
 *
 * <p>This evidence does not establish current Account permission, complete release evidence,
 * activation, or admission. Decoding and relation checks do not authenticate the producer. The
 * explicit mTLS client is responsible for authenticating World before accepting it.
 */
public record WorldPublishedStartLocationEvidence(
    Request request,
    byte[] selectorReceiptBytes,
    byte[] originalAccountBindingBytes,
    byte[] appliedResultBytes) {
  public static final String SCHEMA = "world-published-start-location-evidence/v1";
  private static final int APPLIED_INTAKE_REQUEST_ID_FRAME = 13;

  private static final Set<String> EVIDENCE_FIELDS =
      Set.of(
          "schema",
          "request",
          "selectorReceiptBytesBase64",
          "originalAccountBindingBytesBase64",
          "appliedResultBytesBase64");
  private static final Set<String> REQUEST_FIELDS =
      Set.of(
          "targetNamespace",
          "canonicalTenantId",
          "canonicalVersionId",
          "intakeRequestId",
          "publicationFence",
          "publicationRequestId",
          "requestDigest",
          "versionStateEpoch",
          "publishWorkflowId",
          "appliedCommitId",
          "contentDigest",
          "digestSchemaVersion",
          "worldAffectedTuples");
  private static final Set<String> TUPLE_FIELDS =
      Set.of("owner", "aggregateType", "aggregateId", "scopeType", "scopeId", "expectedEpoch");
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public WorldPublishedStartLocationEvidence {
    Objects.requireNonNull(request, "request");
    selectorReceiptBytes = bytes(selectorReceiptBytes, "selectorReceiptBytes");
    originalAccountBindingBytes = bytes(originalAccountBindingBytes, "originalAccountBindingBytes");
    appliedResultBytes = bytes(appliedResultBytes, "appliedResultBytes");
    requireRelation(request, selectorReceiptBytes, originalAccountBindingBytes, appliedResultBytes);
  }

  @Override
  public byte[] selectorReceiptBytes() {
    return selectorReceiptBytes.clone();
  }

  @Override
  public byte[] originalAccountBindingBytes() {
    return originalAccountBindingBytes.clone();
  }

  @Override
  public byte[] appliedResultBytes() {
    return appliedResultBytes.clone();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof WorldPublishedStartLocationEvidence that)) return false;
    return request.equals(that.request)
        && Arrays.equals(selectorReceiptBytes, that.selectorReceiptBytes)
        && Arrays.equals(originalAccountBindingBytes, that.originalAccountBindingBytes)
        && Arrays.equals(appliedResultBytes, that.appliedResultBytes);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(request);
    result = 31 * result + Arrays.hashCode(selectorReceiptBytes);
    result = 31 * result + Arrays.hashCode(originalAccountBindingBytes);
    result = 31 * result + Arrays.hashCode(appliedResultBytes);
    return result;
  }

  /** Canonical, closed durable representation; this encoding does not authenticate World. */
  public byte[] canonicalBytes() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", SCHEMA);
    value.put("request", requestJson(request));
    value.put(
        "selectorReceiptBytesBase64", Base64.getEncoder().encodeToString(selectorReceiptBytes));
    value.put(
        "originalAccountBindingBytesBase64",
        Base64.getEncoder().encodeToString(originalAccountBindingBytes));
    value.put("appliedResultBytesBase64", Base64.getEncoder().encodeToString(appliedResultBytes));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "World published selector evidence cannot be encoded", impossible);
    }
  }

  /**
   * Decodes and validates retained bytes, but does not establish authenticated producer provenance.
   */
  public static WorldPublishedStartLocationEvidence fromStored(byte[] stored) {
    Objects.requireNonNull(stored, "stored");
    try {
      JsonNode root = JSON.readTree(stored);
      requireFields(root, EVIDENCE_FIELDS, "published World selector evidence");
      if (!SCHEMA.equals(text(root, "schema"))) {
        throw new IllegalArgumentException("Unsupported published World selector evidence schema");
      }
      var evidence =
          new WorldPublishedStartLocationEvidence(
              parseRequest(root.get("request")),
              base64(root, "selectorReceiptBytesBase64"),
              base64(root, "originalAccountBindingBytesBase64"),
              base64(root, "appliedResultBytesBase64"));
      if (!Arrays.equals(stored, evidence.canonicalBytes())) {
        throw new IllegalArgumentException("Published World selector evidence is not canonical");
      }
      return evidence;
    } catch (tools.jackson.core.JacksonException invalid) {
      throw new IllegalArgumentException("Published World selector evidence is invalid", invalid);
    }
  }

  private static void requireRelation(
      Request request, byte[] receiptBytes, byte[] accountBytes, byte[] appliedBytes) {
    var account = DraftAuthorizationFenceBinding.fromStored(accountBytes);
    if (!request.canonicalTenantId().equals(account.tenantId())
        || !request.canonicalVersionId().equals(account.versionId())
        || !request.appliedCommitId().equals(account.commitId().toString())) {
      throw new IllegalArgumentException(
          "Published World selection differs from original Account binding");
    }

    DraftCommitBinding draft =
        DraftCommitBinding.fromStored(
            new String(account.gameDesignBinding(), StandardCharsets.UTF_8), account.inputDigest());
    if (!Arrays.equals(draft.canonicalBytes(), account.normalizedInput())
        || !draft.requestId().equals(account.requestId())
        || !draft.commitId().equals(account.commitId())
        || !draft.target().canonicalTenantId().equals(request.canonicalTenantId())
        || !draft.target().canonicalVersionId().equals(request.canonicalVersionId())) {
      throw new IllegalArgumentException(
          "Published World selection differs from original Draft binding");
    }
    List<OwnedAffectedTuple> boundTuples =
        draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .sorted(
                Comparator.comparing(OwnedAffectedTuple::owner)
                    .thenComparing(OwnedAffectedTuple::aggregateType)
                    .thenComparing(OwnedAffectedTuple::aggregateId)
                    .thenComparing(OwnedAffectedTuple::scopeType)
                    .thenComparing(OwnedAffectedTuple::scopeId)
                    .thenComparing(OwnedAffectedTuple::expectedEpoch))
            .toList();
    if (!request.worldAffectedTuples().equals(boundTuples)) {
      throw new IllegalArgumentException(
          "Published World selection changed the complete World affected set");
    }

    var terminalRequest =
        WorldDraftTerminalReadEvidence.Request.create(request.targetNamespace(), accountBytes);
    var terminalReadback =
        new DraftAuthorizationFenceBinding.OwnerReadback(
            Owner.WORLD,
            Outcome.COMMITTED,
            account.operationId(),
            account.commitId(),
            account.fenceId(),
            account.inputDigest(),
            accountBytes,
            appliedBytes);
    var terminalResponse =
        WorldDraftTerminalReadGrpcCodec.toResponse(
            terminalRequest, java.util.Optional.of(terminalReadback));
    WorldDraftTerminalReadGrpcCodec.fromResponse(terminalRequest, terminalResponse);

    JsonNode applied = readApplied(appliedBytes);
    if (!"APPLIED".equals(text(applied, "status"))) {
      throw new IllegalArgumentException(
          "Published World selector requires the original APPLIED result");
    }
    if (!request.intakeRequestId().equals(appliedIntakeRequestId(applied))) {
      throw new IllegalArgumentException(
          "Published World selection differs from the original APPLIED intake request");
    }
    byte[] resultReceipt = base64(applied, "startLocationReceiptBase64");
    if (!Arrays.equals(receiptBytes, resultReceipt)) {
      throw new IllegalArgumentException(
          "Published World selector differs from original APPLIED receipt");
    }
    WorldDraftStartLocationEvidence receipt =
        WorldDraftStartLocationEvidence.fromStored(receiptBytes);
    if (!receipt.targetNamespace().equals(request.targetNamespace())
        || !receipt.operationId().equals(account.operationId())
        || !receipt.requestId().equals(account.requestId())
        || !receipt.commitId().equals(account.commitId())
        || !receipt.authorizationFenceId().equals(account.fenceId())
        || !receipt.accountBindingDigest().equals(prefixedSha256(accountBytes))
        || !receipt.bindingDigest().equals(draft.digest())
        || !receipt.startLocation().tenantId().equals(request.canonicalTenantId())
        || !receipt.startLocation().versionId().equals(request.canonicalVersionId())
        || !receipt.graphDigest().equals(text(applied, "graphDigest"))) {
      throw new IllegalArgumentException(
          "Published World selector receipt differs from selected APPLIED commit");
    }
  }

  private static JsonNode readApplied(byte[] bytes) {
    try {
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
              .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
              .decode(java.nio.ByteBuffer.wrap(bytes))
              .toString();
      return JSON.readTree(json);
    } catch (IOException | tools.jackson.core.JacksonException invalid) {
      throw new IllegalArgumentException("World APPLIED result is invalid", invalid);
    }
  }

  /** Reads the immutable intake identity from the exact operation frame already codec-validated. */
  private static UUID appliedIntakeRequestId(JsonNode applied) {
    ByteBuffer operation = ByteBuffer.wrap(base64(applied, "operationBytesBase64"));
    String intakeRequestId = null;
    for (int frameIndex = 0; frameIndex <= APPLIED_INTAKE_REQUEST_ID_FRAME; frameIndex++) {
      if (operation.remaining() < Integer.BYTES) {
        throw new IllegalArgumentException("World APPLIED operation frame is truncated");
      }
      int frameLength = operation.getInt();
      if (frameLength < 0 || frameLength > operation.remaining()) {
        throw new IllegalArgumentException("World APPLIED operation frame has an invalid length");
      }
      byte[] frame = new byte[frameLength];
      operation.get(frame);
      if (frameIndex == 0
          && !"world-draft-terminal-operation/v1"
              .equals(new String(frame, StandardCharsets.UTF_8))) {
        throw new IllegalArgumentException("Unsupported World APPLIED operation frame schema");
      }
      if (frameIndex == APPLIED_INTAKE_REQUEST_ID_FRAME) {
        try {
          intakeRequestId =
              StandardCharsets.UTF_8
                  .newDecoder()
                  .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                  .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                  .decode(java.nio.ByteBuffer.wrap(frame))
                  .toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
          throw new IllegalArgumentException(
              "World APPLIED intake request frame is invalid", invalid);
        }
      }
    }
    return parseUuid(intakeRequestId, "APPLIED intakeRequestId");
  }

  private static String prefixedSha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static Request parseRequest(JsonNode node) {
    requireFields(node, REQUEST_FIELDS, "published World selector request");
    JsonNode tuples = node.get("worldAffectedTuples");
    if (tuples == null || !tuples.isArray()) {
      throw new IllegalArgumentException("World affected tuple array is required");
    }
    List<OwnedAffectedTuple> affected = new ArrayList<>();
    for (JsonNode tuple : tuples) {
      requireFields(tuple, TUPLE_FIELDS, "World affected tuple");
      affected.add(
          new OwnedAffectedTuple(
              text(tuple, "owner"),
              text(tuple, "aggregateType"),
              text(tuple, "aggregateId"),
              text(tuple, "scopeType"),
              text(tuple, "scopeId"),
              text(tuple, "expectedEpoch")));
    }
    Request decoded =
        new Request(
            text(node, "targetNamespace"),
            parseUuid(text(node, "canonicalTenantId"), "canonicalTenantId"),
            parseUuid(text(node, "canonicalVersionId"), "canonicalVersionId"),
            parseUuid(text(node, "intakeRequestId"), "intakeRequestId"),
            parseUuid(text(node, "publicationFence"), "publicationFence"),
            text(node, "publicationRequestId"),
            text(node, "requestDigest"),
            positiveLong(text(node, "versionStateEpoch"), "versionStateEpoch"),
            text(node, "publishWorkflowId"),
            text(node, "appliedCommitId"),
            text(node, "contentDigest"),
            intValue(node, "digestSchemaVersion"),
            affected);
    if (!decoded.worldAffectedTuples().equals(affected)) {
      throw new IllegalArgumentException("World affected tuples are not in canonical order");
    }
    return decoded;
  }

  private static Map<String, Object> requestJson(Request request) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("targetNamespace", request.targetNamespace());
    value.put("canonicalTenantId", request.canonicalTenantId().toString());
    value.put("canonicalVersionId", request.canonicalVersionId().toString());
    value.put("intakeRequestId", request.intakeRequestId().toString());
    value.put("publicationFence", request.publicationFence().toString());
    value.put("publicationRequestId", request.publicationRequestId());
    value.put("requestDigest", request.requestDigest());
    value.put("versionStateEpoch", Long.toString(request.versionStateEpoch()));
    value.put("publishWorkflowId", request.publishWorkflowId());
    value.put("appliedCommitId", request.appliedCommitId());
    value.put("contentDigest", request.contentDigest());
    value.put("digestSchemaVersion", request.digestSchemaVersion());
    value.put(
        "worldAffectedTuples",
        request.worldAffectedTuples().stream()
            .map(
                tuple -> {
                  Map<String, Object> item = new LinkedHashMap<>();
                  item.put("owner", tuple.owner());
                  item.put("aggregateType", tuple.aggregateType());
                  item.put("aggregateId", tuple.aggregateId());
                  item.put("scopeType", tuple.scopeType());
                  item.put("scopeId", tuple.scopeId());
                  item.put("expectedEpoch", tuple.expectedEpoch());
                  return item;
                })
            .toList());
    return value;
  }

  private static byte[] base64(JsonNode node, String field) {
    try {
      return Base64.getDecoder().decode(text(node, field));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Invalid base64 field " + field, invalid);
    }
  }

  private static byte[] bytes(byte[] value, String name) {
    if (value == null || value.length == 0) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.clone();
  }

  private static String text(JsonNode object, String field) {
    JsonNode value = object == null ? null : object.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.textValue();
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

  private static int intValue(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException(field + " must be an integer");
    }
    return value.intValue();
  }

  private static UUID parseUuid(String value, String label) {
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(label + " must be a canonical UUID", invalid);
    }
    if (!parsed.toString().equals(value) || parsed.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
    }
    return parsed;
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

  /** Exact transport-neutral immutable selection input. */
  public record Request(
      String targetNamespace,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      UUID intakeRequestId,
      UUID publicationFence,
      String publicationRequestId,
      String requestDigest,
      long versionStateEpoch,
      String publishWorkflowId,
      String appliedCommitId,
      String contentDigest,
      int digestSchemaVersion,
      List<OwnedAffectedTuple> worldAffectedTuples) {
    public static final int SCHEMA_VERSION = 1;

    public Request {
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Canonical World workload namespace is required");
      }
      requireUuid(canonicalTenantId, "canonicalTenantId");
      requireUuid(canonicalVersionId, "canonicalVersionId");
      requireUuid(intakeRequestId, "intakeRequestId");
      requireUuid(publicationFence, "publicationFence");
      requireText(publicationRequestId, "publicationRequestId");
      requireSha256(requestDigest, "requestDigest");
      if (versionStateEpoch <= 0)
        throw new IllegalArgumentException("versionStateEpoch must be positive");
      requireText(publishWorkflowId, "publishWorkflowId");
      requireText(appliedCommitId, "appliedCommitId");
      requireSha256(contentDigest, "contentDigest");
      if (digestSchemaVersion != 2 && digestSchemaVersion != 3) {
        throw new IllegalArgumentException("Unsupported World digest schema version");
      }
      Objects.requireNonNull(worldAffectedTuples, "worldAffectedTuples");
      List<OwnedAffectedTuple> ordered = new ArrayList<>(worldAffectedTuples);
      ordered.sort(
          Comparator.comparing(OwnedAffectedTuple::owner)
              .thenComparing(OwnedAffectedTuple::aggregateType)
              .thenComparing(OwnedAffectedTuple::aggregateId)
              .thenComparing(OwnedAffectedTuple::scopeType)
              .thenComparing(OwnedAffectedTuple::scopeId)
              .thenComparing(OwnedAffectedTuple::expectedEpoch));
      if (ordered.stream().distinct().count() != ordered.size()) {
        throw new IllegalArgumentException("duplicate World affected tuple");
      }
      worldAffectedTuples = List.copyOf(ordered);
    }

    private static void requireUuid(UUID value, String name) {
      if (value == null || value.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException(name + " must be a non-nil UUID");
      }
    }

    private static void requireText(String value, String name) {
      if (value == null || value.isBlank())
        throw new IllegalArgumentException(name + " is required");
    }

    private static void requireSha256(String value, String name) {
      if (value == null || !value.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException(name + " must be 64 lowercase hexadecimal digits");
      }
    }
  }

  public record OwnedAffectedTuple(
      String owner,
      String aggregateType,
      String aggregateId,
      String scopeType,
      String scopeId,
      String expectedEpoch) {
    public OwnedAffectedTuple {
      if (!"WORLD_MANAGEMENT".equals(owner)) {
        throw new IllegalArgumentException("Published selector tuples must name WORLD_MANAGEMENT");
      }
      requireTupleText(aggregateType, "aggregateType");
      requireTupleText(aggregateId, "aggregateId");
      Objects.requireNonNull(scopeType, "scopeType");
      Objects.requireNonNull(scopeId, "scopeId");
      if (scopeType.isEmpty() != scopeId.isEmpty()) {
        throw new IllegalArgumentException("scopeType and scopeId must both be present or absent");
      }
      if (expectedEpoch == null || !expectedEpoch.matches("0|[1-9][0-9]*")) {
        throw new IllegalArgumentException("expectedEpoch must be canonical non-negative decimal");
      }
    }

    private static void requireTupleText(String value, String name) {
      if (value == null || value.isBlank())
        throw new IllegalArgumentException(name + " is required");
    }
  }
}
