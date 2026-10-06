package net.firedevops.firemud.common.authoring;

import com.google.protobuf.Message;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldDraftTerminalReadStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Closed request mapping and exact echo/outcome validation for authenticated World readback. */
public final class WorldDraftTerminalReadGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final tools.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private WorldDraftTerminalReadGrpcCodec() {}

  public static ReadWorldDraftTerminalOutcomeRequest toRequest(
      WorldDraftTerminalReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    return ReadWorldDraftTerminalOutcomeRequest.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setOriginalAccountBinding(
            com.google.protobuf.ByteString.copyFrom(request.originalAccountBinding()))
        .build();
  }

  /** Decodes only after the receiver has authenticated its exact same-namespace Account peer. */
  public static WorldDraftTerminalReadEvidence.Request fromRequest(
      ReadWorldDraftTerminalOutcomeRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "ReadWorldDraftTerminalOutcomeRequest");
    try {
      return new WorldDraftTerminalReadEvidence.Request(
          request.getSchemaVersion(),
          request.getTargetNamespace(),
          parseCanonicalNonNilUuid(request.getReadRequestId(), "readRequestId"),
          request.getOriginalAccountBinding().toByteArray());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("World terminal read request is invalid", exception);
    }
  }

  /** Emits only UNKNOWN or exact canonical World committed/definitive-abort readback. */
  public static ReadWorldDraftTerminalOutcomeResponse toResponse(
      WorldDraftTerminalReadEvidence.Request request,
      Optional<DraftAuthorizationFenceBinding.OwnerReadback> ownerReadback) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(ownerReadback, "ownerReadback");
    var builder =
        ReadWorldDraftTerminalOutcomeResponse.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setReadRequestId(request.readRequestId().toString())
            .setOriginalAccountBinding(
                com.google.protobuf.ByteString.copyFrom(request.originalAccountBinding()));
    if (ownerReadback.isEmpty()) {
      return builder
          .setStatus(WorldDraftTerminalReadStatus.WORLD_DRAFT_TERMINAL_READ_STATUS_UNKNOWN)
          .build();
    }
    var readback = ownerReadback.orElseThrow();
    requireExactOwnerReadback(request, readback);
    return builder
        .setStatus(
            readback.outcome() == Outcome.COMMITTED
                ? WorldDraftTerminalReadStatus.WORLD_DRAFT_TERMINAL_READ_STATUS_COMMITTED
                : WorldDraftTerminalReadStatus
                    .WORLD_DRAFT_TERMINAL_READ_STATUS_DEFINITIVELY_ABORTED)
        .setOwnerReadbackBytes(com.google.protobuf.ByteString.copyFrom(readback.canonicalBytes()))
        .build();
  }

  /** Validates the complete echo and independently framed World owner result. */
  public static WorldDraftTerminalReadEvidence fromResponse(
      WorldDraftTerminalReadEvidence.Request request,
      ReadWorldDraftTerminalOutcomeResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ReadWorldDraftTerminalOutcomeResponse");
    if (response.getSchemaVersion() != request.schemaVersion()
        || !response.getTargetNamespace().equals(request.targetNamespace())
        || !response.getReadRequestId().equals(request.readRequestId().toString())
        || !Arrays.equals(
            response.getOriginalAccountBinding().toByteArray(), request.originalAccountBinding())) {
      throw new IllegalArgumentException("World terminal response changed the exact read request");
    }
    Optional<DraftAuthorizationFenceBinding.OwnerReadback> readback;
    switch (response.getStatus()) {
      case WORLD_DRAFT_TERMINAL_READ_STATUS_UNKNOWN -> {
        if (!response.getOwnerReadbackBytes().isEmpty()) {
          throw new IllegalArgumentException("UNKNOWN World readback cannot carry a result");
        }
        readback = Optional.empty();
      }
      case WORLD_DRAFT_TERMINAL_READ_STATUS_DEFINITIVELY_ABORTED,
          WORLD_DRAFT_TERMINAL_READ_STATUS_COMMITTED -> {
        if (response.getOwnerReadbackBytes().isEmpty()) {
          throw new IllegalArgumentException("World terminal readback bytes are required");
        }
        var decoded =
            DraftAuthorizationFenceBinding.OwnerReadback.fromStored(
                response.getOwnerReadbackBytes().toByteArray());
        requireExactOwnerReadback(request, decoded);
        Outcome expected =
            response.getStatus()
                    == WorldDraftTerminalReadStatus.WORLD_DRAFT_TERMINAL_READ_STATUS_COMMITTED
                ? Outcome.COMMITTED
                : Outcome.DEFINITIVELY_ABORTED;
        if (decoded.outcome() != expected) {
          throw new IllegalArgumentException(
              "World terminal status differs from canonical owner outcome");
        }
        readback = Optional.of(decoded);
      }
      case WORLD_DRAFT_TERMINAL_READ_STATUS_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("Unsupported World terminal read status");
      default -> throw new IllegalArgumentException("Unsupported World terminal read status");
    }
    return new WorldDraftTerminalReadEvidence(request, readback);
  }

  private static void requireExactOwnerReadback(
      WorldDraftTerminalReadEvidence.Request request,
      DraftAuthorizationFenceBinding.OwnerReadback readback) {
    DraftAuthorizationFenceBinding binding = request.accountBinding();
    if (readback.owner() != Owner.WORLD
        || (readback.outcome() != Outcome.DEFINITIVELY_ABORTED
            && readback.outcome() != Outcome.COMMITTED)) {
      throw new IllegalArgumentException(
          "World terminal response is not an exact World terminal outcome");
    }
    readback.requireBinding(binding);
    if (!readback.operationId().equals(binding.operationId())
        || !readback.commitId().equals(binding.commitId())
        || !readback.fenceId().equals(binding.fenceId())
        || !readback.inputDigest().equals(binding.inputDigest())
        || !Arrays.equals(readback.fullBinding(), request.originalAccountBinding())
        || readback.result().length == 0) {
      throw new IllegalArgumentException(
          "World terminal response differs from the complete original Account binding");
    }
    if (readback.outcome() == Outcome.COMMITTED) requireCommittedResult(request, readback);
  }

  /**
   * Checks this World carrier only; mutation/source semantics remain at the authenticated owner.
   */
  static void requireCommittedResult(
      WorldDraftTerminalReadEvidence.Request request,
      DraftAuthorizationFenceBinding.OwnerReadback readback) {
    try {
      byte[] bytes = readback.result();
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
              .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      if (!Arrays.equals(bytes, Rfc8785CanonicalJson.canonicalizeUtf8(json)))
        throw new IllegalArgumentException("Noncanonical World APPLIED result");
      JsonNode result = JSON.readTree(bytes);
      fields(
          result,
          Set.of(
              "schema",
              "status",
              "operationBytesBase64",
              "graphBytesBase64",
              "graphDigest",
              "appliedEpochs"));
      exactText(result, "schema", "world-draft-graph-applied/v1");
      exactText(result, "status", "APPLIED");
      var account = request.accountBinding();
      var draft =
          DraftCommitBinding.fromStored(
              new String(account.gameDesignBinding(), StandardCharsets.UTF_8),
              account.inputDigest());
      if (!Arrays.equals(draft.canonicalBytes(), account.normalizedInput()))
        throw new IllegalArgumentException("World applied input differs from original binding");
      FrameReader operation = new FrameReader(base64(result, "operationBytesBase64"));
      operation.expect("world-draft-terminal-operation/v1");
      for (UUID id :
          java.util.List.of(
              account.operationId(),
              account.requestId(),
              account.commitId(),
              account.fenceId(),
              account.tenantId(),
              account.versionId())) operation.expect(id.toString());
      operation.expectBytes(draft.canonicalBytes());
      operation.expect(request.targetNamespace());
      operation.expect(account.tenantId().toString());
      operation.expect(account.versionId().toString());
      operation.canonicalUuid(); // Version identity operation
      operation.expect(Long.toString(draft.target().gameDesignVersionRowId()));
      operation.canonicalUuid(); // Intake request
      operation.canonicalUuid(); // Intake operation
      operation.digest(); // Intake request digest
      operation.canonicalUuid(); // Source operation
      operation.digest(); // Source evidence digest
      operation.digest(); // Intake receipt digest
      operation.expect(sha256(request.originalAccountBinding()));
      operation.expectBytes(request.originalAccountBinding());
      operation.requireEnd();
      byte[] graph = base64(result, "graphBytesBase64");
      exactText(result, "graphDigest", sha256(graph));
      JsonNode graphValue = JSON.readTree(graph);
      fields(
          graphValue, Set.of("schemaVersion", "canonicalTenantId", "canonicalVersionId", "rows"));
      exactText(graphValue, "schemaVersion", "2");
      exactText(graphValue, "canonicalTenantId", account.tenantId().toString());
      exactText(graphValue, "canonicalVersionId", account.versionId().toString());
      if (!graphValue.get("rows").isArray() || graphValue.get("rows").isEmpty())
        throw new IllegalArgumentException("World applied graph is absent");
      var expected = draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT);
      JsonNode epochs = result.get("appliedEpochs");
      if (!epochs.isArray() || expected.isEmpty() || epochs.size() != expected.size())
        throw new IllegalArgumentException("World applied epoch vector is incomplete");
      for (int i = 0; i < expected.size(); i++) {
        var unit = expected.get(i);
        JsonNode epoch = epochs.get(i);
        fields(
            epoch,
            Set.of(
                "aggregateType",
                "aggregateId",
                "scopeType",
                "scopeId",
                "expectedEpoch",
                "resultingEpoch"));
        exactText(epoch, "aggregateType", unit.aggregateType());
        exactText(epoch, "aggregateId", unit.aggregateId());
        exactText(epoch, "scopeType", unit.scopeType());
        exactText(epoch, "scopeId", unit.scopeId());
        exactText(epoch, "expectedEpoch", unit.expectedEpoch());
        exactText(
            epoch,
            "resultingEpoch",
            new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString());
      }
    } catch (java.io.IOException
        | java.security.NoSuchAlgorithmException
        | RuntimeException invalid) {
      throw new IllegalArgumentException(
          "World committed readback has an invalid or substituted canonical APPLIED result",
          invalid);
    }
  }

  private static void fields(JsonNode value, Set<String> fields) {
    if (value == null
        || !value.isObject()
        || value.size() != fields.size()
        || value.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey())))
      throw new IllegalArgumentException("World APPLIED carrier fields differ");
  }

  private static void exactText(JsonNode value, String field, String expected) {
    if (value.get(field) == null
        || !value.get(field).isTextual()
        || !expected.equals(value.get(field).textValue()))
      throw new IllegalArgumentException("World APPLIED carrier differs at " + field);
  }

  private static byte[] base64(JsonNode value, String field) {
    if (value.get(field) == null || !value.get(field).isTextual())
      throw new IllegalArgumentException("World APPLIED bytes missing");
    String encoded = value.get(field).textValue();
    byte[] decoded = Base64.getDecoder().decode(encoded);
    if (decoded.length == 0 || !Base64.getEncoder().encodeToString(decoded).equals(encoded))
      throw new IllegalArgumentException("Noncanonical World APPLIED Base64");
    return decoded;
  }

  private static String sha256(byte[] value) throws java.security.NoSuchAlgorithmException {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static final class FrameReader {
    private final ByteBuffer bytes;

    private FrameReader(byte[] value) {
      bytes = ByteBuffer.wrap(value);
    }

    private byte[] frame() {
      if (bytes.remaining() < Integer.BYTES)
        throw new IllegalArgumentException("Truncated World operation");
      int size = bytes.getInt();
      if (size <= 0 || size > bytes.remaining())
        throw new IllegalArgumentException("Invalid World operation frame");
      byte[] value = new byte[size];
      bytes.get(value);
      return value;
    }

    private String text() {
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(frame()))
            .toString();
      } catch (java.nio.charset.CharacterCodingException invalid) {
        throw new IllegalArgumentException("Invalid World operation UTF-8", invalid);
      }
    }

    private void expect(String expected) {
      if (!expected.equals(text()))
        throw new IllegalArgumentException("Substituted World operation");
    }

    private void expectBytes(byte[] expected) {
      if (!Arrays.equals(expected, frame()))
        throw new IllegalArgumentException("Substituted World operation bytes");
    }

    private void canonicalUuid() {
      parseCanonicalNonNilUuid(text(), "World owner identity");
    }

    private void digest() {
      if (!text().matches("(?:sha256:)?[0-9a-f]{64}"))
        throw new IllegalArgumentException("Invalid World owner digest");
    }

    private void requireEnd() {
      if (bytes.hasRemaining())
        throw new IllegalArgumentException("Trailing World operation bytes");
    }
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
    }
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
      }
      return parsed;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required", exception);
    }
  }
}
