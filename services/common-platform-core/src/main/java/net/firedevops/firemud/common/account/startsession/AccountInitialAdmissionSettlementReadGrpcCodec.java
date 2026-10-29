package net.firedevops.firemud.common.account.startsession;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.gamesession.v1.GetAccountInitialAdmissionSettlementRequest;
import net.firedevops.firemud.gamesession.v1.GetAccountInitialAdmissionSettlementResponse;

/** Closed purpose-bounded mapping; receivers must authenticate Account before decoding. */
public final class AccountInitialAdmissionSettlementReadGrpcCodec {
  public static final int MAX_REQUEST_WIRE_BYTES = 12 * 1024 * 1024;
  public static final int MAX_RESPONSE_WIRE_BYTES = 12 * 1024 * 1024;
  private static final int SCHEMA_VERSION = 1;

  private AccountInitialAdmissionSettlementReadGrpcCodec() {}

  public static GetAccountInitialAdmissionSettlementRequest toRequest(
      AccountInitialAdmissionSettlementReadInput input, String namespace) {
    Objects.requireNonNull(input, "Admission settlement input required");
    input.requireTargetNamespace(namespace);
    byte[] protection = input.protection().canonicalBytes();
    requireProtectionBudget(protection);
    var request =
        GetAccountInitialAdmissionSettlementRequest.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addTargetNamespace(namespace)
            .addReadRequestId(input.readRequestId().toString())
            .addAccountProtectionEvidence(ByteString.copyFrom(protection))
            .build();
    requireBudget(request, MAX_REQUEST_WIRE_BYTES);
    return request;
  }

  public static AccountInitialAdmissionSettlementReadInput fromRequest(
      GetAccountInitialAdmissionSettlementRequest request, String expectedNamespace) {
    if (request == null) throw invalid("Request is missing");
    requireClosed(request);
    requireBudget(request, MAX_REQUEST_WIRE_BYTES);
    if (request.getSchemaVersionCount() != 1
        || request.getTargetNamespaceCount() != 1
        || request.getReadRequestIdCount() != 1
        || request.getAccountProtectionEvidenceCount() != 1
        || request.getSchemaVersion(0) != SCHEMA_VERSION
        || !GrpcPeerIdentity.isValidNamespace(expectedNamespace)
        || !expectedNamespace.equals(request.getTargetNamespace(0))) {
      throw invalid("Unsupported schema, duplicate field, or namespace");
    }
    byte[] protection = request.getAccountProtectionEvidence(0).toByteArray();
    requireProtectionBudget(protection);
    var input =
        new AccountInitialAdmissionSettlementReadInput(
            canonicalUuid(request.getReadRequestId(0)),
            AccountStartSessionAdmissionProtectionEvidence.decode(protection));
    input.requireTargetNamespace(expectedNamespace);
    if (!toRequest(input, expectedNamespace).equals(request)) {
      throw invalid("Request is not its exact closed encoding");
    }
    return input;
  }

  public static GetAccountInitialAdmissionSettlementResponse toResponse(
      AccountInitialAdmissionSettlementReadEvidence evidence) {
    Objects.requireNonNull(evidence, "Admission settlement evidence required");
    byte[] protection = evidence.protection().canonicalBytes();
    requireProtectionBudget(protection);
    var response =
        GetAccountInitialAdmissionSettlementResponse.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addReadRequestId(evidence.readRequestId().toString())
            .addAccountProtectionEvidence(ByteString.copyFrom(protection))
            .addCanonicalGameSessionOwnerProof(
                ByteString.copyFrom(
                    GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                        evidence.terminal())))
            .build();
    requireBudget(response, MAX_RESPONSE_WIRE_BYTES);
    return response;
  }

  public static AccountInitialAdmissionSettlementReadEvidence fromResponse(
      AccountInitialAdmissionSettlementReadInput input,
      GetAccountInitialAdmissionSettlementResponse response) {
    Objects.requireNonNull(input, "Admission settlement input required");
    if (response == null) throw invalid("Response is missing");
    requireClosed(response);
    requireBudget(response, MAX_RESPONSE_WIRE_BYTES);
    if (response.getSchemaVersionCount() != 1
        || response.getSchemaVersion(0) != SCHEMA_VERSION
        || response.getReadRequestIdCount() != 1
        || response.getAccountProtectionEvidenceCount() != 1
        || response.getCanonicalGameSessionOwnerProofCount() != 1
        || !input.readRequestId().toString().equals(response.getReadRequestId(0))) {
      throw invalid("Response fields or read correlation differ");
    }
    byte[] protection = response.getAccountProtectionEvidence(0).toByteArray();
    requireProtectionBudget(protection);
    if (!Arrays.equals(protection, input.protection().canonicalBytes())) {
      throw invalid("Response changed the complete Account protection");
    }
    byte[] proof = response.getCanonicalGameSessionOwnerProof(0).toByteArray();
    if (proof.length == 0
        || proof.length > AccountInitialAdmissionSettlementReadEvidence.MAX_OWNER_PROOF_BYTES) {
      throw invalid("Game Session owner proof is missing or oversized");
    }
    var evidence =
        new AccountInitialAdmissionSettlementReadEvidence(
            input.readRequestId(),
            AccountStartSessionAdmissionProtectionEvidence.decode(protection),
            GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(proof));
    if (!toResponse(evidence).equals(response)) {
      throw invalid("Response is not its exact closed encoding");
    }
    return evidence;
  }

  public static AccountInitialAdmissionSettlementReadInput fromRequestWireBytes(
      byte[] wireBytes, String namespace) {
    requireWireBudget(wireBytes, MAX_REQUEST_WIRE_BYTES);
    try {
      var request = GetAccountInitialAdmissionSettlementRequest.parseFrom(wireBytes);
      var input = fromRequest(request, namespace);
      if (!Arrays.equals(wireBytes, toRequest(input, namespace).toByteArray())) {
        throw invalid("Request wire bytes are not canonical");
      }
      return input;
    } catch (InvalidProtocolBufferException malformed) {
      throw new IllegalArgumentException(
          "Malformed admission settlement request wire bytes", malformed);
    }
  }

  public static AccountInitialAdmissionSettlementReadEvidence fromResponseWireBytes(
      AccountInitialAdmissionSettlementReadInput input, byte[] wireBytes) {
    requireWireBudget(wireBytes, MAX_RESPONSE_WIRE_BYTES);
    try {
      var response = GetAccountInitialAdmissionSettlementResponse.parseFrom(wireBytes);
      var evidence = fromResponse(input, response);
      if (!Arrays.equals(wireBytes, toResponse(evidence).toByteArray())) {
        throw invalid("Response wire bytes are not canonical");
      }
      return evidence;
    } catch (InvalidProtocolBufferException malformed) {
      throw new IllegalArgumentException(
          "Malformed admission settlement response wire bytes", malformed);
    }
  }

  private static void requireProtectionBudget(byte[] bytes) {
    if (bytes.length == 0
        || bytes.length > AccountStartSessionAdmissionProtectionEvidence.MAX_CANONICAL_BYTES) {
      throw invalid("Complete Account protection is missing or oversized");
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

  private static void requireWireBudget(byte[] bytes, int maximum) {
    if (bytes == null || bytes.length == 0 || bytes.length > maximum) {
      throw invalid("Message exceeds its purpose wire limit");
    }
  }

  private static UUID canonicalUuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value) || new UUID(0L, 0L).equals(id)) {
        throw invalid("Canonical non-nil read ID required");
      }
      return id;
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException("Canonical non-nil read ID required", malformed);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("Admission settlement read: " + message);
  }
}
