package unit.net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import com.google.protobuf.UnknownFieldSet;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import org.junit.jupiter.api.Test;

class OriginalStartSessionCurrentAttemptEvidenceGrpcCodecTest {
  private static final UUID READ_ID = UUID.fromString("01111111-1111-4111-8111-111111111111");
  private static final UUID ATTEMPT = UUID.fromString("02222222-2222-4222-8222-222222222222");
  private static final UUID MUTATION = UUID.fromString("03333333-3333-4333-8333-333333333333");

  @Test
  void roundTripsExactRequestAndProjectionWithPendingPhaseAndOriginalExpiry() {
    Request request =
        new Request(
            READ_ID,
            "world-runtime",
            CurrentAttemptTestData.tuple().canonicalBytes(),
            ATTEMPT,
            MUTATION,
            8L);
    byte[] projection = request.canonicalPostAuthorizationTuple();
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
  void rejectsUnknownFieldsChangedEchoPhaseProjectionDigestAndMalformedSelectors() {
    Request request =
        new Request(
            READ_ID,
            "world-runtime",
            CurrentAttemptTestData.tuple().canonicalBytes(),
            ATTEMPT,
            MUTATION,
            8L);
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

    var response =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(
            new Result(
                request,
                Instant.parse("2030-01-01T00:00:00Z"),
                request.canonicalPostAuthorizationTuple()));
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request, response.toBuilder().setPhaseState("COMMITTED").build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setAccountRedemptionProjectionDigest("0".repeat(64))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    var unknownTimestamp =
        response.toBuilder()
            .setOriginalLeaseExpiresAt(
                response.getOriginalLeaseExpiresAt().toBuilder()
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
                    new Request(
                        UUID.fromString("04444444-4444-4444-8444-444444444444"),
                        "world-runtime",
                        request.canonicalPostAuthorizationTuple(),
                        ATTEMPT,
                        MUTATION,
                        8L),
                    response))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromRequest(
                    wire.toBuilder().setExpectedOwnerFence(0L).build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void roundTripsGoogleTimestampSecondBoundariesExactly() {
    Request request = request();
    byte[] projection = request.canonicalPostAuthorizationTuple();
    Instant[] boundaries = {
      Instant.ofEpochSecond(-62_135_596_800L), Instant.ofEpochSecond(253_402_300_799L, 999_999_999)
    };

    for (Instant boundary : boundaries) {
      var response =
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(
              new Result(request, boundary, projection));
      assertThat(
              OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(request, response)
                  .originalLeaseExpiresAt())
          .isEqualTo(boundary);
    }
  }

  @Test
  void rejectsGoogleTimestampSecondsOutsideRangeOnEncodingAndDecoding() {
    Request request = request();
    byte[] projection = request.canonicalPostAuthorizationTuple();
    for (Instant outside :
        new Instant[] {
          Instant.ofEpochSecond(-62_135_596_801L), Instant.ofEpochSecond(253_402_300_800L)
        }) {
      assertThatThrownBy(
              () ->
                  OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(
                      new Result(request, outside, projection)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    var validResponse =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(
            new Result(request, Instant.parse("2030-01-01T00:00:00Z"), projection));
    for (long outsideSeconds : new long[] {-62_135_596_801L, 253_402_300_800L}) {
      var response =
          validResponse.toBuilder()
              .setOriginalLeaseExpiresAt(
                  Timestamp.newBuilder().setSeconds(outsideSeconds).setNanos(0).build())
              .build();
      assertThatThrownBy(
              () ->
                  OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                      request, response))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsInvalidGoogleTimestampNanosOnDecoding() {
    Request request = request();
    var validResponse =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(
            new Result(
                request,
                Instant.parse("2030-01-01T00:00:00Z"),
                request.canonicalPostAuthorizationTuple()));

    for (int invalidNanos : new int[] {-1, 1_000_000_000}) {
      var response =
          validResponse.toBuilder()
              .setOriginalLeaseExpiresAt(
                  Timestamp.newBuilder()
                      .setSeconds(Instant.parse("2030-01-01T00:00:00Z").getEpochSecond())
                      .setNanos(invalidNanos)
                      .build())
              .build();
      assertThatThrownBy(
              () ->
                  OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                      request, response))
          .isInstanceOf(IllegalArgumentException.class);
    }
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
}
