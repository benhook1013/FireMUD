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
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Closed, local World-owned initial placement operation; it does not authenticate its evidence. */
public final class WorldCanonicalInitialPlayerLocation {
  public static final String REQUEST_SCHEMA = "world-canonical-initial-player-location-request/v1";
  public static final String RESULT_SCHEMA = "world-canonical-initial-player-location-result/v1";

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Set<String> RESULT_FIELDS =
      Set.of(
          "schema",
          "operationId",
          "requestDigest",
          "requestBytesBase64",
          "outcome",
          "conflictCode",
          "startLocation",
          "runtimeRoomInstanceId");
  private static final Set<String> REQUEST_FIELDS =
      Set.of(
          "schema",
          "operationId",
          "canonicalTenantId",
          "realmId",
          "worldSlug",
          "canonicalGameInstanceId",
          "playableStateNamespaceId",
          "playableStateScope",
          "canonicalAccountId",
          "characterId",
          "entityAssignmentOperationId",
          "entityAssignmentDigest",
          "initialAdmissionHoldId",
          "initialAdmissionHoldFence",
          "initialAdmissionRequestId",
          "initialAdmissionRequestDigest",
          "catalogRevision",
          "initialAdmissionOrigin",
          "initialAdmissionOwnerProofId",
          "initialAdmissionOwnerProofDigest",
          "pointerAuditId",
          "pointerVersion",
          "activeLifecycleEvidence");
  private static final Set<String> ROOM_FIELDS = Set.of("tenantId", "versionId", "roomTemplateId");
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private WorldCanonicalInitialPlayerLocation() {}

  /** The current World hold schema only proves a first bind from a never-opened catalog. */
  public enum InitialAdmissionOrigin {
    NO_PRIOR_POINTER,
    EXPECT_CLOSED
  }

  public enum Outcome {
    APPLIED,
    CONFLICT
  }

