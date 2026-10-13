package net.firedevops.firemud.common.tenant;

import com.google.protobuf.Message;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.worldmanagement.v1.IntakeAuthoredWorldSourceRequest;
import net.firedevops.firemud.worldmanagement.v1.IntakeAuthoredWorldSourceResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeByIdRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeByIdResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeResponse;

/** Closed request mapping and exact echo validation for the World authored-source intake RPCs. */
public final class WorldAuthoredSourceIntakeGrpcCodec {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String REQUEST_DIGEST_DOMAIN = "world-authored-source-intake-request/v1";

  /** Complete canonical binding accepted by intake and echoed by both complete-selector RPCs. */
  public record IntakeRequest(
      int schemaVersion,
      String targetNamespace,
      UUID intakeRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String expectedSourceEvidenceDigest) {
    public IntakeRequest {
      if (schemaVersion != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported World authored-source intake version");
      }
      requireNonNil(intakeRequestId, "intakeRequestId");
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      requireNonNil(sourceOperationId, "sourceOperationId");
      AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
      requireDigest(expectedSourceEvidenceDigest, "expectedSourceEvidenceDigest");
    }
  }

  /** A committed World receipt read using a distinct request identity and the full intake tuple. */
  public record ReadRequest(IntakeRequest binding, UUID requestId) {
    public ReadRequest {
      Objects.requireNonNull(binding, "binding");
      requireNonNil(requestId, "requestId");
      if (requestId.equals(binding.intakeRequestId())) {
        throw new IllegalArgumentException("Read requestId must differ from intakeRequestId");
      }
    }
  }

  /** Public receipt fields. receiptDigest is opaque because it also binds World's private key. */
  public record CommittedReceipt(
      int schemaVersion,
      String targetNamespace,
      UUID intakeRequestId,
      UUID operationId,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      String requestDigest,
      String receiptDigest) {
    public CommittedReceipt {
      IntakeRequest binding =
          new IntakeRequest(
              schemaVersion,
              targetNamespace,
              intakeRequestId,
              canonicalTenantId,
              worldSlug,
              sourceOperationId,
              sourceEvidenceDigest);
      requireNonNil(operationId, "operationId");
      requireDigest(requestDigest, "requestDigest");
      requireDigest(receiptDigest, "receiptDigest");
      if (!WorldAuthoredSourceIntakeGrpcCodec.requestDigest(binding).equals(requestDigest)) {
        throw new IllegalArgumentException("World intake requestDigest does not match its binding");
      }
    }
  }

