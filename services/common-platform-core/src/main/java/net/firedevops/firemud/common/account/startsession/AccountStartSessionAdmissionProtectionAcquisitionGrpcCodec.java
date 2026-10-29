package net.firedevops.firemud.common.account.startsession;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AcquireStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.account.v1.AcquireStartSessionAdmissionProtectionResponse;

/** Closed, purpose-bounded transport for the complete existing Account protection values. */
public final class AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec {
  public static final int SCHEMA_VERSION = 1;
  public static final int MAX_REQUEST_CANONICAL_BYTES =
      AccountStartSessionAdmissionProtectionRequest.MAX_CANONICAL_BYTES;
  public static final int MAX_EVIDENCE_CANONICAL_BYTES =
      AccountStartSessionAdmissionProtectionEvidence.MAX_CANONICAL_BYTES;
  // Three packed schema-version bytes and at most five bytes for the bytes tag/length prefix.
  public static final int MAX_ENVELOPE_OVERHEAD_BYTES = 8;
  public static final int MAX_REQUEST_WIRE_BYTES =
      MAX_REQUEST_CANONICAL_BYTES + MAX_ENVELOPE_OVERHEAD_BYTES;
  public static final int MAX_RESPONSE_WIRE_BYTES =
      MAX_EVIDENCE_CANONICAL_BYTES + MAX_ENVELOPE_OVERHEAD_BYTES;

  private AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec() {}

  public static AcquireStartSessionAdmissionProtectionRequest toRequest(
      AccountStartSessionAdmissionProtectionRequest request) {
    Objects.requireNonNull(request, "Complete Account admission protection request required");
    byte[] canonical = request.canonicalBytes();
    requirePayloadBudget(canonical, MAX_REQUEST_CANONICAL_BYTES, "Account protection request");
    AccountStartSessionAdmissionProtectionRequest.decode(canonical);
    var wire =
        AcquireStartSessionAdmissionProtectionRequest.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addRequestCanonicalBytes(ByteString.copyFrom(canonical))
            .build();
    requireBudget(wire, MAX_REQUEST_WIRE_BYTES);
    return wire;
  }

  public static AccountStartSessionAdmissionProtectionRequest fromRequest(
      AcquireStartSessionAdmissionProtectionRequest wire) {
    if (wire == null) throw invalid("Request is missing");
    requireClosed(wire);
    requireBudget(wire, MAX_REQUEST_WIRE_BYTES);
    if (wire.getSchemaVersionCount() != 1
        || wire.getRequestCanonicalBytesCount() != 1
        || wire.getSchemaVersion(0) != SCHEMA_VERSION) {
      throw invalid("Unsupported schema or duplicate request field");
    }
    byte[] canonical = wire.getRequestCanonicalBytes(0).toByteArray();
    requirePayloadBudget(canonical, MAX_REQUEST_CANONICAL_BYTES, "Account protection request");
    AccountStartSessionAdmissionProtectionRequest request =
        AccountStartSessionAdmissionProtectionRequest.decode(canonical);
    if (!toRequest(request).equals(wire)) {
      throw invalid("Request is not its exact closed encoding");
    }
    return request;
  }

  public static AcquireStartSessionAdmissionProtectionResponse toResponse(
      AccountStartSessionAdmissionProtectionRequest request,
      AccountStartSessionAdmissionProtectionEvidence evidence) {
    Objects.requireNonNull(request, "Complete Account admission protection request required");
    Objects.requireNonNull(evidence, "Account admission protection evidence required");
    requireSameRequest(request, evidence);
    byte[] canonical = evidence.canonicalBytes();
    requirePayloadBudget(canonical, MAX_EVIDENCE_CANONICAL_BYTES, "Account protection evidence");
    AccountStartSessionAdmissionProtectionEvidence.decode(canonical);
    var wire =
        AcquireStartSessionAdmissionProtectionResponse.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addEvidenceCanonicalBytes(ByteString.copyFrom(canonical))
            .build();
    requireBudget(wire, MAX_RESPONSE_WIRE_BYTES);
    return wire;
  }

