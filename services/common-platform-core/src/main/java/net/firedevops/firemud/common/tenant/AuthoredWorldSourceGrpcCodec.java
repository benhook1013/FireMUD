package net.firedevops.firemud.common.tenant;

import com.google.protobuf.Message;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceResponse;

/** Closed request mapping and exact readback validation for authored-world source RPCs. */
public final class AuthoredWorldSourceGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  /** Exact, non-mutating source read selector and caller-owned read identity. */
  public record ReadRequest(
      String targetNamespace,
      UUID requestId,
      UUID operationId,
      UUID canonicalTenantId,
      String worldSlug) {
    public ReadRequest {
      Objects.requireNonNull(requestId, "requestId");
      Objects.requireNonNull(operationId, "operationId");
      Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
      AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
      requireNonNil(requestId, "requestId");
      requireNonNil(operationId, "operationId");
    }
  }

  private AuthoredWorldSourceGrpcCodec() {}

  /** Encodes exactly the owner read identity and source selector. */
  public static ResolveAuthoredWorldSourceRequest toReadRequest(ReadRequest request) {
    Objects.requireNonNull(request, "request");
    return ResolveAuthoredWorldSourceRequest.newBuilder()
        .setRequestId(request.requestId().toString())
        .setOperationId(request.operationId().toString())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .setWorldSlug(request.worldSlug())
        .build();
  }

  /** Decodes the complete closed receipt and binds all echoed fields to the exact read request. */
  public static AuthoredWorldSourceEvidence fromReadResponse(
      ReadRequest request, ResolveAuthoredWorldSourceResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response);

    AuthoredWorldSourceEvidence evidence;
    try {
      UUID echoedRequestId = parseCanonicalNonNilUuid(response.getRequestId(), "requestId");
      if (!request.requestId().equals(echoedRequestId)) {
        throw new IllegalArgumentException("Authored-world source read request echo changed");
      }
      evidence =
          new AuthoredWorldSourceEvidence(
              response.getSchemaVersion(),
              response.getTargetNamespace(),
              parseCanonicalNonNilUuid(
                  response.getRegistrationRequestId(), "registrationRequestId"),
              parseCanonicalNonNilUuid(response.getOperationId(), "operationId"),
              response.getRequestDigest(),
              parseCanonicalNonNilUuid(response.getCanonicalTenantId(), "canonicalTenantId"),
              response.getTenantSlug(),
              response.getWorldSlug(),
              response.getWorldDisplayName(),
              response.getSourceGameRowId(),
              response.getSourceGameTenantKey(),
              response.getProvenanceKind(),
              response.getEvidenceDigest());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Authored-world source response is invalid", exception);
    }

    if (!request.targetNamespace().equals(evidence.targetNamespace())
        || !request.operationId().equals(evidence.operationId())
        || !request.canonicalTenantId().equals(evidence.canonicalTenantId())
        || !request.worldSlug().equals(evidence.worldSlug())) {
      throw new IllegalArgumentException(
          "Authored-world source response does not match the exact request");
    }
    return evidence;
  }

  private static void requireNoUnknownFields(Message message) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(
          "Authored-world source response contains unsupported fields");
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
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }
}
