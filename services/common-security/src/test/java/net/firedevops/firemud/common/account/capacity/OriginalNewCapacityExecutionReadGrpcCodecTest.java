package net.firedevops.firemud.common.account.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Controlled exact content; actual Account/Game Session composition proves source provenance. */
class OriginalNewCapacityExecutionReadGrpcCodecTest {
  @Test
  void exactTerminalEnvelopeRetainsOriginalFenceAndSeparatelyFreshOwnerObservation() {
    var selector = selector();
    var execution = execution(selector);
    for (String disposition : List.of("COMMITTED", "NOT_EXECUTED_AFTER_AUTHORIZATION")) {
      String physical = "sha256:" + "b".repeat(64), receipt = "sha256:" + "c".repeat(64);
      var proof =
          new OriginalNewCapacityExecutionReadGrpcCodec.Readback(
              execution,
              disposition,
              physical,
              receipt,
              execution.terminalEvidence(disposition, physical, receipt),
              "replacement-owner",
              8L);
      var decoded =
          OriginalNewCapacityExecutionReadGrpcCodec.fromResponse(
              selector, OriginalNewCapacityExecutionReadGrpcCodec.toResponse(selector, proof));
      assertThat(decoded.execution().selectedOwnerId()).isEqualTo("original-owner");
      assertThat(decoded.execution().selectedOwnerFence()).isEqualTo(7L);
      assertThat(decoded.currentOwnerId()).isEqualTo("replacement-owner");
      assertThat(decoded.currentOwnerFence()).isEqualTo(8L);
      assertThat(decoded.canonicalReadback()).containsExactly(proof.canonicalReadback());
      assertThat(decoded.execution().canonicalBytes()).containsExactly(execution.canonicalBytes());
    }
  }

  @Test
  void duplicateUnknownChangedReservationPayloadDigestAndOriginalFenceFailClosed() {
    var selector = selector();
    var execution = execution(selector);
    String digest = "sha256:" + "b".repeat(64);
    var proof =
        new OriginalNewCapacityExecutionReadGrpcCodec.Readback(
            execution,
            "COMMITTED",
            digest,
            digest,
            execution.terminalEvidence("COMMITTED", digest, digest),
            "original-owner",
            7L);
    var wire = OriginalNewCapacityExecutionReadGrpcCodec.toResponse(selector, proof);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(999, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    for (var malformed :
        List.of(
            wire.toBuilder().addSelectedOwnerFence(7).build(),
            wire.toBuilder().setUnknownFields(unknown).build(),
            wire.toBuilder().setSelectedOwnerFence(0, 8).build(),
            wire.toBuilder().setCurrentOwnerFence(0, 6).build(),
            wire.toBuilder().setCurrentOwnerId(0, "different-owner-at-same-fence").build(),
            wire.toBuilder().setCanonicalExecution(0, ByteString.copyFromUtf8("changed")).build(),
            wire.toBuilder().setCanonicalReadback(0, ByteString.copyFromUtf8("true")).build(),
            wire.toBuilder().setPhysicalDigest(0, "sha256:" + "d".repeat(64)).build(),
            wire.toBuilder().setDisposition(0, "FAILED").build())) {
      assertThatThrownBy(
              () -> OriginalNewCapacityExecutionReadGrpcCodec.fromResponse(selector, malformed))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var request = OriginalNewCapacityExecutionReadGrpcCodec.toRequest(selector);
    assertThatThrownBy(
            () ->
                OriginalNewCapacityExecutionReadGrpcCodec.parseRequest(
                    request.toBuilder().addReservation(request.getReservation(0)).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                OriginalNewCapacityExecutionReadGrpcCodec.parseRequest(
                    request.toBuilder()
                        .setAttempt(0, request.getAttempt(0).toBuilder().setUnknownFields(unknown))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static OriginalNewCapacityExecutionReadGrpcCodec.Selector selector() {
    var request = CapacityAdmissionTestFixture.playerRequest();
    var reservation =
        new TenantCapacityAdmission.Result(
            request.identity(),
            request.admissionAttemptId(),
            request.capacityRearmFence(),
            request.mutationDigest(),
            UUID.fromString("b3333333-3333-4333-8333-333333333333"),
            1,
            CapacityAdmissionTestFixture.NOW.plusSeconds(15),
            TenantCapacityAdmission.State.RESERVED,
            request.evidence().snapshotIdentity(),
            request.evidence().evaluationIdentity());
    return new OriginalNewCapacityExecutionReadGrpcCodec.Selector(request, reservation);
  }

  private static OriginalNewCapacityExecution execution(
      OriginalNewCapacityExecutionReadGrpcCodec.Selector selector) {
    return new OriginalNewCapacityExecution(
        selector.request(),
        selector.reservation(),
        selector.request().identity().admissionId(),
        "sha256:" + "a".repeat(64),
        "original-owner",
        7L);
  }
}
