package net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import com.google.protobuf.UnknownFieldSet;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import org.junit.jupiter.api.Test;

class OriginalStartSessionCurrentAttemptEvidenceGrpcCodecTest {
  private static final UUID READ_ID = uuid("01111111-1111-4111-8111-111111111111");
  private static final UUID ATTEMPT = uuid("02222222-2222-4222-8222-222222222222");
  private static final UUID MUTATION = uuid("03333333-3333-4333-8333-333333333333");

  @Test
  void roundTripsExactRequestPendingPhaseProjectionAndOriginalExpiry() {
    Request request = request();
    byte[] projection = request.originalTuple().canonicalBytes();
    Result result =
        new Result(request, Instant.parse("2030-01-01T00:00:00.123456789Z"), projection);
    var wireRequest = OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toRequest(request);
    var wireResponse = OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(result);

    assertThat(OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromRequest(wireRequest))
        .isEqualTo(request);
    var decoded =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(request, wireResponse);
    assertThat(decoded.request()).isEqualTo(request);
    assertThat(decoded.phaseState()).isEqualTo("OWNER_EXECUTION_PENDING");
    assertThat(decoded.originalLeaseExpiresAt()).isEqualTo(result.originalLeaseExpiresAt());
    assertThat(decoded.accountRedemptionProjection()).containsExactly(projection);
    assertThat(wireResponse.getAccountRedemptionProjectionDigest())
        .isEqualTo(result.accountRedemptionProjectionDigest());
  }

  @Test
  void rejectsUnknownFieldsUnsupportedSchemaInvalidFenceAndChangedEcho() {
    Request request = request();
    var wire = OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toRequest(request);
    var unknownRequest =
        wire.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertThatThrownBy(
            () -> OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromRequest(unknownRequest))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromRequest(
                    wire.toBuilder().setSchemaVersion(2).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromRequest(
                    wire.toBuilder().setExpectedOwnerFence(0L).build()))
        .isInstanceOf(IllegalArgumentException.class);

    var response =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(
            new Result(
                request,
                Instant.parse("2030-01-01T00:00:00Z"),
                request.originalTuple().canonicalBytes()));
    var changedEcho =
        response.toBuilder()
            .setRequest(
                wire.toBuilder()
                    .setReadRequestId(uuid("04444444-4444-4444-8444-444444444444").toString()))
            .build();
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request, changedEcho))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact read request");
  }

  @Test
  void rejectsWrongPhaseDigestResponseAndTimestampShapes() {
    Request request = request();
    var valid =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(
            new Result(
                request,
                Instant.parse("2030-01-01T00:00:00Z"),
                request.originalTuple().canonicalBytes()));
    var unknownResponse =
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request, unknownResponse))
        .isInstanceOf(IllegalArgumentException.class);
    var unknownTimestamp =
        valid.toBuilder()
            .setOriginalLeaseExpiresAt(
                valid.getOriginalLeaseExpiresAt().toBuilder()
                    .setUnknownFields(
                        UnknownFieldSet.newBuilder()
                            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                            .build())
                    .build())
            .build();
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request, unknownTimestamp))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request, valid.toBuilder().setPhaseState("OWNER_EXECUTION_COMMITTED").build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request,
                    valid.toBuilder().setAccountRedemptionProjectionDigest("0".repeat(64)).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest");
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request,
                    valid.toBuilder()
                        .setOriginalLeaseExpiresAt(
                            Timestamp.newBuilder().setSeconds(253_402_300_800L).setNanos(0).build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timestamp");
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request,
                    valid.toBuilder()
                        .setOriginalLeaseExpiresAt(
                            Timestamp.newBuilder()
                                .setSeconds(Instant.parse("2030-01-01T00:00:00Z").getEpochSecond())
                                .setNanos(1_000_000_000)
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request, valid.toBuilder().clearOriginalLeaseExpiresAt().build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Request request() {
    return new Request(
        READ_ID,
        "world-runtime",
        CurrentAttemptTestData.tuple().canonicalBytes(),
        ATTEMPT,
        MUTATION,
        8L);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
