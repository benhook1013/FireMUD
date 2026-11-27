package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AccountIssuerAuthoritySourceSnapshot;
import org.junit.jupiter.api.Test;

class AccountIssuerSourceSnapshotGrpcCodecTest {
  private static final String NAMESPACE = "test";
  private static final String CALLER = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String ISSUER = AccountIssuerSourceSnapshotEvidence.ISSUER_ID;
  private static final String STREAM = AccountIssuerSourceSnapshotEvidence.ISSUER_STREAM_KEY;

  @Test
  void initialRequestAndZeroCheckpointResponseRoundTripWithoutEventPresence() {
    UUID operation = UUID.randomUUID();
    var request = request(operation, Optional.empty());
    var wireRequest = AccountIssuerSourceSnapshotGrpcCodec.toRequest(request, NAMESPACE, CALLER);
    var parsedRequest =
        AccountIssuerSourceSnapshotGrpcCodec.fromRequest(wireRequest, NAMESPACE, CALLER);
    var source = snapshot(operation, "1", "1", "0", Optional.empty());
    var wireResponse =
        AccountIssuerSourceSnapshotGrpcCodec.toResponse(source, parsedRequest, CALLER);
    var parsedResponse =
        AccountIssuerSourceSnapshotGrpcCodec.fromResponse(wireResponse, parsedRequest, CALLER);

    assertThat(parsedRequest).isEqualTo(request);
    assertThat(wireResponse.hasSourceEvent()).isFalse();
    assertThat(parsedResponse).isEqualTo(source);
  }

