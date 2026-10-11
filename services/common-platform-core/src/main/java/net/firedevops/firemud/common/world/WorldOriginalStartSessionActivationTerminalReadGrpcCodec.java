package net.firedevops.firemud.common.world;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.worldmanagement.v1.ReadOriginalStartSessionActivationTerminalEvidenceRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadOriginalStartSessionActivationTerminalEvidenceResponse;

/** Closed bounded protobuf mapping for exact World original activation terminal reads. */
public final class WorldOriginalStartSessionActivationTerminalReadGrpcCodec {
  public static final int MAX_REQUEST_WIRE_BYTES =
      AccountStartSessionActivationProtectionEvidence.MAX_CANONICAL_BYTES + 256;
  public static final int MAX_RESPONSE_WIRE_BYTES =
      WorldOriginalStartSessionActivationTerminalEvidence.MAX_CANONICAL_BYTES + 64;
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private WorldOriginalStartSessionActivationTerminalReadGrpcCodec() {}

  public static ReadOriginalStartSessionActivationTerminalEvidenceRequest toRequest(
      WorldOriginalStartSessionActivationTerminalReadInput input, String namespace) {
    Objects.requireNonNull(input, "World terminal read input required");
    requireNamespace(namespace);
    input.requireTargetNamespace(namespace);
    var request =
        ReadOriginalStartSessionActivationTerminalEvidenceRequest.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addTargetNamespace(namespace)
            .addReadRequestId(input.readRequestId().toString())
            .addAccountProtectionEvidence(
                ByteString.copyFrom(input.protectionEvidence().canonicalBytes()))
            .build();
    requireRequestBudget(request);
    return request;
  }

  /** Call only after the receiver has authenticated the exact same-namespace Account peer. */
  public static WorldOriginalStartSessionActivationTerminalReadInput fromRequest(
      ReadOriginalStartSessionActivationTerminalEvidenceRequest request, String expectedNamespace) {
    if (request == null) throw invalid("request is missing");
    requireClosed(request, "request");
    requireRequestBudget(request);
    if (request.getSchemaVersionCount() != 1
        || request.getTargetNamespaceCount() != 1
        || request.getReadRequestIdCount() != 1
        || request.getAccountProtectionEvidenceCount() != 1
        || request.getSchemaVersion(0) != SCHEMA_VERSION
        || !GrpcPeerIdentity.isValidNamespace(expectedNamespace)
        || !expectedNamespace.equals(request.getTargetNamespace(0))) {
      throw invalid("Unsupported schema, duplicate field, or namespace");
    }
    byte[] evidenceBytes = request.getAccountProtectionEvidence(0).toByteArray();
    if (evidenceBytes.length == 0
        || evidenceBytes.length
            > AccountStartSessionActivationProtectionEvidence.MAX_CANONICAL_BYTES) {
      throw invalid("Complete Account activation protection evidence is missing or oversized");
    }
    var input =
        new WorldOriginalStartSessionActivationTerminalReadInput(
            canonicalUuid(request.getReadRequestId(0)),
            AccountStartSessionActivationProtectionEvidence.decode(evidenceBytes));
    input.requireTargetNamespace(expectedNamespace);
    if (!toRequest(input, expectedNamespace).equals(request)) {
      throw invalid("World terminal read request is not its exact closed encoding");
    }
    return input;
  }

  public static ReadOriginalStartSessionActivationTerminalEvidenceResponse toResponse(
      WorldOriginalStartSessionActivationTerminalReadInput input,
      WorldOriginalStartSessionActivationTerminalEvidence terminal) {
    requireTerminalMatches(input, terminal);
    var response =
        ReadOriginalStartSessionActivationTerminalEvidenceResponse.newBuilder()
            .addReadRequestId(input.readRequestId().toString())
            .addTerminalEvidence(ByteString.copyFrom(terminal.canonicalBytes()))
            .build();
    requireResponseBudget(response);
    return response;
  }

