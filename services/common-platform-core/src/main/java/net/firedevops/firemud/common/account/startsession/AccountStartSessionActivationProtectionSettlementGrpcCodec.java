package net.firedevops.firemud.common.account.startsession;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionActivationProtectionRequest;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionActivationProtectionResponse;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;

/** Closed, bounded protobuf mapping for Account's exact original activation settlement receipt. */
public final class AccountStartSessionActivationProtectionSettlementGrpcCodec {
  /** Small selector only; no caller-supplied terminal bytes are accepted. */
  public static final int MAX_REQUEST_WIRE_BYTES = 512;

  /** Existing full World receipt cap plus bounded protobuf wrapper framing and identity fields. */
  public static final int MAX_RESPONSE_WIRE_BYTES =
      WorldOriginalStartSessionActivationTerminalEvidence.MAX_CANONICAL_BYTES + 256;

  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");
  private static final DateTimeFormatter UTC_MICROS =
      new DateTimeFormatterBuilder()
          .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
          .appendLiteral('.')
          .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 6, 6, false)
          .appendLiteral('Z')
          .toFormatter(Locale.ROOT)
          .withZone(ZoneOffset.UTC);

  private AccountStartSessionActivationProtectionSettlementGrpcCodec() {}

  public static SettleOriginalStartSessionActivationProtectionRequest toRequest(
      AccountStartSessionActivationProtectionSettlementInput input) {
    Objects.requireNonNull(input, "activation settlement input required");
    var request =
        SettleOriginalStartSessionActivationProtectionRequest.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addTargetNamespace(input.targetNamespace())
            .addSettlementRequestId(input.settlementRequestId().toString())
            .addAccountProtectionId(input.accountProtectionId().toString())
            .addAccountProtectionFence(Long.toString(input.accountProtectionFence()))
            .build();
    requireRequestBudget(request);
    return request;
  }

  /** Must be called only after exact same-namespace World peer authentication. */
  public static AccountStartSessionActivationProtectionSettlementInput fromRequest(
      SettleOriginalStartSessionActivationProtectionRequest request, String expectedNamespace) {
    Objects.requireNonNull(request, "activation settlement request required");
    requireClosed(request, "request");
    requireRequestBudget(request);
    if (request.getSchemaVersionCount() != 1
        || request.getTargetNamespaceCount() != 1
        || request.getSettlementRequestIdCount() != 1
        || request.getAccountProtectionIdCount() != 1
        || request.getAccountProtectionFenceCount() != 1
        || request.getSchemaVersion(0) != SCHEMA_VERSION
        || !GrpcPeerIdentity.isValidNamespace(expectedNamespace)
        || !expectedNamespace.equals(request.getTargetNamespace(0))) {
      throw invalid("Unsupported schema, duplicate field, or target namespace");
    }
    var input =
        new AccountStartSessionActivationProtectionSettlementInput(
            expectedNamespace,
            canonicalUuid(request.getSettlementRequestId(0), "settlementRequestId"),
            canonicalUuid(request.getAccountProtectionId(0), "accountProtectionId"),
            canonicalPositiveLong(request.getAccountProtectionFence(0)));
    if (!toRequest(input).equals(request)) {
      throw invalid("Activation settlement request is not its exact closed encoding");
    }
    return input;
  }

  public static AccountStartSessionActivationProtectionSettlementEvidence fromResponse(
      AccountStartSessionActivationProtectionSettlementInput input,
      SettleOriginalStartSessionActivationProtectionResponse response) {
    Objects.requireNonNull(input, "activation settlement input required");
    Objects.requireNonNull(response, "activation settlement response required");
    requireClosed(response, "response");
    requireResponseBudget(response);
    if (response.getSchemaVersionCount() != 1
        || response.getSettlementRequestIdCount() != 1
        || response.getAccountProtectionIdCount() != 1
        || response.getAccountProtectionFenceCount() != 1
        || response.getSettledAtCount() != 1
        || response.getTerminalEvidenceCanonicalBytesCount() != 1
        || response.getSchemaVersion(0) != SCHEMA_VERSION
        || !input.settlementRequestId().toString().equals(response.getSettlementRequestId(0))
        || !input.accountProtectionId().toString().equals(response.getAccountProtectionId(0))
        || !Long.toString(input.accountProtectionFence())
            .equals(response.getAccountProtectionFence(0))) {
      throw invalid("Activation settlement response identity differs from its request");
    }
    Instant settledAt = canonicalInstant(response.getSettledAt(0));
    byte[] terminalBytes = response.getTerminalEvidenceCanonicalBytes(0).toByteArray();
    if (terminalBytes.length == 0
        || terminalBytes.length
            > WorldOriginalStartSessionActivationTerminalEvidence.MAX_CANONICAL_BYTES) {
      throw invalid("Complete World terminal receipt is missing or oversized");
    }
    var terminal = WorldOriginalStartSessionActivationTerminalEvidence.decode(terminalBytes);
    if (!Arrays.equals(terminalBytes, terminal.canonicalBytes())
        || !input
            .accountProtectionId()
            .equals(terminal.accountProtectionEvidence().accountProtectionId())
        || input.accountProtectionFence()
            != terminal.accountProtectionEvidence().accountProtectionFence()
        || !input
            .targetNamespace()
            .equals(
                terminal
                    .accountProtectionEvidence()
                    .request()
                    .originalTuple()
                    .preAuthorizationTuple()
                    .action()
                    .scope()
                    .targetNamespace())) {
      throw invalid("Full World terminal receipt differs from the exact Account protection");
    }
    var evidence =
        new AccountStartSessionActivationProtectionSettlementEvidence(
            input.settlementRequestId(), terminal, settledAt);
    if (!toResponse(evidence).equals(response)) {
      throw invalid("Activation settlement response is not its exact closed encoding");
    }
    return evidence;
  }

  /** Encodes only complete settlement evidence returned by the Account owner service. */
  public static SettleOriginalStartSessionActivationProtectionResponse toResponse(
      AccountStartSessionActivationProtectionSettlementInput input,
      AccountStartSessionActivationProtectionSettlementEvidence evidence) {
    Objects.requireNonNull(input, "activation settlement input required");
    Objects.requireNonNull(evidence, "durable Account settlement evidence required");
    if (!input.settlementRequestId().equals(evidence.settlementRequestId())
        || !input.accountProtectionId().equals(evidence.accountProtectionId())
        || input.accountProtectionFence() != evidence.accountProtectionFence()
        || !input
            .targetNamespace()
            .equals(
                evidence
                    .terminal()
                    .accountProtectionEvidence()
                    .request()
                    .originalTuple()
                    .preAuthorizationTuple()
                    .action()
                    .scope()
                    .targetNamespace())) {
      throw invalid("Durable settlement differs from the exact request identity");
    }
    return toResponse(evidence);
  }

  public static AccountStartSessionActivationProtectionSettlementEvidence fromResponseWireBytes(
      AccountStartSessionActivationProtectionSettlementInput input, byte[] wireBytes) {
    if (wireBytes == null || wireBytes.length == 0 || wireBytes.length > MAX_RESPONSE_WIRE_BYTES) {
      throw invalid("Activation settlement response exceeds its wire budget");
    }
    try {
      var response = SettleOriginalStartSessionActivationProtectionResponse.parseFrom(wireBytes);
      var evidence = fromResponse(input, response);
      if (!Arrays.equals(wireBytes, toResponse(evidence).toByteArray())) {
        throw invalid("Activation settlement response wire bytes are not canonical");
      }
      return evidence;
    } catch (InvalidProtocolBufferException malformed) {
      throw invalid("Activation settlement response wire bytes are malformed", malformed);
    }
  }

  public static AccountStartSessionActivationProtectionSettlementInput fromRequestWireBytes(
      byte[] wireBytes, String expectedNamespace) {
    if (wireBytes == null || wireBytes.length == 0 || wireBytes.length > MAX_REQUEST_WIRE_BYTES) {
      throw invalid("Activation settlement request exceeds its wire budget");
    }
    try {
      var request = SettleOriginalStartSessionActivationProtectionRequest.parseFrom(wireBytes);
      var input = fromRequest(request, expectedNamespace);
      if (!Arrays.equals(wireBytes, toRequest(input).toByteArray())) {
        throw invalid("Activation settlement request wire bytes are not canonical");
      }
      return input;
    } catch (InvalidProtocolBufferException malformed) {
      throw invalid("Activation settlement request wire bytes are malformed", malformed);
    }
  }

  private static SettleOriginalStartSessionActivationProtectionResponse toResponse(
      AccountStartSessionActivationProtectionSettlementEvidence evidence) {
    var response =
        SettleOriginalStartSessionActivationProtectionResponse.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addSettlementRequestId(evidence.settlementRequestId().toString())
            .addAccountProtectionId(evidence.accountProtectionId().toString())
            .addAccountProtectionFence(Long.toString(evidence.accountProtectionFence()))
            .addSettledAt(UTC_MICROS.format(evidence.settledAt()))
            .addTerminalEvidenceCanonicalBytes(
                ByteString.copyFrom(evidence.terminal().canonicalBytes()))
            .build();
    requireResponseBudget(response);
    return response;
  }

  private static void requireRequestBudget(
      SettleOriginalStartSessionActivationProtectionRequest request) {
    if (request.getSerializedSize() <= 0 || request.getSerializedSize() > MAX_REQUEST_WIRE_BYTES) {
      throw invalid("Activation settlement request exceeds its wire budget");
    }
  }

  private static void requireResponseBudget(
      SettleOriginalStartSessionActivationProtectionResponse response) {
    if (response.getSerializedSize() <= 0
        || response.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES
        || response.getTerminalEvidenceCanonicalBytesCount() != 1
        || response.getTerminalEvidenceCanonicalBytes(0).isEmpty()
        || response.getTerminalEvidenceCanonicalBytes(0).size()
            > WorldOriginalStartSessionActivationTerminalEvidence.MAX_CANONICAL_BYTES) {
      throw invalid("Complete World terminal receipt is missing or exceeds its wire budget");
    }
  }

  private static void requireClosed(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw invalid("Activation settlement " + label + " contains unsupported fields");
    }
  }

  private static UUID canonicalUuid(String value, String field) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || NIL_UUID.equals(parsed)) {
        throw invalid(field + " must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " must be a canonical non-nil UUID", malformed);
    }
  }

  private static long canonicalPositiveLong(String text) {
    if (text == null || !POSITIVE_DECIMAL.matcher(text).matches()) {
      throw invalid("Account protection fence must be positive canonical decimal text");
    }
    try {
      long value = Long.parseLong(text);
      if (value <= 0L || !Long.toString(value).equals(text)) {
        throw invalid("Account protection fence must fit a positive signed long");
      }
      return value;
    } catch (NumberFormatException malformed) {
      throw invalid("Account protection fence must fit a positive signed long", malformed);
    }
  }

  private static Instant canonicalInstant(String text) {
    try {
      Instant value = Instant.parse(text);
      if (value.getNano() % 1_000 != 0 || !UTC_MICROS.format(value).equals(text)) {
        throw invalid("Account settlement timestamp must be exact UTC microseconds");
      }
      return value;
    } catch (RuntimeException malformed) {
      throw invalid("Account settlement timestamp must be exact UTC microseconds", malformed);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("Original StartSession activation settlement " + message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(
        "Original StartSession activation settlement " + message, cause);
  }
}