  @Test
  void expectedSourceRevalidationAcceptsOnlyTheWholeExactCarrier() {
    UUID operation = UUID.randomUUID();
    var captured = advanced(operation, "1", "2");
    var request = request(operation, Optional.of(captured));
    var wireRequest = AccountIssuerSourceSnapshotGrpcCodec.toRequest(request, NAMESPACE, CALLER);
    var parsedRequest =
        AccountIssuerSourceSnapshotGrpcCodec.fromRequest(wireRequest, NAMESPACE, CALLER);

    assertThat(parsedRequest.expectedSource()).contains(captured);
    assertThat(
            AccountIssuerSourceSnapshotGrpcCodec.fromResponse(
                AccountIssuerSourceSnapshotGrpcCodec.toResponse(captured, parsedRequest, CALLER),
                parsedRequest,
                CALLER))
        .isEqualTo(captured);

    var changedCheckpoint = advanced(operation, "2", "3");
    assertThatThrownBy(
            () ->
                AccountIssuerSourceSnapshotGrpcCodec.fromResponse(
                    AccountIssuerSourceSnapshotGrpcCodec.toWire(changedCheckpoint),
                    parsedRequest,
                    CALLER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact expected snapshot");

    var changedEvent =
        snapshot(
            operation,
            "2",
            "2",
            "1",
            Optional.of(
                sealedIssuerEvent(
                    "different-retained-event", "different-retained-event", "1", "2")));
    assertThatThrownBy(
            () ->
                AccountIssuerSourceSnapshotGrpcCodec.fromResponse(
                    AccountIssuerSourceSnapshotGrpcCodec.toWire(changedEvent),
                    parsedRequest,
                    CALLER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact expected snapshot");
  }

  @Test
  void rejectsOperationCallerNamespaceAndExpectedSnapshotScopeChanges() {
    UUID operation = UUID.randomUUID();
    var initial = snapshot(operation, "1", "1", "0", Optional.empty());
    var request = request(operation, Optional.empty());
    var otherOperation = request(UUID.randomUUID(), Optional.empty());
    var wireInitial = AccountIssuerSourceSnapshotGrpcCodec.toWire(initial);

    assertThatThrownBy(
            () ->
                AccountIssuerSourceSnapshotGrpcCodec.fromResponse(
                    wireInitial, otherOperation, CALLER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match the request scope");
    assertThatThrownBy(
            () ->
                AccountIssuerSourceSnapshotGrpcCodec.fromResponse(
                    wireInitial, request, "spiffe://firemud/ns/other/sa/game-session-service"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("same-namespace");
    assertThatThrownBy(
            () ->
                AccountIssuerSourceSnapshotGrpcCodec.toRequest(
                    request, "other", "spiffe://firemud/ns/other/sa/game-session-service"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configured namespace");
    assertThatThrownBy(
            () ->
                request(
                    operation,
                    Optional.of(snapshot(UUID.randomUUID(), "1", "1", "0", Optional.empty()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match the request scope");
  }

  @Test
  void rejectsUnknownFieldsAtRequestSnapshotAndNestedExpectedSnapshotBoundaries() {
    UUID operation = UUID.randomUUID();
    var request = request(operation, Optional.empty());
    var wireRequest = AccountIssuerSourceSnapshotGrpcCodec.toRequest(request, NAMESPACE, CALLER);
    var unknownRequest = wireRequest.toBuilder().setUnknownFields(oneUnknownField()).build();
    assertThatThrownBy(
            () ->
                AccountIssuerSourceSnapshotGrpcCodec.fromRequest(unknownRequest, NAMESPACE, CALLER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown protobuf fields");

    var source = snapshot(operation, "1", "1", "0", Optional.empty());
    var wireSource = AccountIssuerSourceSnapshotGrpcCodec.toWire(source);
    var unknownSnapshot = wireSource.toBuilder().setUnknownFields(oneUnknownField()).build();
    assertThatThrownBy(() -> AccountIssuerSourceSnapshotGrpcCodec.fromWire(unknownSnapshot))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown protobuf fields");

    var expectedRequest = request(operation, Optional.of(source));
    var nestedUnknown = wireSource.toBuilder().setUnknownFields(oneUnknownField()).build();
    var outerWithNestedUnknown =
        AccountIssuerSourceSnapshotGrpcCodec.toRequest(expectedRequest, NAMESPACE, CALLER)
            .toBuilder()
            .setExpectedSource(nestedUnknown)
            .build();
    assertThatThrownBy(
            () ->
                AccountIssuerSourceSnapshotGrpcCodec.fromRequest(
                    outerWithNestedUnknown, NAMESPACE, CALLER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown protobuf fields");
  }

  @Test
  void rejectsMalformedUtf8UnknownEventFieldsAndOversizedSourcePayload() {
    UUID operation = UUID.randomUUID();
    var malformedUtf8 =
        advancedWire(operation).toBuilder()
            .setSourceEvent(ByteString.copyFrom(new byte[] {(byte) 0xc3, 0x28}))
            .build();
    assertThatThrownBy(() -> AccountIssuerSourceSnapshotGrpcCodec.fromWire(malformedUtf8))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("malformed UTF-8");

    String event = sealedIssuerEvent(operation, "1", "2");
    String unknownJson = event.substring(0, event.length() - 1) + ",\"z\":true}";
    var unknownEvent =
        advancedWire(operation).toBuilder()
            .setSourceEvent(ByteString.copyFromUtf8(unknownJson))
            .build();
    assertThatThrownBy(() -> AccountIssuerSourceSnapshotGrpcCodec.fromWire(unknownEvent))
        .isInstanceOf(IllegalArgumentException.class);

    var oversized =
        advancedWire(operation).toBuilder()
            .setSourceEvent(ByteString.copyFrom(new byte[64 * 1024 + 1]))
            .build();
    assertThatThrownBy(() -> AccountIssuerSourceSnapshotGrpcCodec.fromWire(oversized))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("64 KiB");
  }

  @Test
  void roundTripsLargeCountersAsExactDecimalStrings() {
    UUID operation = UUID.randomUUID();
    String sequence = "9007199254740992";
    String generation = new BigInteger(sequence).add(BigInteger.ONE).toString();
    var source = advanced(operation, sequence, generation);

    assertThat(
            AccountIssuerSourceSnapshotGrpcCodec.fromWire(
                AccountIssuerSourceSnapshotGrpcCodec.toWire(source)))
        .isEqualTo(source);
  }

  @Test
  void rejectsAbsentOrChangedSourceOnPositiveSequenceAndCounterDrift() {
    UUID operation = UUID.randomUUID();
    var valid = advanced(operation, "1", "2");
    var absent =
        AccountIssuerAuthoritySourceSnapshot.newBuilder()
            .setSchemaVersion(1)
            .setTargetNamespace(NAMESPACE)
            .setCallerWorkload(CALLER)
            .setReconciliationOperationId(operation.toString())
            .setIssuerId(ISSUER)
            .setIssuerAuthGeneration("2")
            .setSourceVersion("2")
            .setOutboxStreamKey(STREAM)
            .setLastCommittedOutboxSequence("1")
            .build();
    assertThatThrownBy(() -> AccountIssuerSourceSnapshotGrpcCodec.fromWire(absent))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires sourceEvent");

    var wrongSequence =
        AccountIssuerSourceSnapshotGrpcCodec.toWire(valid).toBuilder()
            .setLastCommittedOutboxSequence("2")
            .build();
    assertThatThrownBy(() -> AccountIssuerSourceSnapshotGrpcCodec.fromWire(wrongSequence))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static UnknownFieldSet oneUnknownField() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static AccountIssuerSourceSnapshotGrpcCodec.ReadRequest request(
      UUID operation, Optional<AccountIssuerSourceSnapshotEvidence> expected) {
    return new AccountIssuerSourceSnapshotGrpcCodec.ReadRequest(
        operation, NAMESPACE, ISSUER, expected);
  }

  private static AccountIssuerSourceSnapshotEvidence snapshot(
      UUID operation, String generation, String version, String sequence, Optional<String> event) {
    return new AccountIssuerSourceSnapshotEvidence(
        operation, NAMESPACE, CALLER, ISSUER, generation, version, STREAM, sequence, event);
  }

  private static AccountIssuerSourceSnapshotEvidence advanced(
      UUID operation, String sequence, String generation) {
    return snapshot(
        operation,
        generation,
        generation,
        sequence,
        Optional.of(sealedIssuerEvent(operation, sequence, generation)));
  }

  private static AccountIssuerAuthoritySourceSnapshot advancedWire(UUID operation) {
    return AccountIssuerSourceSnapshotGrpcCodec.toWire(advanced(operation, "1", "2"));
  }

  private static String sealedIssuerEvent(UUID operation, String sequence, String generation) {
    return sealedIssuerEvent(operation.toString(), operation.toString(), sequence, generation);
  }

  private static String sealedIssuerEvent(
      String eventId, String requestId, String sequence, String generation) {
    return AccountAuthoritySourceEventV1Codec.sealIssuer(
            new AccountAuthoritySourceEventV1Codec.IssuerPreimage(
                eventId,
                requestId,
                STREAM,
                sequence,
                ISSUER,
                generation,
                generation,
                "SIGNER_COMPROMISE"))
        .canonicalJson();
  }
}
