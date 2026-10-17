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
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionWorldParticipationRequest;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionWorldParticipationResponse;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;

/** Closed, bounded protobuf mapping for Account's exact original World participation receipt. */
public final class AccountStartSessionWorldParticipationSettlementGrpcCodec {
  /** Small selector only; no caller-supplied terminal bytes are accepted. */
  public static final int MAX_REQUEST_WIRE_BYTES = 512;

  /** Existing complete World terminal cap plus bounded protobuf framing and identity fields. */
  public static final int MAX_RESPONSE_WIRE_BYTES =
      WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES + 256;

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

  private AccountStartSessionWorldParticipationSettlementGrpcCodec() {}

  public static SettleOriginalStartSessionWorldParticipationRequest toRequest(
      AccountStartSessionWorldParticipationSettlementInput input) {
    Objects.requireNonNull(input, "World participation settlement input required");
    var request =
        SettleOriginalStartSessionWorldParticipationRequest.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addTargetNamespace(input.targetNamespace())
            .addSettlementRequestId(input.settlementRequestId().toString())
            .addAccountWorldParticipationId(input.accountWorldParticipationId().toString())
            .addAccountWorldParticipationFence(
                Long.toString(input.accountWorldParticipationFence()))
            .build();
    requireRequestBudget(request);
    return request;
  }

  /** Must be called only after exact same-namespace World peer authentication. */
  public static AccountStartSessionWorldParticipationSettlementInput fromRequest(
      SettleOriginalStartSessionWorldParticipationRequest request, String expectedNamespace) {
    Objects.requireNonNull(request, "World participation settlement request required");
    requireClosed(request, "request");
    requireRequestBudget(request);
    if (request.getSchemaVersionCount() != 1
        || request.getTargetNamespaceCount() != 1
        || request.getSettlementRequestIdCount() != 1
        || request.getAccountWorldParticipationIdCount() != 1
        || request.getAccountWorldParticipationFenceCount() != 1
        || request.getSchemaVersion(0) != SCHEMA_VERSION
        || !GrpcPeerIdentity.isValidNamespace(expectedNamespace)
        || !expectedNamespace.equals(request.getTargetNamespace(0))) {
      throw invalid("Unsupported schema, duplicate field, or target namespace");
    }
    var input =
        new AccountStartSessionWorldParticipationSettlementInput(
            expectedNamespace,
            canonicalUuid(request.getSettlementRequestId(0), "settlementRequestId"),
            canonicalUuid(request.getAccountWorldParticipationId(0), "accountWorldParticipationId"),
            canonicalPositiveLong(request.getAccountWorldParticipationFence(0)));
    if (!toRequest(input).equals(request)) {
      throw invalid("World participation settlement request is not its exact closed encoding");
    }
    return input;
  }

  /** Encodes only complete settlement evidence returned by the Account owner service. */
  public static SettleOriginalStartSessionWorldParticipationResponse toResponse(
      AccountStartSessionWorldParticipationSettlementInput input,
      AccountStartSessionWorldParticipationSettlementEvidence evidence) {
    Objects.requireNonNull(input, "World participation settlement input required");
    Objects.requireNonNull(evidence, "durable Account settlement evidence required");
    WorldStartSessionExecutionTerminal terminal = evidence.terminal();
    if (!input.settlementRequestId().equals(evidence.settlementRequestId())
        || !input.accountWorldParticipationId().equals(terminal.accountWorldParticipationId())
        || input.accountWorldParticipationFence() != terminal.accountWorldParticipationFence()
        || !input.targetNamespace().equals(terminal.targetNamespace())) {
      throw invalid("Durable settlement differs from the exact request identity");
    }
    var response =
        SettleOriginalStartSessionWorldParticipationResponse.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addSettlementRequestId(evidence.settlementRequestId().toString())
            .addAccountWorldParticipationId(terminal.accountWorldParticipationId().toString())
            .addAccountWorldParticipationFence(
                Long.toString(terminal.accountWorldParticipationFence()))
            .addSettledAt(UTC_MICROS.format(evidence.settledAt()))
            .addTerminalEvidenceCanonicalBytes(ByteString.copyFrom(terminal.canonicalBytes()))
            .build();
    requireResponseBudget(response);
    return response;
  }

