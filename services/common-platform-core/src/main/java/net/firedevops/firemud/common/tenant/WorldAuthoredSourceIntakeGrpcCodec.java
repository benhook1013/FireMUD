package net.firedevops.firemud.common.tenant;

import com.google.protobuf.Message;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.worldmanagement.v1.IntakeAuthoredWorldSourceRequest;
import net.firedevops.firemud.worldmanagement.v1.IntakeAuthoredWorldSourceResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeResponse;

/** Closed request mapping and exact echo validation for the World authored-source intake RPCs. */
public final class WorldAuthoredSourceIntakeGrpcCodec {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String REQUEST_DIGEST_DOMAIN = "world-authored-source-intake-request/v1";

  /** Complete canonical binding accepted by intake and echoed by both World RPCs. */
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
