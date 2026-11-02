package net.firedevops.firemud.common.tenant;

import com.google.protobuf.Message;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Outcome;
import net.firedevops.firemud.gamedesign.v1.OriginalTenantCreationOutcome;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveOriginalTenantCreationOutcomeRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveOriginalTenantCreationOutcomeResponse;

/** Closed request mapping and exact terminal readback validation for original creation outcomes. */
public final class GameTenantCreationOriginalOutcomeGrpcCodec {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  /** Fresh read identity paired with the complete internal original binding. */
  public record ReadRequest(UUID readRequestId, Binding binding) {
    public ReadRequest {
      requireNonNil(readRequestId, "readRequestId");
      Objects.requireNonNull(binding, "binding");
    }
  }

  private GameTenantCreationOriginalOutcomeGrpcCodec() {}

  /** Encodes exactly the independent read identity and six-field internal selector. */
  public static ResolveOriginalTenantCreationOutcomeRequest toReadRequest(ReadRequest request) {
    Objects.requireNonNull(request, "request");
    Binding binding = request.binding();
    return ResolveOriginalTenantCreationOutcomeRequest.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setReadRequestId(request.readRequestId().toString())
        .setTargetNamespace(binding.targetNamespace())
        .setCreationRequestId(binding.creationRequestId().toString())
        .setAccountAuthorizationOperationId(binding.accountAuthorizationOperationId().toString())
        .setInitiatingAccountId(binding.initiatingAccountId().toString())
        .setCreationRequestDigest(binding.creationRequestDigest())
        .setAccountAuthorizationDigest(binding.accountAuthorizationDigest())
        .build();
  }