  /**
   * Caller-stable operation input. The supplied lifecycle proof is re-read and byte-compared
   * against current World owner state in the same writable transaction as the placement write.
   * Entity and Game Session producer authentication remains an external, default-denied gate.
   */
  public record Request(
      UUID operationId,
      UUID canonicalTenantId,
      UUID realmId,
      String worldSlug,
      UUID canonicalGameInstanceId,
      UUID playableStateNamespaceId,
      String playableStateScope,
      UUID canonicalAccountId,
      UUID characterId,
      UUID entityAssignmentOperationId,
      String entityAssignmentDigest,
      UUID initialAdmissionHoldId,
      UUID initialAdmissionHoldFence,
      String initialAdmissionRequestId,
      String initialAdmissionRequestDigest,
      long catalogRevision,
      String initialAdmissionOwnerProofId,
      String initialAdmissionOwnerProofDigest,
      String pointerAuditId,
      long pointerVersion,
      InitialAdmissionOrigin initialAdmissionOrigin,
      WorldCanonicalInstanceLifecycleEvidence activeLifecycleEvidence) {
    public Request {
      requireNonNil(operationId, "operationId");
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      requireNonNil(realmId, "realmId");
      requireSlug(worldSlug);
      requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
      requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
      if (!"SHARED".equals(playableStateScope) && !"ISOLATED".equals(playableStateScope)) {
        throw invalid("playableStateScope must be SHARED or ISOLATED");
      }
      requireNonNil(canonicalAccountId, "canonicalAccountId");
      requireNonNil(characterId, "characterId");
      requireNonNil(entityAssignmentOperationId, "entityAssignmentOperationId");
      requireDigest(entityAssignmentDigest, "entityAssignmentDigest");
      requireNonNil(initialAdmissionHoldId, "initialAdmissionHoldId");
      requireNonNil(initialAdmissionHoldFence, "initialAdmissionHoldFence");
      requireText(initialAdmissionRequestId, 128, "initialAdmissionRequestId");
      requireDigest(initialAdmissionRequestDigest, "initialAdmissionRequestDigest");
      requirePositive(catalogRevision, "catalogRevision");
      requireText(initialAdmissionOwnerProofId, 128, "initialAdmissionOwnerProofId");
      requireDigest(initialAdmissionOwnerProofDigest, "initialAdmissionOwnerProofDigest");
      requireText(pointerAuditId, 128, "pointerAuditId");
      requirePositive(pointerVersion, "pointerVersion");
      Objects.requireNonNull(initialAdmissionOrigin, "initialAdmissionOrigin");
      Objects.requireNonNull(activeLifecycleEvidence, "activeLifecycleEvidence");
      if (!"ACTIVE".equals(activeLifecycleEvidence.lifecycleStatus())) {
        throw invalid("initial placement requires exact current ACTIVE World lifecycle evidence");
      }
      var lifecycle = activeLifecycleEvidence.request();
      if (!canonicalTenantId.equals(lifecycle.canonicalTenantId())
          || !worldSlug.equals(lifecycle.worldSlug())
          || !canonicalGameInstanceId.equals(lifecycle.canonicalGameInstanceId())
          || !playableStateNamespaceId.equals(lifecycle.playableStateNamespaceId())
          || !playableStateScope.equals(lifecycle.playableStateScope())) {
        throw invalid("initial placement scope differs from its complete World lifecycle proof");
      }
    }

    public byte[] canonicalRequestBytes() {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("schema", REQUEST_SCHEMA);
      value.put("operationId", operationId.toString());
      value.put("canonicalTenantId", canonicalTenantId.toString());
      value.put("realmId", realmId.toString());
      value.put("worldSlug", worldSlug);
      value.put("canonicalGameInstanceId", canonicalGameInstanceId.toString());
      value.put("playableStateNamespaceId", playableStateNamespaceId.toString());
      value.put("playableStateScope", playableStateScope);
      value.put("canonicalAccountId", canonicalAccountId.toString());
      value.put("characterId", characterId.toString());
      value.put("entityAssignmentOperationId", entityAssignmentOperationId.toString());
      value.put("entityAssignmentDigest", entityAssignmentDigest);
      value.put("initialAdmissionHoldId", initialAdmissionHoldId.toString());
      value.put("initialAdmissionHoldFence", initialAdmissionHoldFence.toString());
      value.put("initialAdmissionRequestId", initialAdmissionRequestId);
      value.put("initialAdmissionRequestDigest", initialAdmissionRequestDigest);
      value.put("catalogRevision", Long.toString(catalogRevision));
      value.put("initialAdmissionOrigin", initialAdmissionOrigin.name());
      value.put("initialAdmissionOwnerProofId", initialAdmissionOwnerProofId);
      value.put("initialAdmissionOwnerProofDigest", initialAdmissionOwnerProofDigest);
      value.put("pointerAuditId", pointerAuditId);
      value.put("pointerVersion", Long.toString(pointerVersion));
      try {
        value.put("activeLifecycleEvidence", JSON.readTree(normalizedLifecycleEvidenceBytes()));
        return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      } catch (IOException impossible) {
        throw new IllegalStateException(
            "World initial-location request cannot be encoded", impossible);
      }
    }

    public String requestDigest() {
      return prefixedDigest(canonicalRequestBytes());
    }

    /** Decodes a canonical stored request while retaining its complete original lifecycle proof. */
    public static Request fromStored(
        byte[] completeCanonicalRequestBytes, byte[] originalCompleteLifecycleEvidenceBytes) {
      Objects.requireNonNull(completeCanonicalRequestBytes, "completeCanonicalRequestBytes");
      Objects.requireNonNull(
          originalCompleteLifecycleEvidenceBytes, "originalCompleteLifecycleEvidenceBytes");
      try {
        JsonNode root = JSON.readTree(strictUtf8(completeCanonicalRequestBytes));
        requireFields(root, REQUEST_FIELDS, "initial-location request");
        if (!REQUEST_SCHEMA.equals(text(root, "schema"))) {
          throw invalid("unsupported initial-location request schema");
        }
        WorldCanonicalInstanceLifecycleEvidence lifecycleEvidence =
            WorldCanonicalInstanceLifecycleEvidence.fromStored(
                originalCompleteLifecycleEvidenceBytes);
        Request request =
            new Request(
                canonicalUuid(root, "operationId"),
                canonicalUuid(root, "canonicalTenantId"),
                canonicalUuid(root, "realmId"),
                text(root, "worldSlug"),
                canonicalUuid(root, "canonicalGameInstanceId"),
                canonicalUuid(root, "playableStateNamespaceId"),
                text(root, "playableStateScope"),
                canonicalUuid(root, "canonicalAccountId"),
                canonicalUuid(root, "characterId"),
                canonicalUuid(root, "entityAssignmentOperationId"),
                text(root, "entityAssignmentDigest"),
                canonicalUuid(root, "initialAdmissionHoldId"),
                canonicalUuid(root, "initialAdmissionHoldFence"),
                text(root, "initialAdmissionRequestId"),
                text(root, "initialAdmissionRequestDigest"),
                positiveLong(text(root, "catalogRevision"), "catalogRevision"),
                text(root, "initialAdmissionOwnerProofId"),
                text(root, "initialAdmissionOwnerProofDigest"),
                text(root, "pointerAuditId"),
                positiveLong(text(root, "pointerVersion"), "pointerVersion"),
                InitialAdmissionOrigin.valueOf(text(root, "initialAdmissionOrigin")),
                lifecycleEvidence);
        if (!Arrays.equals(completeCanonicalRequestBytes, request.canonicalRequestBytes())) {
          throw invalid(
              "retained initial-location request is not canonical or differs from evidence");
        }
        return request;
      } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) {
        if (invalid instanceof IllegalArgumentException argument
            && argument.getMessage() != null
            && argument.getMessage().startsWith("INVALID_ARGUMENT:")) {
          throw argument;
        }
        throw invalid("retained initial-location request is invalid", invalid);
      }
    }