  /** Closed read selector that does not require caller-supplied source fields. */
  public record ByIdReadRequest(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      UUID intakeRequestId,
      UUID canonicalTenantId) {
    public ByIdReadRequest {
      if (schemaVersion != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported World authored-source intake version");
      }
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Canonical targetNamespace is required");
      }
      requireNonNil(readRequestId, "readRequestId");
      requireNonNil(intakeRequestId, "intakeRequestId");
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      if (readRequestId.equals(intakeRequestId)) {
        throw new IllegalArgumentException("Read requestId must differ from intakeRequestId");
      }
    }
  }

  /** Full public portion of a retained World intake receipt and its immutable source evidence. */
  public record PublicReceipt(
      int schemaVersion,
      String targetNamespace,
      UUID intakeRequestId,
      UUID operationId,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      String requestDigest,
      String receiptDigest,
      AuthoredWorldSourceEvidence source) {
    public PublicReceipt {
      if (schemaVersion != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported World authored-source intake version");
      }
      requireNonNil(intakeRequestId, "intakeRequestId");
      requireNonNil(operationId, "operationId");
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      requireNonNil(sourceOperationId, "sourceOperationId");
      AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
      requireDigest(sourceEvidenceDigest, "sourceEvidenceDigest");
      requireDigest(requestDigest, "requestDigest");
      requireDigest(receiptDigest, "receiptDigest");
      Objects.requireNonNull(source, "source");
      if (!"NEW_GAME_ROW".equals(source.provenanceKind())
          || !targetNamespace.equals(source.targetNamespace())
          || !canonicalTenantId.equals(source.canonicalTenantId())
          || !worldSlug.equals(source.worldSlug())
          || !sourceOperationId.equals(source.operationId())
          || !sourceEvidenceDigest.equals(source.evidenceDigest())) {
        throw new IllegalArgumentException(
            "World intake receipt differs from its complete fresh source evidence");
      }
      IntakeRequest binding =
          new IntakeRequest(
              schemaVersion,
              targetNamespace,
              intakeRequestId,
              canonicalTenantId,
              worldSlug,
              sourceOperationId,
              sourceEvidenceDigest);
      if (!WorldAuthoredSourceIntakeGrpcCodec.requestDigest(binding).equals(requestDigest)) {
        throw new IllegalArgumentException("World intake requestDigest does not match its source");
      }
    }
  }

  private WorldAuthoredSourceIntakeGrpcCodec() {}

  /** Encodes exactly the validated original intake binding. */
  public static IntakeAuthoredWorldSourceRequest toIntakeRequest(IntakeRequest request) {
    Objects.requireNonNull(request, "request");
    return IntakeAuthoredWorldSourceRequest.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setIntakeRequestId(request.intakeRequestId().toString())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .setWorldSlug(request.worldSlug())
        .setSourceOperationId(request.sourceOperationId().toString())
        .setExpectedSourceEvidenceDigest(request.expectedSourceEvidenceDigest())
        .build();
  }

  /** Encodes the full intake binding and the caller-owned read request identity. */
  public static ReadAuthoredWorldSourceIntakeRequest toReadRequest(ReadRequest request) {
    Objects.requireNonNull(request, "request");
    IntakeRequest binding = request.binding();
    return ReadAuthoredWorldSourceIntakeRequest.newBuilder()
        .setSchemaVersion(binding.schemaVersion())
        .setTargetNamespace(binding.targetNamespace())
        .setRequestId(request.requestId().toString())
        .setIntakeRequestId(binding.intakeRequestId().toString())
        .setCanonicalTenantId(binding.canonicalTenantId().toString())
        .setWorldSlug(binding.worldSlug())
        .setSourceOperationId(binding.sourceOperationId().toString())
        .setExpectedSourceEvidenceDigest(binding.expectedSourceEvidenceDigest())
        .build();
  }

  /** Decodes the complete closed intake request before any owner access. */
  public static IntakeRequest fromIntakeRequest(IntakeAuthoredWorldSourceRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "IntakeAuthoredWorldSource request");
    return new IntakeRequest(
        request.getSchemaVersion(),
        request.getTargetNamespace(),
        parseCanonicalNonNilUuid(request.getIntakeRequestId(), "intakeRequestId"),
        parseCanonicalNonNilUuid(request.getCanonicalTenantId(), "canonicalTenantId"),
        request.getWorldSlug(),
        parseCanonicalNonNilUuid(request.getSourceOperationId(), "sourceOperationId"),
        request.getExpectedSourceEvidenceDigest());
  }

  /** Decodes a closed exact-read request; requestId belongs only to the read itself. */
  public static ReadRequest fromReadRequest(ReadAuthoredWorldSourceIntakeRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "ReadAuthoredWorldSourceIntake request");
    IntakeRequest binding =
        new IntakeRequest(
            request.getSchemaVersion(),
            request.getTargetNamespace(),
            parseCanonicalNonNilUuid(request.getIntakeRequestId(), "intakeRequestId"),
            parseCanonicalNonNilUuid(request.getCanonicalTenantId(), "canonicalTenantId"),
            request.getWorldSlug(),
            parseCanonicalNonNilUuid(request.getSourceOperationId(), "sourceOperationId"),
            request.getExpectedSourceEvidenceDigest());
    return new ReadRequest(binding, parseCanonicalNonNilUuid(request.getRequestId(), "requestId"));
  }

  /** Encodes only a fully validated committed receipt and its exact original request binding. */
  public static IntakeAuthoredWorldSourceResponse toIntakeResponse(
      IntakeRequest request, CommittedReceipt receipt) {
    requireExactBinding(request, receipt);
    return IntakeAuthoredWorldSourceResponse.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setIntakeRequestId(request.intakeRequestId().toString())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .setWorldSlug(request.worldSlug())
        .setSourceOperationId(request.sourceOperationId().toString())
        .setExpectedSourceEvidenceDigest(request.expectedSourceEvidenceDigest())
        .setOperationId(receipt.operationId().toString())
        .setRequestDigest(receipt.requestDigest())
        .setReceiptDigest(receipt.receiptDigest())
        .build();
  }

  /** Encodes a fully validated committed receipt and echoes the distinct read identity. */
  public static ReadAuthoredWorldSourceIntakeResponse toReadResponse(
      ReadRequest request, CommittedReceipt receipt) {
    Objects.requireNonNull(request, "request");
    requireExactBinding(request.binding(), receipt);
    return ReadAuthoredWorldSourceIntakeResponse.newBuilder()
        .setSchemaVersion(request.binding().schemaVersion())
        .setTargetNamespace(request.binding().targetNamespace())
        .setRequestId(request.requestId().toString())
        .setIntakeRequestId(request.binding().intakeRequestId().toString())
        .setCanonicalTenantId(request.binding().canonicalTenantId().toString())
        .setWorldSlug(request.binding().worldSlug())
        .setSourceOperationId(request.binding().sourceOperationId().toString())
        .setExpectedSourceEvidenceDigest(request.binding().expectedSourceEvidenceDigest())
        .setOperationId(receipt.operationId().toString())
        .setRequestDigest(receipt.requestDigest())
        .setReceiptDigest(receipt.receiptDigest())
        .build();
  }

  /** Encodes a closed exact source-receipt read selected only by retained intake identity. */
  public static ReadAuthoredWorldSourceIntakeByIdRequest toReadByIdRequest(
      ByIdReadRequest request) {
    Objects.requireNonNull(request, "request");
    return ReadAuthoredWorldSourceIntakeByIdRequest.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setIntakeRequestId(request.intakeRequestId().toString())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .build();
  }

  /** Decodes the complete closed ID-based read before any owner access. */
  public static ByIdReadRequest fromReadByIdRequest(
      ReadAuthoredWorldSourceIntakeByIdRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "ReadAuthoredWorldSourceIntakeById request");
    return new ByIdReadRequest(
        request.getSchemaVersion(),
        request.getTargetNamespace(),
        parseCanonicalNonNilUuid(request.getReadRequestId(), "readRequestId"),
        parseCanonicalNonNilUuid(request.getIntakeRequestId(), "intakeRequestId"),
        parseCanonicalNonNilUuid(request.getCanonicalTenantId(), "canonicalTenantId"));
  }

  /**
   * Encodes the exact read echo and complete public receipt; World-private keys are not emitted.
   */
  public static ReadAuthoredWorldSourceIntakeByIdResponse toReadByIdResponse(
      ByIdReadRequest request, PublicReceipt receipt) {
    requireReceiptForByIdRequest(request, receipt);
    return ReadAuthoredWorldSourceIntakeByIdResponse.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setIntakeRequestId(request.intakeRequestId().toString())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .setReceipt(toProtoReceipt(receipt))
        .build();
  }

  /** Validates exact echo, receipt identity and every source-evidence digest from the owner. */
  public static PublicReceipt fromReadByIdResponse(
      ByIdReadRequest request, ReadAuthoredWorldSourceIntakeByIdResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ReadAuthoredWorldSourceIntakeById response");
    ByIdReadRequest echoed =
        new ByIdReadRequest(
            response.getSchemaVersion(),
            response.getTargetNamespace(),
            parseCanonicalNonNilUuid(response.getReadRequestId(), "readRequestId"),
            parseCanonicalNonNilUuid(response.getIntakeRequestId(), "intakeRequestId"),
            parseCanonicalNonNilUuid(response.getCanonicalTenantId(), "canonicalTenantId"));
    if (!request.equals(echoed)) {
      throw new IllegalArgumentException(
          "World authored-source intake by-ID response does not echo the exact request");
    }
    if (!response.hasReceipt()) {
      throw new IllegalArgumentException("World authored-source intake by-ID receipt is required");
    }
    PublicReceipt receipt = fromProtoReceipt(response.getReceipt());
    requireReceiptForByIdRequest(request, receipt);
    return receipt;
  }

  private static void requireReceiptForByIdRequest(ByIdReadRequest request, PublicReceipt receipt) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(receipt, "receipt");
    if (request.schemaVersion() != receipt.schemaVersion()
        || !request.targetNamespace().equals(receipt.targetNamespace())
        || !request.intakeRequestId().equals(receipt.intakeRequestId())
        || !request.canonicalTenantId().equals(receipt.canonicalTenantId())) {
      throw new IllegalArgumentException(
          "World authored-source intake receipt differs from the exact by-ID request");
    }
  }

  private static net.firedevops.firemud.worldmanagement.v1.AuthoredWorldSourceIntakePublicReceipt
      toProtoReceipt(PublicReceipt receipt) {
    var source = receipt.source();
    var sourceBuilder =
        net.firedevops.firemud.worldmanagement.v1.AuthoredWorldSourceEvidence.newBuilder()
            .setSchemaVersion(source.schemaVersion())
            .setTargetNamespace(source.targetNamespace())
            .setRegistrationRequestId(source.registrationRequestId().toString())
            .setOperationId(source.operationId().toString())
            .setRequestDigest(source.requestDigest())
            .setCanonicalTenantId(source.canonicalTenantId().toString())
            .setTenantSlug(source.tenantSlug())
            .setWorldSlug(source.worldSlug())
            .setWorldDisplayName(source.worldDisplayName())
            .setSourceGameRowId(source.sourceGameRowId())
            .setSourceGameTenantKey(source.sourceGameTenantKey())
            .setProvenanceKind(source.provenanceKind())
            .setEvidenceDigest(source.evidenceDigest());
    return net.firedevops.firemud.worldmanagement.v1.AuthoredWorldSourceIntakePublicReceipt
        .newBuilder()
        .setSchemaVersion(receipt.schemaVersion())
        .setTargetNamespace(receipt.targetNamespace())
        .setIntakeRequestId(receipt.intakeRequestId().toString())
        .setOperationId(receipt.operationId().toString())
        .setCanonicalTenantId(receipt.canonicalTenantId().toString())
        .setWorldSlug(receipt.worldSlug())
        .setSourceOperationId(receipt.sourceOperationId().toString())
        .setSourceEvidenceDigest(receipt.sourceEvidenceDigest())
        .setRequestDigest(receipt.requestDigest())
        .setReceiptDigest(receipt.receiptDigest())
        .setSource(sourceBuilder)
        .build();
  }

  private static PublicReceipt fromProtoReceipt(
      net.firedevops.firemud.worldmanagement.v1.AuthoredWorldSourceIntakePublicReceipt receipt) {
    Objects.requireNonNull(receipt, "receipt");
    requireNoUnknownFields(receipt, "AuthoredWorldSourceIntakePublicReceipt");
    if (!receipt.hasSource()) {
      throw new IllegalArgumentException("Complete authored-world source evidence is required");
    }
    var source = receipt.getSource();
    requireNoUnknownFields(source, "AuthoredWorldSourceEvidence");
    AuthoredWorldSourceEvidence evidence =
        new AuthoredWorldSourceEvidence(
            source.getSchemaVersion(),
            source.getTargetNamespace(),
            parseCanonicalNonNilUuid(source.getRegistrationRequestId(), "registrationRequestId"),
            parseCanonicalNonNilUuid(source.getOperationId(), "sourceOperationId"),
            source.getRequestDigest(),
            parseCanonicalNonNilUuid(source.getCanonicalTenantId(), "canonicalTenantId"),
            source.getTenantSlug(),
            source.getWorldSlug(),
            source.getWorldDisplayName(),
            source.getSourceGameRowId(),
            source.getSourceGameTenantKey(),
            source.getProvenanceKind(),
            source.getEvidenceDigest());
    return new PublicReceipt(
        receipt.getSchemaVersion(),
        receipt.getTargetNamespace(),
        parseCanonicalNonNilUuid(receipt.getIntakeRequestId(), "intakeRequestId"),
        parseCanonicalNonNilUuid(receipt.getOperationId(), "operationId"),
        parseCanonicalNonNilUuid(receipt.getCanonicalTenantId(), "canonicalTenantId"),
        receipt.getWorldSlug(),
        parseCanonicalNonNilUuid(receipt.getSourceOperationId(), "sourceOperationId"),
        receipt.getSourceEvidenceDigest(),
        receipt.getRequestDigest(),
        receipt.getReceiptDigest(),
        evidence);
  }

  /** Validates all echoed request fields and the public, owner-authenticated receipt fields. */
  public static CommittedReceipt fromIntakeResponse(
      IntakeRequest request, IntakeAuthoredWorldSourceResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "IntakeAuthoredWorldSource response");
    IntakeRequest echoed =
        new IntakeRequest(
            response.getSchemaVersion(),
            response.getTargetNamespace(),
            parseCanonicalNonNilUuid(response.getIntakeRequestId(), "intakeRequestId"),
            parseCanonicalNonNilUuid(response.getCanonicalTenantId(), "canonicalTenantId"),
            response.getWorldSlug(),
            parseCanonicalNonNilUuid(response.getSourceOperationId(), "sourceOperationId"),
            response.getExpectedSourceEvidenceDigest());
    if (!request.equals(echoed)) {
      throw new IllegalArgumentException("World intake response does not echo the exact request");
    }
    return new CommittedReceipt(
        echoed.schemaVersion(),
        echoed.targetNamespace(),
        echoed.intakeRequestId(),
        parseCanonicalNonNilUuid(response.getOperationId(), "operationId"),
        echoed.canonicalTenantId(),
        echoed.worldSlug(),
        echoed.sourceOperationId(),
        echoed.expectedSourceEvidenceDigest(),
        response.getRequestDigest(),
        response.getReceiptDigest());
  }

  /** Validates the separate read identity, full original binding, and committed receipt. */
  public static CommittedReceipt fromReadResponse(
      ReadRequest request, ReadAuthoredWorldSourceIntakeResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ReadAuthoredWorldSourceIntake response");
    IntakeRequest echoed =
        new IntakeRequest(
            response.getSchemaVersion(),
            response.getTargetNamespace(),
            parseCanonicalNonNilUuid(response.getIntakeRequestId(), "intakeRequestId"),
            parseCanonicalNonNilUuid(response.getCanonicalTenantId(), "canonicalTenantId"),
            response.getWorldSlug(),
            parseCanonicalNonNilUuid(response.getSourceOperationId(), "sourceOperationId"),
            response.getExpectedSourceEvidenceDigest());
    UUID echoedRequestId = parseCanonicalNonNilUuid(response.getRequestId(), "requestId");
    if (!request.binding().equals(echoed) || !request.requestId().equals(echoedRequestId)) {
      throw new IllegalArgumentException(
          "World intake read response does not echo the exact request");
    }
    return new CommittedReceipt(
        echoed.schemaVersion(),
        echoed.targetNamespace(),
        echoed.intakeRequestId(),
        parseCanonicalNonNilUuid(response.getOperationId(), "operationId"),
        echoed.canonicalTenantId(),
        echoed.worldSlug(),
        echoed.sourceOperationId(),
        echoed.expectedSourceEvidenceDigest(),
        response.getRequestDigest(),
        response.getReceiptDigest());
  }

  /** Recomputable request digest defined by the World intake contract. */
  public static String requestDigest(IntakeRequest request) {
    Objects.requireNonNull(request, "request");
    return GameTenantCreationDigest.digest(
        REQUEST_DIGEST_DOMAIN,
        request.targetNamespace(),
        request.intakeRequestId().toString(),
        request.canonicalTenantId().toString(),
        request.worldSlug(),
        request.sourceOperationId().toString(),
        request.expectedSourceEvidenceDigest());
  }

  private static void requireExactBinding(IntakeRequest request, CommittedReceipt receipt) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(receipt, "receipt");
    if (receipt.schemaVersion() != request.schemaVersion()
        || !receipt.targetNamespace().equals(request.targetNamespace())
        || !receipt.intakeRequestId().equals(request.intakeRequestId())
        || !receipt.canonicalTenantId().equals(request.canonicalTenantId())
        || !receipt.worldSlug().equals(request.worldSlug())
        || !receipt.sourceOperationId().equals(request.sourceOperationId())
        || !receipt.sourceEvidenceDigest().equals(request.expectedSourceEvidenceDigest())
        || !receipt.requestDigest().equals(requestDigest(request))) {
      throw new IllegalArgumentException(
          "World committed receipt does not match the exact request");
    }
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required", exception);
    }
    requireNonNil(parsed, label);
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required");
    }
    return parsed;
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private static void requireDigest(String value, String label) {
    if (!GameTenantCreationDigest.isDigest(value)) {
      throw new IllegalArgumentException(label + " must be canonical SHA-256 text");
    }
  }
}