  public static AccountStartSessionAdmissionProtectionEvidence fromResponse(
      AccountStartSessionAdmissionProtectionRequest request,
      AcquireStartSessionAdmissionProtectionResponse wire) {
    Objects.requireNonNull(request, "Complete Account admission protection request required");
    if (wire == null) throw invalid("Response is missing");
    requireClosed(wire);
    requireBudget(wire, MAX_RESPONSE_WIRE_BYTES);
    if (wire.getSchemaVersionCount() != 1
        || wire.getEvidenceCanonicalBytesCount() != 1
        || wire.getSchemaVersion(0) != SCHEMA_VERSION) {
      throw invalid("Unsupported schema or duplicate response field");
    }
    byte[] canonical = wire.getEvidenceCanonicalBytes(0).toByteArray();
    requirePayloadBudget(canonical, MAX_EVIDENCE_CANONICAL_BYTES, "Account protection evidence");
    AccountStartSessionAdmissionProtectionEvidence evidence =
        AccountStartSessionAdmissionProtectionEvidence.decode(canonical);
    requireSameRequest(request, evidence);
    if (!toResponse(request, evidence).equals(wire)) {
      throw invalid("Response is not its exact closed encoding");
    }
    return evidence;
  }

  public static AccountStartSessionAdmissionProtectionRequest fromRequestWireBytes(
      byte[] wireBytes) {
    requireWireBudget(wireBytes, MAX_REQUEST_WIRE_BYTES, "request");
    try {
      AcquireStartSessionAdmissionProtectionRequest wire =
          AcquireStartSessionAdmissionProtectionRequest.parseFrom(wireBytes);
      AccountStartSessionAdmissionProtectionRequest request = fromRequest(wire);
      if (!Arrays.equals(wireBytes, toRequest(request).toByteArray())) {
        throw invalid("Request wire bytes are not canonical");
      }
      return request;
    } catch (InvalidProtocolBufferException malformed) {
      throw new IllegalArgumentException(
          "Malformed admission-protection request wire bytes", malformed);
    }
  }

  public static AccountStartSessionAdmissionProtectionEvidence fromResponseWireBytes(
      AccountStartSessionAdmissionProtectionRequest request, byte[] wireBytes) {
    requireWireBudget(wireBytes, MAX_RESPONSE_WIRE_BYTES, "response");
    try {
      AcquireStartSessionAdmissionProtectionResponse wire =
          AcquireStartSessionAdmissionProtectionResponse.parseFrom(wireBytes);
      AccountStartSessionAdmissionProtectionEvidence evidence = fromResponse(request, wire);
      if (!Arrays.equals(wireBytes, toResponse(request, evidence).toByteArray())) {
        throw invalid("Response wire bytes are not canonical");
      }
      return evidence;
    } catch (InvalidProtocolBufferException malformed) {
      throw new IllegalArgumentException(
          "Malformed admission-protection response wire bytes", malformed);
    }
  }

  private static void requireSameRequest(
      AccountStartSessionAdmissionProtectionRequest request,
      AccountStartSessionAdmissionProtectionEvidence evidence) {
    if (!Arrays.equals(request.canonicalBytes(), evidence.request().canonicalBytes())) {
      throw invalid("Evidence does not retain the complete requested protection input");
    }
  }

  private static void requireClosed(Message message) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw invalid("Message contains unsupported fields");
    }
  }

  private static void requireBudget(Message message, int maximum) {
    if (message.getSerializedSize() <= 0 || message.getSerializedSize() > maximum) {
      throw invalid("Message exceeds its purpose wire limit");
    }
  }

  private static void requireWireBudget(byte[] bytes, int maximum, String label) {
    if (bytes == null || bytes.length == 0 || bytes.length > maximum) {
      throw invalid("Message exceeds its purpose wire limit: " + label);
    }
  }

  private static void requirePayloadBudget(byte[] bytes, int maximum, String label) {
    if (bytes == null || bytes.length == 0 || bytes.length > maximum) {
      throw invalid(label + " is missing or oversized");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("Admission protection acquisition: " + message);
  }
}