    /** Full source/lifecycle evidence with only the per-read correlation UUID removed. */
    public byte[] originalLifecycleEvidenceBytes() {
      return activeLifecycleEvidence.canonicalBytes();
    }

    /** Canonical identity form used to compare fresh reads of the same owner evidence. */
    public byte[] normalizedLifecycleEvidenceBytes() {
      return WorldCanonicalInitialPlayerLocation.normalizedLifecycleEvidenceBytes(
          activeLifecycleEvidence.canonicalBytes());
    }
  }

  /** Immutable result carrier; stored canonical result bytes are preserved on exact replay. */
  public static final class Result {
    private final Request request;
    private final Outcome outcome;
    private final String conflictCode;
    private final RoomTemplateRef startLocation;
    private final Long runtimeRoomInstanceId;
    private final byte[] canonicalBytes;

    private Result(
        Request request,
        Outcome outcome,
        String conflictCode,
        RoomTemplateRef startLocation,
        Long runtimeRoomInstanceId,
        byte[] canonicalBytes) {
      this.request = Objects.requireNonNull(request, "request");
      this.outcome = Objects.requireNonNull(outcome, "outcome");
      this.conflictCode = conflictCode;
      this.startLocation = startLocation;
      this.runtimeRoomInstanceId = runtimeRoomInstanceId;
      this.canonicalBytes = Arrays.copyOf(canonicalBytes, canonicalBytes.length);
    }

    public static Result applied(
        Request request, RoomTemplateRef room, long runtimeRoomInstanceId) {
      return create(request, Outcome.APPLIED, null, room, runtimeRoomInstanceId);
    }

    public static Result conflict(Request request, String conflictCode) {
      requireConflictCode(conflictCode);
      return create(request, Outcome.CONFLICT, conflictCode, null, null);
    }

    public static Result fromStored(Request request, byte[] storedBytes) {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(storedBytes, "storedBytes");
      try {
        JsonNode root =
            JSON.readTree(new String(storedBytes, java.nio.charset.StandardCharsets.UTF_8));
        requireFields(root, RESULT_FIELDS, "initial-location result");
        if (!RESULT_SCHEMA.equals(text(root, "schema"))
            || !request.operationId().toString().equals(text(root, "operationId"))
            || !request.requestDigest().equals(text(root, "requestDigest"))
            || !Base64.getEncoder()
                .encodeToString(request.canonicalRequestBytes())
                .equals(text(root, "requestBytesBase64"))) {
          throw invalid("retained initial-location result differs from its exact request");
        }
        Outcome outcome = Outcome.valueOf(text(root, "outcome"));
        JsonNode conflictNode = root.get("conflictCode");
        String conflict =
            conflictNode == null || conflictNode.isNull() ? null : conflictNode.textValue();
        JsonNode roomNode = root.get("startLocation");
        Long runtimeRoom =
            root.hasNonNull("runtimeRoomInstanceId")
                ? positiveLong(text(root, "runtimeRoomInstanceId"), "runtimeRoomInstanceId")
                : null;
        RoomTemplateRef room = roomNode == null || roomNode.isNull() ? null : parseRoom(roomNode);
        if (outcome == Outcome.APPLIED
            ? conflict != null || room == null || runtimeRoom == null
            : conflict == null || room != null || runtimeRoom != null) {
          throw invalid("retained initial-location result has an invalid outcome shape");
        }
        Result result = create(request, outcome, conflict, room, runtimeRoom);
        if (!Arrays.equals(storedBytes, result.canonicalBytes())) {
          throw invalid("retained initial-location result is not canonical");
        }
        return result;
      } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) {
        if (invalid instanceof IllegalArgumentException argument
            && argument.getMessage() != null
            && argument.getMessage().startsWith("INVALID_ARGUMENT:")) {
          throw argument;
        }
        throw invalid("retained initial-location result is invalid", invalid);
      }
    }

    public Request request() {
      return request;
    }

    public String requestDigest() {
      return request.requestDigest();
    }

    public Outcome outcome() {
      return outcome;
    }

    public String conflictCode() {
      return conflictCode;
    }

    public RoomTemplateRef startLocation() {
      return startLocation;
    }

    public Long runtimeRoomInstanceId() {
      return runtimeRoomInstanceId;
    }

    public byte[] canonicalBytes() {
      return Arrays.copyOf(canonicalBytes, canonicalBytes.length);
    }

    private static Result create(
        Request request,
        Outcome outcome,
        String conflictCode,
        RoomTemplateRef room,
        Long runtimeRoomInstanceId) {
      Objects.requireNonNull(request, "request");
      if (outcome == Outcome.APPLIED) {
        if (conflictCode != null
            || room == null
            || runtimeRoomInstanceId == null
            || runtimeRoomInstanceId <= 0) {
          throw invalid(
              "APPLIED initial-location result requires a typed room and positive runtime room id");
        }
        if (!room.equals(request.activeLifecycleEvidence().startLocation())
            || runtimeRoomInstanceId.longValue()
                != request.activeLifecycleEvidence().runtimeRoomInstanceId()) {
          throw invalid("initial-location result differs from the request's exact V2 ROOM mapping");
        }
      } else {
        requireConflictCode(conflictCode);
        if (room != null || runtimeRoomInstanceId != null) {
          throw invalid("CONFLICT initial-location result cannot carry a room mapping");
        }
      }
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("schema", RESULT_SCHEMA);
      value.put("operationId", request.operationId().toString());
      value.put("requestDigest", request.requestDigest());
      value.put(
          "requestBytesBase64",
          Base64.getEncoder().encodeToString(request.canonicalRequestBytes()));
      value.put("outcome", outcome.name());
      value.put("conflictCode", conflictCode);
      value.put("startLocation", room == null ? null : roomJson(room));
      value.put(
          "runtimeRoomInstanceId",
          runtimeRoomInstanceId == null ? null : runtimeRoomInstanceId.toString());
      try {
        byte[] bytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
        return new Result(request, outcome, conflictCode, room, runtimeRoomInstanceId, bytes);
      } catch (IOException impossible) {
        throw new IllegalStateException(
            "World initial-location result cannot be encoded", impossible);
      }
    }
  }

  private static void requireConflictCode(String value) {
    requireText(value, 64, "conflictCode");
    if (!value.matches("[A-Z][A-Z0-9_]{0,63}")) {
      throw invalid("conflictCode must be an uppercase ASCII machine code");
    }
  }

  private static Map<String, Object> roomJson(RoomTemplateRef room) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("tenantId", room.tenantId().toString());
    value.put("versionId", room.versionId().toString());
    value.put("roomTemplateId", room.roomTemplateId().toString());
    return value;
  }

  /** Removes only the lifecycle read correlation UUID while preserving the complete proof. */
  public static byte[] normalizedLifecycleEvidenceBytes(byte[] evidenceBytes) {
    Objects.requireNonNull(evidenceBytes, "evidenceBytes");
    try {
      JsonNode evidence = JSON.readTree(evidenceBytes);
      JsonNode requestNode = evidence == null ? null : evidence.get("request");
      if (evidence == null
          || !evidence.isObject()
          || !(requestNode instanceof ObjectNode request)) {
        throw invalid("World lifecycle evidence must contain its complete request object");
      }
      JsonNode readRequestId = request.get("readRequestId");
      if (readRequestId == null || !readRequestId.isTextual()) {
        throw invalid("World lifecycle evidence must contain its read correlation UUID");
      }
      request.remove("readRequestId");
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(evidence));
    } catch (IOException invalid) {
      throw invalid("World lifecycle evidence cannot be normalized", invalid);
    }
  }

  private static RoomTemplateRef parseRoom(JsonNode node) {
    requireFields(node, ROOM_FIELDS, "startLocation");
    return new RoomTemplateRef(
        UUID.fromString(text(node, "tenantId")),
        UUID.fromString(text(node, "versionId")),
        UUID.fromString(text(node, "roomTemplateId")));
  }

  private static void requireFields(JsonNode node, Set<String> fields, String label) {
    if (node == null || !node.isObject() || node.size() != fields.size()) {
      throw invalid(label + " must be a closed object");
    }
    node.propertyNames()
        .forEach(
            field -> {
              if (!fields.contains(field)) throw invalid(label + " contains an unknown field");
            });
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) throw invalid(field + " must be text");
    return value.textValue();
  }

  private static UUID canonicalUuid(JsonNode node, String field) {
    String value = text(node, field);
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) throw new IllegalArgumentException();
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw invalid(field + " must be a canonical UUID", invalid);
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
      throw invalid("stored initial-location request is not valid UTF-8", invalid);
    }
  }

  private static long positiveLong(String value, String label) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0 || !Long.toString(parsed).equals(value)) throw new NumberFormatException();
      return parsed;
    } catch (NumberFormatException invalid) {
      throw invalid(label + " must be a canonical positive signed-64-bit integer", invalid);
    }
  }

  private static String prefixedDigest(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) throw invalid(label + " must be a non-nil UUID");
  }

  private static void requireSlug(String value) {
    requireText(value, 120, "worldSlug");
    if (!value.matches("[a-z0-9]+(-[a-z0-9]+)*")) throw invalid("worldSlug is not canonical");
  }

  private static void requireText(String value, int max, String label) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || value.chars().anyMatch(Character::isISOControl))
      throw invalid(label + " is required and bounded");
  }

  private static void requireDigest(String value, String label) {
    if (value == null || !SHA256.matcher(value).matches())
      throw invalid(label + " must be lowercase SHA-256");
  }

  private static void requirePositive(long value, String label) {
    if (value <= 0) throw invalid(label + " must be positive");
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("INVALID_ARGUMENT: " + message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException("INVALID_ARGUMENT: " + message, cause);
  }
}
