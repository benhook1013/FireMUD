package net.firedevops.firemud.common.tenant;

import com.google.protobuf.Message;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.v1.ReadFreshTenantCreationReservationRequest;
import net.firedevops.firemud.gamedesign.v1.ReadFreshTenantCreationReservationResponse;

/** Closed request mapping and exact readback validation for fresh tenant reservations. */
public final class FreshTenantCreationReservationGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public record ReadRequest(
      String targetNamespace,
      UUID readRequestId,
      UUID creationRequestId,
      String expectedRequestDigest,
      UUID expectedOperationId,
      UUID expectedCanonicalTenantId) {
    public ReadRequest {
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Target namespace must be one canonical DNS label");
      }
      requireNonNil(readRequestId, "readRequestId");
      requireNonNil(creationRequestId, "creationRequestId");
      requireNonNil(expectedOperationId, "expectedOperationId");
      requireNonNil(expectedCanonicalTenantId, "expectedCanonicalTenantId");
      if (readRequestId.equals(creationRequestId)) {
        throw new IllegalArgumentException("Read and creation request identities must be distinct");
      }
      if (!GameTenantCreationDigest.isDigest(expectedRequestDigest)) {
        throw new IllegalArgumentException("Canonical expected request digest is required");
      }
    }
  }

  private FreshTenantCreationReservationGrpcCodec() {}

  public static ReadFreshTenantCreationReservationRequest toReadRequest(ReadRequest request) {
    Objects.requireNonNull(request, "request");
    return ReadFreshTenantCreationReservationRequest.newBuilder()
        .setSchemaVersion(1)
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setCreationRequestId(request.creationRequestId().toString())
        .setExpectedRequestDigest(request.expectedRequestDigest())
        .setExpectedCreationOperationId(request.expectedOperationId().toString())
        .setExpectedCanonicalTenantId(request.expectedCanonicalTenantId().toString())
        .build();
  }

  public static FreshTenantCreationReservationEvidence fromReadResponse(
      ReadRequest request, ReadFreshTenantCreationReservationResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response);
    if (!response.hasSchemaVersion()
        || !response.hasTargetNamespace()
        || !response.hasReadRequestId()
        || !response.hasCreationRequestId()
        || !response.hasRequestDigest()
        || !response.hasCreationOperationId()
        || !response.hasCanonicalTenantId()
        || !response.hasSourceGameTenantKey()
        || !response.hasName()
        || !response.hasEvidenceDigest()) {
      throw new IllegalArgumentException("Reservation response omits a required field");
    }

    FreshTenantCreationReservationEvidence evidence;
    try {
      UUID readRequestId = parseCanonicalNonNilUuid(response.getReadRequestId(), "readRequestId");
      if (!request.readRequestId().equals(readRequestId)) {
        throw new IllegalArgumentException("Reservation read identity echo changed");
      }
      evidence =
          new FreshTenantCreationReservationEvidence(
              response.getSchemaVersion(),
              response.getTargetNamespace(),
              parseCanonicalNonNilUuid(response.getCreationRequestId(), "creationRequestId"),
              response.getRequestDigest(),
              parseCanonicalNonNilUuid(response.getCreationOperationId(), "creationOperationId"),
              parseCanonicalNonNilUuid(response.getCanonicalTenantId(), "canonicalTenantId"),
              response.getSourceGameTenantKey(),
              response.getName(),
              response.hasDescription() ? response.getDescription() : null,
              response.getEvidenceDigest());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Game Design reservation response is invalid", exception);
    }

    if (!request.targetNamespace().equals(evidence.targetNamespace())
        || !request.creationRequestId().equals(evidence.creationRequestId())
        || !request.expectedRequestDigest().equals(evidence.requestDigest())
        || !request.expectedOperationId().equals(evidence.operationId())
        || !request.expectedCanonicalTenantId().equals(evidence.canonicalTenantId())) {
      throw new IllegalArgumentException(
          "Game Design reservation response does not match the exact request");
    }
    return evidence;
  }

  private static void requireNoUnknownFields(Message message) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Reservation response contains unsupported fields");
    }
  }

  public static UUID parseCanonicalNonNilUuid(String value, String label) {
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
      throw new IllegalArgumentException(label + " must not be nil");
    }
  }
}
