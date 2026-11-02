package net.firedevops.firemud.common.tenant;

import com.google.protobuf.Message;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ResolveCurrentTenantCreationAuthorizationRequest;
import net.firedevops.firemud.account.v1.ResolveCurrentTenantCreationAuthorizationResponse;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;

/** Closed Account current-authorization request and response mapping. */
public final class CurrentTenantCreationAuthorizationGrpcCodec {
  public static final int MAX_WIRE_BYTES = 2048;
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final long MAX_EPOCH_MILLIS = 253402300799999L;

  private CurrentTenantCreationAuthorizationGrpcCodec() {}

  public record Request(UUID readRequestId, Binding binding) {
    public Request {
      requireNonNil(readRequestId, "readRequestId");
      Objects.requireNonNull(binding, "binding");
    }
  }

  /** Shape-only Account result; data returned by this codec is not an authorization capability. */
  public record Authorization(Binding binding, long validBeforeEpochMillis) {
    public Authorization {
      Objects.requireNonNull(binding, "binding");
      requireValidBefore(validBeforeEpochMillis);
    }
  }

  public static ResolveCurrentTenantCreationAuthorizationRequest request(
      UUID readRequestId, Binding binding) {
    Request request = new Request(readRequestId, binding);
    return toWire(request);
  }

  public static Request decodeRequest(ResolveCurrentTenantCreationAuthorizationRequest wire) {
    Objects.requireNonNull(wire, "wire");
    requireKnownAndWithinBudget(wire);
    if (wire.getSchemaVersion() != SCHEMA_VERSION) {
      throw invalid("Unsupported current tenant-creation authorization request schema");
    }
    try {
      Request request =
          new Request(
              parseCanonicalNonNilUuid(wire.getReadRequestId(), "readRequestId"),
              binding(
                  wire.getTargetNamespace(),
                  wire.getCreationRequestId(),
                  wire.getAccountAuthorizationOperationId(),
                  wire.getInitiatingAccountId(),
                  wire.getCreationRequestDigest(),
                  wire.getAccountAuthorizationDigest()));
      if (!toWire(request).equals(wire)) {
        throw invalid("Current tenant-creation authorization request is not canonical");
      }
      return request;
    } catch (IllegalArgumentException malformed) {
      throw invalid("Current tenant-creation authorization request is invalid", malformed);
    }
  }

  public static ResolveCurrentTenantCreationAuthorizationResponse response(
      UUID readRequestId, Binding binding, long validBeforeEpochMillis) {
    Request request = new Request(readRequestId, binding);
    requireValidBefore(validBeforeEpochMillis);
    var wire =
        ResolveCurrentTenantCreationAuthorizationResponse.newBuilder()
            .setSchemaVersion(SCHEMA_VERSION)
            .setReadRequestId(request.readRequestId().toString())
            .setTargetNamespace(binding.targetNamespace())
            .setCreationRequestId(binding.creationRequestId().toString())
            .setAccountAuthorizationOperationId(
                binding.accountAuthorizationOperationId().toString())
            .setInitiatingAccountId(binding.initiatingAccountId().toString())
            .setCreationRequestDigest(binding.creationRequestDigest())
            .setAccountAuthorizationDigest(binding.accountAuthorizationDigest())
            .setValidBeforeEpochMillis(validBeforeEpochMillis)
            .build();
    requireWithinBudget(wire);
    return wire;
  }

  public static Authorization verifyResponse(
      UUID readRequestId,
      Binding expected,
      ResolveCurrentTenantCreationAuthorizationResponse wire) {
    Request request = new Request(readRequestId, expected);
    Objects.requireNonNull(wire, "wire");
    requireKnownAndWithinBudget(wire);
    if (wire.getSchemaVersion() != SCHEMA_VERSION) {
      throw invalid("Unsupported current tenant-creation authorization response schema");
    }
    try {
      UUID echoedReadId = parseCanonicalNonNilUuid(wire.getReadRequestId(), "readRequestId");
      Binding echoedBinding =
          binding(
              wire.getTargetNamespace(),
              wire.getCreationRequestId(),
              wire.getAccountAuthorizationOperationId(),
              wire.getInitiatingAccountId(),
              wire.getCreationRequestDigest(),
              wire.getAccountAuthorizationDigest());
      long validBefore = wire.getValidBeforeEpochMillis();
      requireValidBefore(validBefore);
      if (!request.readRequestId().equals(echoedReadId) || !expected.equals(echoedBinding)) {
        throw invalid("Current tenant-creation authorization response changed its exact request");
      }
      if (!response(echoedReadId, echoedBinding, validBefore).equals(wire)) {
        throw invalid("Current tenant-creation authorization response is not canonical");
      }
      return new Authorization(echoedBinding, validBefore);
    } catch (IllegalArgumentException malformed) {
      throw invalid("Current tenant-creation authorization response is invalid", malformed);
    }
  }

  private static ResolveCurrentTenantCreationAuthorizationRequest toWire(Request request) {
    Binding binding = request.binding();
    var wire =
        ResolveCurrentTenantCreationAuthorizationRequest.newBuilder()
            .setSchemaVersion(SCHEMA_VERSION)
            .setReadRequestId(request.readRequestId().toString())
            .setTargetNamespace(binding.targetNamespace())
            .setCreationRequestId(binding.creationRequestId().toString())
            .setAccountAuthorizationOperationId(
                binding.accountAuthorizationOperationId().toString())
            .setInitiatingAccountId(binding.initiatingAccountId().toString())
            .setCreationRequestDigest(binding.creationRequestDigest())
            .setAccountAuthorizationDigest(binding.accountAuthorizationDigest())
            .build();
    requireWithinBudget(wire);
    return wire;
  }

  private static Binding binding(
      String targetNamespace,
      String creationRequestId,
      String authorizationOperationId,
      String initiatingAccountId,
      String creationDigest,
      String authorizationDigest) {
    return new Binding(
        targetNamespace,
        parseCanonicalNonNilUuid(creationRequestId, "creationRequestId"),
        parseCanonicalNonNilUuid(authorizationOperationId, "accountAuthorizationOperationId"),
        parseCanonicalNonNilUuid(initiatingAccountId, "initiatingAccountId"),
        creationDigest,
        authorizationDigest);
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (RuntimeException malformed) {
      throw invalid("Canonical non-nil " + label + " is required", malformed);
    }
    if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
      throw invalid("Canonical non-nil " + label + " is required");
    }
    return parsed;
  }

  private static void requireValidBefore(long value) {
    if (value <= 0 || value > MAX_EPOCH_MILLIS) {
      throw invalid("validBeforeEpochMillis must be a supported positive UTC epoch time");
    }
  }

  private static void requireKnownAndWithinBudget(Message wire) {
    if (wire.getSerializedSize() > MAX_WIRE_BYTES) {
      throw invalid("Current tenant-creation authorization wire message exceeds its byte bound");
    }
    if (!wire.getUnknownFields().asMap().isEmpty()) {
      throw invalid("Unknown current tenant-creation authorization fields");
    }
  }

  private static void requireWithinBudget(Message wire) {
    if (wire.getSerializedSize() > MAX_WIRE_BYTES) {
      throw invalid("Current tenant-creation authorization wire message exceeds its byte bound");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw invalid(label + " must not be nil");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(message, cause);
  }
}