  /** Validates and returns the complete first immutable World receipt unchanged. */
  public static WorldOriginalStartSessionActivationTerminalEvidence fromResponse(
      WorldOriginalStartSessionActivationTerminalReadInput input,
      ReadOriginalStartSessionActivationTerminalEvidenceResponse response) {
    Objects.requireNonNull(input, "World terminal read input required");
    Objects.requireNonNull(response, "World terminal read response required");
    requireClosed(response, "response");
    requireResponseBudget(response);
    if (response.getReadRequestIdCount() != 1
        || response.getTerminalEvidenceCount() != 1
        || !input.readRequestId().toString().equals(response.getReadRequestId(0))) {
      throw invalid("World terminal response correlation is missing or changed");
    }
    WorldOriginalStartSessionActivationTerminalEvidence terminal =
        WorldOriginalStartSessionActivationTerminalEvidence.decode(
            response.getTerminalEvidence(0).toByteArray());
    requireTerminalMatches(input, terminal);
    if (!toResponse(input, terminal).equals(response)) {
      throw invalid("World terminal response is not its exact closed encoding");
    }
    return terminal;
  }

  public static WorldOriginalStartSessionActivationTerminalReadInput fromRequestWireBytes(
      byte[] wireBytes, String namespace) {
    if (wireBytes == null || wireBytes.length == 0 || wireBytes.length > MAX_REQUEST_WIRE_BYTES) {
      throw invalid("World terminal request exceeds its wire limit");
    }
    try {
      var wire = ReadOriginalStartSessionActivationTerminalEvidenceRequest.parseFrom(wireBytes);
      var input = fromRequest(wire, namespace);
      if (!Arrays.equals(wireBytes, toRequest(input, namespace).toByteArray())) {
        throw invalid("World terminal request wire bytes are not canonical");
      }
      return input;
    } catch (InvalidProtocolBufferException malformed) {
      throw invalid("World terminal request wire bytes are malformed", malformed);
    }
  }

  public static WorldOriginalStartSessionActivationTerminalEvidence fromResponseWireBytes(
      WorldOriginalStartSessionActivationTerminalReadInput input, byte[] wireBytes) {
    if (wireBytes == null || wireBytes.length == 0 || wireBytes.length > MAX_RESPONSE_WIRE_BYTES) {
      throw invalid("World terminal response exceeds its wire limit");
    }
    try {
      var wire = ReadOriginalStartSessionActivationTerminalEvidenceResponse.parseFrom(wireBytes);
      var terminal = fromResponse(input, wire);
      if (!Arrays.equals(wireBytes, toResponse(input, terminal).toByteArray())) {
        throw invalid("World terminal response wire bytes are not canonical");
      }
      return terminal;
    } catch (InvalidProtocolBufferException malformed) {
      throw invalid("World terminal response wire bytes are malformed", malformed);
    }
  }

  private static void requireTerminalMatches(
      WorldOriginalStartSessionActivationTerminalReadInput input,
      WorldOriginalStartSessionActivationTerminalEvidence terminal) {
    Objects.requireNonNull(terminal, "World terminal receipt required");
    if (!Arrays.equals(
        input.protectionEvidence().canonicalBytes(),
        terminal.accountProtectionEvidence().canonicalBytes())) {
      throw invalid("World terminal receipt is not bound to the requested full Account evidence");
    }
    input.requireTargetNamespace(input.targetNamespace());
  }

  private static void requireRequestBudget(
      ReadOriginalStartSessionActivationTerminalEvidenceRequest request) {
    if (request.getSerializedSize() <= 0 || request.getSerializedSize() > MAX_REQUEST_WIRE_BYTES) {
      throw invalid("World terminal request exceeds its wire limit");
    }
  }

  private static void requireResponseBudget(
      ReadOriginalStartSessionActivationTerminalEvidenceResponse response) {
    if (response.getSerializedSize() <= 0
        || response.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES
        || response.getTerminalEvidenceCount() != 1
        || response.getTerminalEvidence(0).isEmpty()
        || response.getTerminalEvidence(0).size()
            > WorldOriginalStartSessionActivationTerminalEvidence.MAX_CANONICAL_BYTES) {
      throw invalid("World terminal evidence is missing or exceeds its existing byte limit");
    }
  }

  private static void requireClosed(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw invalid("World terminal " + label + " contains unsupported fields");
    }
  }

  private static UUID canonicalUuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value) || NIL_UUID.equals(id))
        throw invalid("Invalid read correlation ID");
      return id;
    } catch (IllegalArgumentException malformed) {
      throw invalid("Invalid read correlation ID", malformed);
    }
  }

  private static void requireNamespace(String namespace) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw invalid("Invalid World target namespace");
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("Original activation terminal read " + message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException("Original activation terminal read " + message, cause);
  }
}
