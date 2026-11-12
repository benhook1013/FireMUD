package net.firedevops.firemud.common.account.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Any;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Result;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.State;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Failure;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;

class TenantCapacityAdmissionGrpcCodecTest {
  @Test
  void onlyCanonicalFailureDetailsSurviveWithoutChangingTransportStatus() {
    for (var failure : Failure.values()) {
      if (failure.isDomainRefusal()) {
        assertThatThrownBy(failure::toStatusException).isInstanceOf(IllegalArgumentException.class);
        var oldTransport =
            StatusProto.toStatusRuntimeException(
                com.google.rpc.Status.newBuilder()
                    .setCode(Status.Code.RESOURCE_EXHAUSTED.value())
                    .setMessage("Tenant capacity commitment did not complete")
                    .addDetails(
                        Any.pack(
                            ErrorDetail.newBuilder()
                                .setCode(failure.name())
                                .setMessage("Tenant capacity commitment did not complete")
                                .build()))
                    .build());
        var verified = TenantCapacityAdmissionGrpcCodec.validateFailure(oldTransport);
        assertThat(verified.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThat(StatusProto.fromThrowable(verified).getDetailsList()).isEmpty();
      } else {
        var canonical = failure.toStatusException();
        assertThat(TenantCapacityAdmissionGrpcCodec.validateFailure(canonical)).isSameAs(canonical);
      }
    }
    var deadline = Status.DEADLINE_EXCEEDED.asRuntimeException();
    assertThat(TenantCapacityAdmissionGrpcCodec.validateFailure(deadline)).isSameAs(deadline);
    var claimedFence =
        StatusProto.toStatusRuntimeException(
            com.google.rpc.Status.newBuilder()
                .setCode(Status.Code.UNAVAILABLE.value())
                .addDetails(Any.pack(ErrorDetail.newBuilder().setCode("FENCE_REJECTED").build()))
                .build());
    var verified = TenantCapacityAdmissionGrpcCodec.validateFailure(claimedFence);
    assertThat(verified.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(StatusProto.fromThrowable(verified).getDetailsList()).isEmpty();
  }

  @Test
  void domainRefusalsRequireTheExactOriginalAndExclusiveResponseAlternative() {
    Request request = CapacityAdmissionTestFixture.playerRequest(false);
    var commitment = TenantCapacityAdmissionGrpcCodec.toResponse(request, result(request));
    assertThat(TenantCapacityAdmissionGrpcCodec.readRefusal(request, commitment)).isEmpty();
    for (var code : List.of(Failure.TENANT_BILLING_BLOCKED, Failure.CAPACITY_QUOTA_EXCEEDED)) {
      var response = TenantCapacityAdmissionGrpcCodec.toRefusal(request, code);
      assertThat(TenantCapacityAdmissionGrpcCodec.readRefusal(request, response)).contains(code);
      assertThatThrownBy(() -> TenantCapacityAdmissionGrpcCodec.requireResponse(request, response))
          .isInstanceOf(IllegalArgumentException.class);
      for (var malformed :
          List.of(
              response.toBuilder().clearRefusal().build(),
              response.toBuilder().addRefusal(response.getRefusal(0)).build(),
              response.toBuilder().addCommitment(commitment.getCommitment(0)).build(),
              response.toBuilder()
                  .setRefusal(0, response.getRefusal(0).toBuilder().setCapacityRearmFence(0, 2))
                  .build(),
              response.toBuilder()
                  .setRefusal(0, response.getRefusal(0).toBuilder().setCodeValue(0, 999))
                  .build())) {
        assertThatThrownBy(() -> TenantCapacityAdmissionGrpcCodec.readRefusal(request, malformed))
            .isInstanceOf(IllegalArgumentException.class);
      }
    }
  }

  @Test
  void exactBothAlternativesRoundTripWithCompleteBundleAndReservationBinding() {
    for (Request request :
        List.of(
            CapacityAdmissionTestFixture.playerRequest(),
            CapacityAdmissionTestFixture.worldRequest(17L))) {
      var wire = TenantCapacityAdmissionGrpcCodec.toRequest(request);
      assertThat(wire.hasCapacityDelta()).isTrue();
      assertThat(TenantCapacityAdmissionGrpcCodec.parseRequest(wire)).isEqualTo(request);
      Result result = result(request);
      assertThat(
              TenantCapacityAdmissionGrpcCodec.requireResponse(
                  request, TenantCapacityAdmissionGrpcCodec.toResponse(request, result)))
          .isEqualTo(result);
    }
  }

  @Test
  void nonCreatingEvidenceRoundTripsWithoutChangingAnyCanonicalBytes() {
    Request request = CapacityAdmissionTestFixture.playerRequest(false);
    var wire = TenantCapacityAdmissionGrpcCodec.toRequest(request);
    var parsed = TenantCapacityAdmissionGrpcCodec.parseRequest(wire);
    assertThat(wire.getAttempt(0).getCanonicalAccountEvidence(0).toByteArray())
        .containsExactly(request.evidence().canonicalBytes());
    assertThat(parsed).isEqualTo(request);
    assertThat(parsed.evidence().canonicalBytes())
        .containsExactly(request.evidence().canonicalBytes());
    assertThat(parsed.evidence().permitsCreation()).isFalse();
    assertThat(TenantCapacityAdmissionGrpcCodec.toRequest(parsed).toByteArray())
        .containsExactly(wire.toByteArray());
    parsed.requireStructurallyValid();
    assertThatThrownBy(parsed::requireCommittable).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void absentNegativeDuplicateUnknownAndMixedCallerContextFailBeforeCommit() {
    Request request = CapacityAdmissionTestFixture.playerRequest();
    var wire = TenantCapacityAdmissionGrpcCodec.toRequest(request);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(999, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    var world =
        TenantCapacityAdmissionGrpcCodec.toRequest(CapacityAdmissionTestFixture.worldRequest(17L));
    for (var bad :
        List.of(
            wire.toBuilder().clearCapacityDelta().build(),
            wire.toBuilder().setCapacityDelta(-1).build(),
            wire.toBuilder().addAttempt(wire.getAttempt(0)).build(),
            wire.toBuilder().setUnknownFields(unknown).build(),
            wire.toBuilder()
                .setAttempt(
                    0,
                    wire.getAttempt(0).toBuilder()
                        .addWorldContext(world.getAttempt(0).getWorldContext(0)))
                .build(),
            wire.toBuilder()
                .setAttempt(0, wire.getAttempt(0).toBuilder().addCapacityRearmFence(1))
                .build(),
            wire.toBuilder()
                .setAttempt(
                    0,
                    wire.getAttempt(0).toBuilder()
                        .setPlayerContext(
                            0,
                            wire.getAttempt(0).getPlayerContext(0).toBuilder()
                                .addSessionId(
                                    wire.getAttempt(0).getPlayerContext(0).getSessionId(0))))
                .build(),
            wire.toBuilder()
                .setAttempt(
                    0,
                    wire.getAttempt(0).toBuilder()
                        .setPlayerContext(
                            0,
                            wire.getAttempt(0).getPlayerContext(0).toBuilder()
                                .setUnknownFields(unknown)))
                .build())) {
      assertThatThrownBy(() -> TenantCapacityAdmissionGrpcCodec.parseRequest(bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void presentZeroSurvivesOnlyExplicitNonExpandingClassification() {
    Request base = CapacityAdmissionTestFixture.worldRequest(0L);
    Request zero =
        new Request(
                base.identity(),
                base.admissionAttemptId(),
                base.capacityRearmFence(),
                base.targetNamespace(),
                base.context(),
                TenantCapacityAdmission.Operation.NON_EXPANDING_RECOVERY,
                new TenantCapacityAdmission.Shape(0, 1, 0),
                new TenantCapacityAdmission.Shape(0, 1, 0),
                base.evidence(),
                0L,
                null)
            .withDigest();
    var wire = TenantCapacityAdmissionGrpcCodec.toRequest(zero);
    assertThat(wire.hasCapacityDelta()).isTrue();
    assertThat(TenantCapacityAdmissionGrpcCodec.parseRequest(wire).capacityDelta()).isZero();
    assertThatThrownBy(() -> TenantCapacityAdmissionGrpcCodec.toRequest(base))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void responseCannotSubstituteAttemptDigestFenceOrEvidenceIdentity() {
    var request = CapacityAdmissionTestFixture.playerRequest();
    var wire = TenantCapacityAdmissionGrpcCodec.toResponse(request, result(request));
    for (var bad :
        List.of(
            wire.toBuilder().addCommitment(wire.getCommitment(0)).build(),
            wire.toBuilder()
                .setCommitment(0, wire.getCommitment(0).toBuilder().setCapacityRearmFence(0, 2))
                .build(),
            wire.toBuilder()
                .setCommitment(
                    0, wire.getCommitment(0).toBuilder().setMutationDigest(0, "b".repeat(64)))
                .build(),
            wire.toBuilder()
                .setCommitment(
                    0,
                    wire.getCommitment(0).toBuilder()
                        .setEvaluationIdentity(0, "sha256:" + "b".repeat(64)))
                .build(),
            wire.toBuilder()
                .setCommitment(
                    0,
                    wire.getCommitment(0).toBuilder()
                        .setExpiresAt(0, request.evidence().expiresAt().plusSeconds(1).toString()))
                .build())) {
      assertThatThrownBy(() -> TenantCapacityAdmissionGrpcCodec.requireResponse(request, bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void malformedAndOutOfRangeResponseExpiryUseTheInvalidResponseException() {
    var request = CapacityAdmissionTestFixture.playerRequest();
    var wire = TenantCapacityAdmissionGrpcCodec.toResponse(request, result(request));
    for (String expires :
        List.of("not-an-instant", "2026-99-02T00:00:00Z", "+1000000001-01-01T00:00:00Z")) {
      var malformed =
          wire.toBuilder()
              .setCommitment(0, wire.getCommitment(0).toBuilder().setExpiresAt(0, expires))
              .build();
      assertThatThrownBy(() -> TenantCapacityAdmissionGrpcCodec.requireResponse(request, malformed))
          .isExactlyInstanceOf(IllegalArgumentException.class);
    }
  }

  private static Result result(Request request) {
    return new Result(
        request.identity(),
        request.admissionAttemptId(),
        request.capacityRearmFence(),
        request.mutationDigest(),
        UUID.fromString("b2222222-2222-4222-8222-222222222222"),
        1,
        CapacityAdmissionTestFixture.NOW.plusSeconds(15),
        State.RESERVED,
        request.evidence().snapshotIdentity(),
        request.evidence().evaluationIdentity());
  }
}