  /** Decodes the exact request and checks that its namespace matches local receiver config. */
  public static ReadRequest fromReadRequest(
      String trustedNamespace, ResolveOriginalTenantCreationOutcomeRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request);
    if (request.getSchemaVersion() != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported original tenant outcome request schema");
    }
    try {
      UUID readRequestId = parseCanonicalNonNilUuid(request.getReadRequestId(), "readRequestId");
      Binding binding =
          new Binding(
              request.getTargetNamespace(),
              parseCanonicalNonNilUuid(request.getCreationRequestId(), "creationRequestId"),
              parseCanonicalNonNilUuid(
                  request.getAccountAuthorizationOperationId(), "accountAuthorizationOperationId"),
              parseCanonicalNonNilUuid(request.getInitiatingAccountId(), "initiatingAccountId"),
              request.getCreationRequestDigest(),
              request.getAccountAuthorizationDigest());
      if (!binding.targetNamespace().equals(trustedNamespace)) {
        throw new IllegalArgumentException(
            "Outcome request namespace differs from receiver config");
      }
      return new ReadRequest(readRequestId, binding);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException("Original tenant outcome request is invalid", malformed);
    }
  }

  /** Encodes a terminal outcome and the complete V30 receipt only for CREATED. */
  public static ResolveOriginalTenantCreationOutcomeResponse toReadResponse(
      ReadRequest request, GameTenantCreationOriginalOutcomeEvidence evidence) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(evidence, "evidence");
    requireExactBinding(request.binding(), evidence.binding());
    var builder =
        ResolveOriginalTenantCreationOutcomeResponse.newBuilder()
            .setSchemaVersion(evidence.schemaVersion())
            .setReadRequestId(request.readRequestId().toString())
            .setTargetNamespace(evidence.binding().targetNamespace())
            .setCreationRequestId(evidence.binding().creationRequestId().toString())
            .setAccountAuthorizationOperationId(
                evidence.binding().accountAuthorizationOperationId().toString())
            .setInitiatingAccountId(evidence.binding().initiatingAccountId().toString())
            .setCreationRequestDigest(evidence.binding().creationRequestDigest())
            .setAccountAuthorizationDigest(evidence.binding().accountAuthorizationDigest())
            .setOutcome(toProtoOutcome(evidence.outcome()))
            .setEvidenceDigest(evidence.evidenceDigest());
    evidence.receipt().ifPresent(receipt -> builder.setCreatedReceipt(toFreshReceipt(receipt)));
    return builder.build();
  }

  /** Decodes only a closed terminal response bound to this exact read request. */
  public static GameTenantCreationOriginalOutcomeEvidence fromReadResponse(
      ReadRequest request, ResolveOriginalTenantCreationOutcomeResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response);
    if (response.getSchemaVersion() != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported original tenant outcome response schema");
    }
    UUID echoedReadRequestId =
        parseCanonicalNonNilUuid(response.getReadRequestId(), "readRequestId");
    if (!request.readRequestId().equals(echoedReadRequestId)) {
      throw new IllegalArgumentException("Original tenant outcome read identity changed");
    }
    Binding echoedBinding =
        new Binding(
            response.getTargetNamespace(),
            parseCanonicalNonNilUuid(response.getCreationRequestId(), "creationRequestId"),
            parseCanonicalNonNilUuid(
                response.getAccountAuthorizationOperationId(), "accountAuthorizationOperationId"),
            parseCanonicalNonNilUuid(response.getInitiatingAccountId(), "initiatingAccountId"),
            response.getCreationRequestDigest(),
            response.getAccountAuthorizationDigest());
    requireExactBinding(request.binding(), echoedBinding);

    Outcome outcome = fromProtoOutcome(response.getOutcome());
    Optional<FreshTenantCreationEvidence> receipt =
        response.hasCreatedReceipt()
            ? Optional.of(fromFreshReceipt(response.getCreatedReceipt()))
            : Optional.empty();
    GameTenantCreationOriginalOutcomeEvidence evidence =
        new GameTenantCreationOriginalOutcomeEvidence(
            response.getSchemaVersion(), echoedBinding, outcome, receipt);
    if (!GameTenantCreationDigest.isDigest(response.getEvidenceDigest())
        || !evidence.evidenceDigest().equals(response.getEvidenceDigest())) {
      throw new IllegalArgumentException("Original tenant outcome evidence digest is invalid");
    }
    return evidence;
  }

  private static ResolveFreshTenantCreationResponse toFreshReceipt(
      FreshTenantCreationEvidence receipt) {
    return ResolveFreshTenantCreationResponse.newBuilder()
        .setSchemaVersion(receipt.schemaVersion())
        .setTargetNamespace(receipt.targetNamespace())
        .setCreationRequestId(receipt.creationRequestId().toString())
        .setOperationId(receipt.operationId().toString())
        .setRequestDigest(receipt.requestDigest())
        .setCanonicalTenantId(receipt.canonicalTenantId().toString())
        .setSourceGameRowId(receipt.sourceGameRowId())
        .setSourceGameTenantKey(receipt.sourceGameTenantKey())
        .setProvenanceKind(receipt.provenanceKind())
        .setEvidenceDigest(receipt.evidenceDigest())
        .build();
  }

  private static FreshTenantCreationEvidence fromFreshReceipt(
      ResolveFreshTenantCreationResponse receipt) {
    requireNoUnknownFields(receipt);
    return new FreshTenantCreationEvidence(
        receipt.getSchemaVersion(),
        receipt.getTargetNamespace(),
        parseCanonicalNonNilUuid(receipt.getCreationRequestId(), "receipt.creationRequestId"),
        parseCanonicalNonNilUuid(receipt.getOperationId(), "receipt.operationId"),
        receipt.getRequestDigest(),
        parseCanonicalNonNilUuid(receipt.getCanonicalTenantId(), "receipt.canonicalTenantId"),
        receipt.getSourceGameRowId(),
        receipt.getSourceGameTenantKey(),
        receipt.getProvenanceKind(),
        receipt.getEvidenceDigest());
  }

  private static OriginalTenantCreationOutcome toProtoOutcome(Outcome outcome) {
    return switch (outcome) {
      case CREATED -> OriginalTenantCreationOutcome.ORIGINAL_TENANT_CREATION_OUTCOME_CREATED;
      case NOT_CREATED ->
          OriginalTenantCreationOutcome.ORIGINAL_TENANT_CREATION_OUTCOME_NOT_CREATED;
    };
  }

  private static Outcome fromProtoOutcome(OriginalTenantCreationOutcome outcome) {
    return switch (outcome) {
      case ORIGINAL_TENANT_CREATION_OUTCOME_CREATED -> Outcome.CREATED;
      case ORIGINAL_TENANT_CREATION_OUTCOME_NOT_CREATED -> Outcome.NOT_CREATED;
      case ORIGINAL_TENANT_CREATION_OUTCOME_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("Original tenant outcome must be terminal");
    };
  }

  private static void requireExactBinding(Binding expected, Binding actual) {
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException("Original tenant outcome differs from exact binding");
    }
  }

  private static void requireNoUnknownFields(Message message) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Original tenant outcome contains unsupported fields");
    }
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required", malformed);
    }
    requireNonNil(parsed, label);
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
    }
    return parsed;
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }
}
