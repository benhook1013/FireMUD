package net.firedevops.firemud.common.world;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionInput;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.worldmanagement.v1.ActivateOriginalStartSessionProtectedRequest;
import net.firedevops.firemud.worldmanagement.v1.ActivateOriginalStartSessionProtectedResponse;

/**
 * Closed, bounded World transport mapping that preserves the complete Account acquisition input.
 */
public final class WorldOriginalStartSessionProtectedActivationGrpcCodec {
  public static final int MAX_REQUEST_WIRE_BYTES = 65 * 1024 * 1024 + 128;
  public static final int MAX_RESPONSE_WIRE_BYTES =
      WorldOriginalStartSessionActivationTerminalEvidence.MAX_CANONICAL_BYTES + 64;
  private static final int SCHEMA_VERSION = 1;

  private WorldOriginalStartSessionProtectedActivationGrpcCodec() {}

  public static ActivateOriginalStartSessionProtectedRequest toRequest(
      AccountStartSessionActivationProtectionAcquisitionInput input, String namespace) {
    Objects.requireNonNull(input, "activation acquisition input required");
    requireNamespace(namespace);
    input.requireTargetNamespace(namespace);
    var request =
        ActivateOriginalStartSessionProtectedRequest.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addTargetNamespace(namespace)
            .addAcquisitionInput(
                AccountStartSessionActivationProtectionAcquisitionGrpcCodec.toRequest(
                    input, namespace))
            .build();
    requireRequestBudget(request);
    return request;
  }

  /** Call only after exact same-namespace Game Session workload authentication. */
  public static AccountStartSessionActivationProtectionAcquisitionInput fromRequest(
      ActivateOriginalStartSessionProtectedRequest request, String expectedNamespace) {
    Objects.requireNonNull(request, "protected activation request required");
    requireClosed(request, "request");
    requireRequestBudget(request);
    if (request.getSchemaVersionCount() != 1
        || request.getTargetNamespaceCount() != 1
        || request.getAcquisitionInputCount() != 1
        || request.getSchemaVersion(0) != SCHEMA_VERSION
        || !GrpcPeerIdentity.isValidNamespace(expectedNamespace)
        || !expectedNamespace.equals(request.getTargetNamespace(0))) {
      throw invalid("Unsupported schema, duplicate field, or target namespace");
    }
    AccountStartSessionActivationProtectionAcquisitionInput input =
        AccountStartSessionActivationProtectionAcquisitionGrpcCodec.fromRequest(
            request.getAcquisitionInput(0), expectedNamespace);
    if (!toRequest(input, expectedNamespace).equals(request)) {
      throw invalid("Protected activation request is not its exact closed encoding");
    }
    return input;
  }

  public static ActivateOriginalStartSessionProtectedResponse toResponse(
      AccountStartSessionActivationProtectionAcquisitionInput input,
      String namespace,
      WorldOriginalStartSessionActivationTerminalEvidence terminal) {
    requireTerminalMatches(input, namespace, terminal);
    var response =
        ActivateOriginalStartSessionProtectedResponse.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addTerminalEvidence(ByteString.copyFrom(terminal.canonicalBytes()))
            .build();
    requireResponseBudget(response);
    return response;
  }

  /**
   * Returns the complete retained first terminal receipt without rewriting its read correlations.
   */
  public static WorldOriginalStartSessionActivationTerminalEvidence fromResponse(
      AccountStartSessionActivationProtectionAcquisitionInput input,
      String namespace,
      ActivateOriginalStartSessionProtectedResponse response) {
    Objects.requireNonNull(input, "activation acquisition input required");
    Objects.requireNonNull(response, "protected activation response required");
    requireClosed(response, "response");
    requireResponseBudget(response);
    if (response.getSchemaVersionCount() != 1
        || response.getSchemaVersion(0) != SCHEMA_VERSION
        || response.getTerminalEvidenceCount() != 1) {
      throw invalid("Unsupported schema or missing exact World terminal evidence");
    }
    byte[] bytes = response.getTerminalEvidence(0).toByteArray();
    if (bytes.length == 0
        || bytes.length > WorldOriginalStartSessionActivationTerminalEvidence.MAX_CANONICAL_BYTES) {
      throw invalid("World terminal evidence is missing or oversized");
    }
    WorldOriginalStartSessionActivationTerminalEvidence terminal =
        WorldOriginalStartSessionActivationTerminalEvidence.decode(bytes);
    requireTerminalMatches(input, namespace, terminal);
    if (!toResponse(input, namespace, terminal).equals(response)) {
      throw invalid("Protected activation response is not its exact closed encoding");
    }
    return terminal;
  }

  private static void requireTerminalMatches(
      AccountStartSessionActivationProtectionAcquisitionInput input,
      String namespace,
      WorldOriginalStartSessionActivationTerminalEvidence terminal) {
    Objects.requireNonNull(input, "activation acquisition input required");
    Objects.requireNonNull(terminal, "World terminal evidence required");
    requireNamespace(namespace);
    input.requireTargetNamespace(namespace);
    // The Account codec compares the caller's full tuple, GS mutation/attempt/fence, activation
    // identity, and stable PREPARING request. It deliberately tolerates only World read correlation
    // normalization and does not replace the first Account receipt carried by the terminal.
    AccountStartSessionActivationProtectionAcquisitionGrpcCodec.toResponse(
        input, namespace, terminal.accountProtectionEvidence());
    var requested = input.worldActivationRequest();
    var terminalRequest = terminal.activationResult().request();
    if (!requested.activationRequestId().equals(terminal.activationRequestId())
        || !Arrays.equals(
            requested.canonicalRequestBytes(), terminalRequest.canonicalRequestBytes())) {
      throw invalid("World terminal differs from the exact caller activation statement");
    }
  }

  private static void requireRequestBudget(ActivateOriginalStartSessionProtectedRequest request) {
    if (request.getSerializedSize() <= 0 || request.getSerializedSize() > MAX_REQUEST_WIRE_BYTES) {
      throw invalid("World protected activation request exceeds its wire limit");
    }
  }

  private static void requireResponseBudget(
      ActivateOriginalStartSessionProtectedResponse response) {
    if (response.getSerializedSize() <= 0
        || response.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES
        || response.getTerminalEvidenceCount() != 1
        || response.getTerminalEvidence(0).isEmpty()
        || response.getTerminalEvidence(0).size()
            > WorldOriginalStartSessionActivationTerminalEvidence.MAX_CANONICAL_BYTES) {
      throw invalid("World activation terminal response exceeds its carrier-specific wire limit");
    }
  }

  private static void requireClosed(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw invalid("World protected activation " + label + " contains unsupported fields");
    }
  }

  private static void requireNamespace(String namespace) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw invalid("Target namespace is invalid");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("World protected activation " + message);
  }
}