  public static AccountStartSessionWorldParticipationSettlementEvidence fromResponse(
      AccountStartSessionWorldParticipationSettlementInput input,
      SettleOriginalStartSessionWorldParticipationResponse response) {
    Objects.requireNonNull(input, "World participation settlement input required");
    Objects.requireNonNull(response, "World participation settlement response required");
    requireClosed(response, "response");
    requireResponseBudget(response);
    if (response.getSchemaVersionCount() != 1
        || response.getSettlementRequestIdCount() != 1
        || response.getAccountWorldParticipationIdCount() != 1
        || response.getAccountWorldParticipationFenceCount() != 1
        || response.getSettledAtCount() != 1
        || response.getTerminalEvidenceCanonicalBytesCount() != 1
        || response.getSchemaVersion(0) != SCHEMA_VERSION
        || !input.settlementRequestId().toString().equals(response.getSettlementRequestId(0))
        || !input
            .accountWorldParticipationId()
            .toString()
            .equals(response.getAccountWorldParticipationId(0))
        || !Long.toString(input.accountWorldParticipationFence())
            .equals(response.getAccountWorldParticipationFence(0))) {
      throw invalid("World participation settlement response identity differs from its request");
    }
    Instant settledAt = canonicalInstant(response.getSettledAt(0));
    byte[] terminalBytes = response.getTerminalEvidenceCanonicalBytes(0).toByteArray();
    if (terminalBytes.length == 0
        || terminalBytes.length > WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES) {
      throw invalid("Complete World terminal is missing or oversized");
    }
    WorldStartSessionExecutionTerminal terminal =
        WorldStartSessionExecutionTerminal.fromStored(terminalBytes);
    if (!Arrays.equals(terminalBytes, terminal.canonicalBytes())
        || !input.accountWorldParticipationId().equals(terminal.accountWorldParticipationId())
        || input.accountWorldParticipationFence() != terminal.accountWorldParticipationFence()
        || !input.targetNamespace().equals(terminal.targetNamespace())) {
      throw invalid("Full World terminal differs from the exact Account participation");
    }
    var evidence =
        new AccountStartSessionWorldParticipationSettlementEvidence(
            input.settlementRequestId(), terminal, settledAt);
    if (!toResponse(input, evidence).equals(response)) {
      throw invalid("World participation settlement response is not its exact closed encoding");
    }
    return evidence;
  }

  public static AccountStartSessionWorldParticipationSettlementEvidence fromResponseWireBytes(
      AccountStartSessionWorldParticipationSettlementInput input, byte[] wireBytes) {
    if (wireBytes == null || wireBytes.length == 0 || wireBytes.length > MAX_RESPONSE_WIRE_BYTES) {
      throw invalid("World participation settlement response exceeds its wire budget");
    }
    try {
      var response = SettleOriginalStartSessionWorldParticipationResponse.parseFrom(wireBytes);
      var evidence = fromResponse(input, response);
      if (!Arrays.equals(wireBytes, toResponse(input, evidence).toByteArray())) {
        throw invalid("World participation settlement response wire bytes are not canonical");
      }
      return evidence;
    } catch (InvalidProtocolBufferException malformed) {
      throw invalid("World participation settlement response wire bytes are malformed", malformed);
    }
  }

  public static AccountStartSessionWorldParticipationSettlementInput fromRequestWireBytes(
      byte[] wireBytes, String expectedNamespace) {
    if (wireBytes == null || wireBytes.length == 0 || wireBytes.length > MAX_REQUEST_WIRE_BYTES) {
      throw invalid("World participation settlement request exceeds its wire budget");
    }
    try {
      var request = SettleOriginalStartSessionWorldParticipationRequest.parseFrom(wireBytes);
      var input = fromRequest(request, expectedNamespace);
      if (!Arrays.equals(wireBytes, toRequest(input).toByteArray())) {
        throw invalid("World participation settlement request wire bytes are not canonical");
      }
      return input;
    } catch (InvalidProtocolBufferException malformed) {
      throw invalid("World participation settlement request wire bytes are malformed", malformed);
    }
  }

  private static void requireRequestBudget(
      SettleOriginalStartSessionWorldParticipationRequest request) {
    if (request.getSerializedSize() <= 0 || request.getSerializedSize() > MAX_REQUEST_WIRE_BYTES) {
      throw invalid("World participation settlement request exceeds its wire budget");
    }
  }

  private static void requireResponseBudget(
      SettleOriginalStartSessionWorldParticipationResponse response) {
    if (response.getSerializedSize() <= 0
        || response.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES
        || response.getTerminalEvidenceCanonicalBytesCount() != 1
        || response.getTerminalEvidenceCanonicalBytes(0).isEmpty()
        || response.getTerminalEvidenceCanonicalBytes(0).size()
            > WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES) {
      throw invalid("Complete World terminal is missing or exceeds its wire budget");
    }
  }

  private static void requireClosed(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw invalid("World participation settlement " + label + " contains unsupported fields");
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
      throw invalid("Account World participation fence must be positive canonical decimal text");
    }
    try {
      long value = Long.parseLong(text);
      if (value <= 0L || !Long.toString(value).equals(text)) {
        throw invalid("Account World participation fence must fit a positive signed long");
      }
      return value;
    } catch (NumberFormatException malformed) {
      throw invalid("Account World participation fence must fit a positive signed long", malformed);
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
    return new IllegalArgumentException(
        "Original StartSession World participation settlement " + message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(
        "Original StartSession World participation settlement " + message, cause);
  }
}
