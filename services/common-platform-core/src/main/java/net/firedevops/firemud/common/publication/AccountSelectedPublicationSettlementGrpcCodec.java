package net.firedevops.firemud.common.publication;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.SettleSelectedPublicationRequest;
import net.firedevops.firemud.account.v1.SettleSelectedPublicationResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Closed Account-owned selected-publication settlement transport and receipt validation. */
public final class AccountSelectedPublicationSettlementGrpcCodec {
  /** Shared per-message protobuf budget; oversized complete six-frame receipts fail closed. */
  public static final int MAX_MESSAGE_BYTES = 32 * 1024 * 1024;

  private static final int MESSAGE_ENVELOPE_OVERHEAD_RESERVE = 256;
  private static final String RECEIPT_SCHEMA = "account-selected-publication-settlement/v1";

  private AccountSelectedPublicationSettlementGrpcCodec() {}

  public static SettleSelectedPublicationRequest toRequest(Request request) {
    Objects.requireNonNull(request, "request");
    var wire =
        SettleSelectedPublicationRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setRequestId(request.requestId().toString())
            .setOriginalOperation(ByteString.copyFrom(request.originalOperation()))
            .build();
    requireWireBudget(wire);
    return wire;
  }

  /** Receiver authenticates the exact Game Design peer before decoding this request. */
  public static Request fromRequest(SettleSelectedPublicationRequest request) {
    Objects.requireNonNull(request, "request");
    requireWireBudget(request);
    noUnknownFields(request);
    try {
      return new Request(
          request.getSchemaVersion(),
          request.getTargetNamespace(),
          canonicalUuid(request.getRequestId()),
          request.getOriginalOperation().toByteArray());
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "Account publication settlement request is invalid", malformed);
    }
  }

  /** Emits only an exact original receipt whose six canonical frames agree with the request. */
  public static SettleSelectedPublicationResponse toResponse(Request request, byte[] receipt) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(receipt, "receipt");
    if ((long) receipt.length + request.originalOperation().length
        > MAX_MESSAGE_BYTES - MESSAGE_ENVELOPE_OVERHEAD_RESERVE) {
      throw new IllegalArgumentException(
          "Account publication settlement response exceeds the 32 MiB wire budget");
    }
    var response =
        SettleSelectedPublicationResponse.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setRequestId(request.requestId().toString())
            .setOriginalOperation(ByteString.copyFrom(request.originalOperation()))
            .setSettlementReceipt(ByteString.copyFrom(receipt))
            .build();
    requireWireBudget(response);
    validateReceipt(request, receipt);
    return response;
  }

  /** Verifies the full echo and returns the exact canonical Account settlement receipt. */
  public static byte[] fromResponse(Request request, SettleSelectedPublicationResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireWireBudget(response);
    noUnknownFields(response);
    if (response.getSchemaVersion() != request.schemaVersion()
        || !request.targetNamespace().equals(response.getTargetNamespace())
        || !request.requestId().toString().equals(response.getRequestId())
        || !Arrays.equals(
            request.originalOperation(), response.getOriginalOperation().toByteArray())) {
      throw new IllegalArgumentException(
          "Account changed the exact publication settlement request");
    }
    byte[] receipt = response.getSettlementReceipt().toByteArray();
    validateReceipt(request, receipt);
    return receipt.clone();
  }

  private static void validateReceipt(Request request, byte[] receipt) {
    if (receipt == null || receipt.length == 0 || receipt.length > MAX_MESSAGE_BYTES) {
      throw new IllegalArgumentException(
          "Complete bounded Account publication settlement receipt required");
    }
    final byte[] accountBinding;
    final byte[] originalOperation;
    final byte[] gameDesignTerminalBytes;
    final String worldOutcome;
    final byte[] worldTerminalBytes;
    try {
      var reader = new DraftAuthorizationFenceBinding.FrameReader(receipt);
      reader.expect(RECEIPT_SCHEMA);
      accountBinding = reader.bytes();
      originalOperation = reader.bytes();
      gameDesignTerminalBytes = reader.bytes();
      worldOutcome = reader.text();
      worldTerminalBytes = reader.bytes();
      reader.requireEnd();
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException(
          "Canonical six-frame Account publication settlement receipt required", malformed);
    }

    GameDesignPublicationOperationBinding operation;
    GameDesignPublicationTerminalEvidence terminal;
    try {
      operation = GameDesignPublicationOperationBinding.fromStored(request.originalOperation());
      terminal = GameDesignPublicationTerminalEvidence.fromStored(gameDesignTerminalBytes);
      GameDesignPublicationTerminalEvidence.fromStored(worldTerminalBytes);
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException(
          "Account receipt lacks complete canonical publication evidence", malformed);
    }
    String expectedWorldOutcome =
        terminal.outcome() == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
            ? "PUBLISHED"
            : "ABORTED";
    if (!Arrays.equals(accountBinding, operation.account().canonicalBytes())
        || !Arrays.equals(originalOperation, request.originalOperation())
        || !Arrays.equals(terminal.operationBytes(), originalOperation)
        || !Arrays.equals(gameDesignTerminalBytes, terminal.canonicalBytes())
        || !Arrays.equals(worldTerminalBytes, gameDesignTerminalBytes)
        || !expectedWorldOutcome.equals(worldOutcome)) {
      throw new IllegalArgumentException(
          "Account settlement receipt differs from the exact operation, terminal, or World outcome");
    }

    var canonical = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(canonical, RECEIPT_SCHEMA);
    DraftAuthorizationFenceBinding.frame(canonical, accountBinding);
    DraftAuthorizationFenceBinding.frame(canonical, originalOperation);
    DraftAuthorizationFenceBinding.frame(canonical, gameDesignTerminalBytes);
    DraftAuthorizationFenceBinding.frame(canonical, worldOutcome);
    DraftAuthorizationFenceBinding.frame(canonical, worldTerminalBytes);
    if (!Arrays.equals(receipt, canonical.toByteArray())) {
      throw new IllegalArgumentException("Noncanonical Account publication settlement receipt");
    }
  }

  private static void requireWireBudget(Message message) {
    if (message.getSerializedSize() > MAX_MESSAGE_BYTES) {
      throw new IllegalArgumentException(
          "Account publication settlement exceeds the 32 MiB wire budget");
    }
  }

  private static void noUnknownFields(Message message) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Account publication settlement has unsupported fields");
    }
  }

  private static UUID canonicalUuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || parsed.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException("Canonical non-nil request UUID required");
      }
      return parsed;
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException("Canonical non-nil request UUID required", malformed);
    }
  }

  /** Request correlation is fresh per transport call and is not the retained settlement key. */
  public record Request(
      int schemaVersion, String targetNamespace, UUID requestId, byte[] originalOperation) {
    public static final int SCHEMA_VERSION = 1;

    public Request {
      if (schemaVersion != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported Account publication settlement schema");
      }
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Canonical Account workload namespace required");
      }
      if (requestId == null || requestId.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException("Non-nil canonical request identity required");
      }
      if (originalOperation == null || originalOperation.length == 0) {
        throw new IllegalArgumentException("Complete original publication operation required");
      }
      if (originalOperation.length > GameDesignPublicationOperationBinding.MAX_OPERATION_BYTES) {
        throw new IllegalArgumentException("Original publication operation exceeds 4 MiB");
      }
      originalOperation = originalOperation.clone();
      GameDesignPublicationOperationBinding operation;
      try {
        operation = GameDesignPublicationOperationBinding.fromStored(originalOperation);
      } catch (RuntimeException malformed) {
        throw new IllegalArgumentException(
            "Complete canonical original publication operation required", malformed);
      }
      if (!Arrays.equals(originalOperation, operation.canonicalBytes())) {
        throw new IllegalArgumentException(
            "Exact canonical original publication operation required");
      }
      if (!targetNamespace.equals(operation.world().request().targetNamespace())) {
        throw new IllegalArgumentException(
            "Original publication operation differs from the Account workload namespace");
      }
    }

    public static Request create(String targetNamespace, byte[] originalOperation) {
      return new Request(SCHEMA_VERSION, targetNamespace, UUID.randomUUID(), originalOperation);
    }

    @Override
    public byte[] originalOperation() {
      return originalOperation.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Request that
          && schemaVersion == that.schemaVersion
          && targetNamespace.equals(that.targetNamespace)
          && requestId.equals(that.requestId)
          && Arrays.equals(originalOperation, that.originalOperation);
    }

    @Override
    public int hashCode() {
      return 31 * Objects.hash(schemaVersion, targetNamespace, requestId)
          + Arrays.hashCode(originalOperation);
    }
  }
}
