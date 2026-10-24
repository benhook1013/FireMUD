package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import org.junit.jupiter.api.Test;

/** Pure carrier integrity; these comparison values are not authenticated recovery evidence. */
class CanonicalGameplayRosterRecoveryEvidenceTest {
  @Test
  void createdRecoveryReturnsOnlyItsDistinctRosterAndKeepsExactOwnerFenceAndMarker()
      throws Exception {
    var fixture = CanonicalGameplayRosterReferenceEvidenceTest.createdFixture();
    var request = request(fixture.original());
    var response =
        CanonicalGameplayRosterRecoveryEvidence.playerCreatedResponse(
            request, UUID.randomUUID(), 1L, UUID.randomUUID(), fixture.roster());
    assertThat(response.getCanonicalRosterReceipt()).isEmpty();
    assertThat(response.getCanonicalPlayerCreatedRosterReceipt())
        .isEqualTo(fixture.roster().toByteString());
    assertThat(
            CanonicalGameplayRosterRecoveryEvidence.requireResponse(
                request, CanonicalGameplayRosterRecoveryResponse.parseFrom(response.toByteArray())))
        .isEqualTo(response);
    var owner =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
                fixture.original(),
                response.getOwnerFence(),
                UUID.fromString(response.getTargetFenceUuid()),
                fixture.roster())
            .toBuilder()
            .setRecoveryUuid(request.getRecoveryUuid())
            .build();
    assertThat(CanonicalGameplayRosterRecoveryEvidence.requireOwnerRecovery(request, owner))
        .isEqualTo(owner);
    assertThatThrownBy(() -> CanonicalGameplayRosterReferenceEvidence.requireDereference(owner))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterRecoveryEvidence.response(
                    request, UUID.randomUUID(), 1L, UUID.randomUUID(), null))
        .isInstanceOf(IllegalArgumentException.class);
    var noCapture =
        CanonicalGameplayRosterRecoveryEvidence.playerCreatedResponse(
            request, UUID.randomUUID(), 1L, UUID.randomUUID(), null);
    assertThat(noCapture.getCanonicalPlayerCreatedRosterReceipt()).isEmpty();
    assertThat(CanonicalGameplayRosterRecoveryEvidence.requireResponse(request, noCapture))
        .isEqualTo(noCapture);
    for (var changed :
        List.of(
            response.toBuilder()
                .setCanonicalRosterReceipt(fixture.roster().getRoster().toByteString())
                .build(),
            response.toBuilder()
                .setCanonicalPlayerCreatedRosterReceipt(
                    fixture.roster().toBuilder()
                        .setSourceIdentity(
                            fixture.roster().getSourceIdentity().toBuilder()
                                .setAuthoredBindingUuid(UUID.randomUUID().toString()))
                        .build()
                        .toByteString())
                .build(),
            response.toBuilder()
                .setCanonicalPlayerCreatedRosterReceipt(
                    fixture.roster().toBuilder()
                        .setRoster(
                            fixture.roster().getRoster().toBuilder()
                                .setCanonicalAccountUuid(UUID.randomUUID().toString()))
                        .build()
                        .toByteString())
                .build(),
            response.toBuilder()
                .setRequest(request.toBuilder().setRecoveryUuid(UUID.randomUUID().toString()))
                .build())) {
      assertThatThrownBy(
              () -> CanonicalGameplayRosterRecoveryEvidence.requireResponse(request, changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void recoveryBudgetsStayEightAnd128KiBWithClosedPolicySpecificReceiptFields() {
    var fixture = CanonicalGameplayRosterReferenceEvidenceTest.createdFixture();
    var request = request(fixture.original());
    var response =
        CanonicalGameplayRosterRecoveryEvidence.playerCreatedResponse(
            request, UUID.randomUUID(), 1L, UUID.randomUUID(), null);
    assertThat(CanonicalGameplayRosterRecoveryEvidence.MAX_REQUEST_BYTES).isEqualTo(8192);
    assertThat(CanonicalGameplayRosterRecoveryEvidence.MAX_RESPONSE_BYTES).isEqualTo(128 * 1024);
    int maximumRosterEnvelope = 66 * 1024 + 4096 + 256;
    assertThat(maximumRosterEnvelope)
        .isLessThanOrEqualTo(CanonicalGameplayRosterRecoveryEvidence.MAX_RESPONSE_BYTES);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterRecoveryEvidence.requireResponse(
                    request,
                    response.toBuilder()
                        .setCanonicalPlayerCreatedRosterReceipt(
                            CanonicalGameplayRosterReferenceEvidenceTest.logicalBytes(
                                66 * 1024 + 1))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterRecoveryEvidence.requireResponse(
                    request,
                    response.toBuilder()
                        .setCanonicalPlayerCreatedRosterReceipt(
                            CanonicalGameplayRosterReferenceEvidenceTest.logicalBytes(
                                128 * 1024 + 1))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterRecoveryEvidence.requireResponse(
                    request, response.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);
    var oldTarget =
        CanonicalGameplayRosterReferenceEvidence.target(fixture.original()).toBuilder()
            .setEntryPolicy(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy
                    .CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
            .build();
    var old =
        CanonicalGameplayRosterReferenceEvidence.request(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "gameplay", oldTarget);
    var oldRequest = request(old);
    var oldReceipt =
        CanonicalGameplayRosterResponse.newBuilder()
            .setCanonicalAccountUuid(old.getCanonicalAccountUuid())
            .setTarget(oldTarget)
            .setSnapshotUuid(UUID.randomUUID().toString())
            .setSnapshotDigest("d".repeat(64))
            .build();
    var oldResponse =
        CanonicalGameplayRosterRecoveryEvidence.response(
            oldRequest, UUID.randomUUID(), 1L, UUID.randomUUID(), oldReceipt);
    assertThat(CanonicalGameplayRosterRecoveryEvidence.requireResponse(oldRequest, oldResponse))
        .isEqualTo(oldResponse);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterRecoveryEvidence.requireResponse(
                    oldRequest,
                    oldResponse.toBuilder()
                        .setCanonicalPlayerCreatedRosterReceipt(fixture.roster().toByteString())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterRecoveryEvidence.playerCreatedResponse(
                    oldRequest, UUID.randomUUID(), 1L, UUID.randomUUID(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CanonicalGameplayRosterRecoveryRequest request(
      net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest original) {
    return CanonicalGameplayRosterRecoveryRequest.newBuilder()
        .setSchemaVersion(1)
        .setOriginalSelection(original)
        .setRecoveryUuid(UUID.randomUUID().toString())
        .build();
  }
}
