package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof.Outcome;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import org.junit.jupiter.api.Test;

/** Complete controlled values only; no Account issuance or Game Session custody is established. */
class AccountInitialAdmissionSettlementReadGrpcCodecTest {
  @Test
  void roundTripsExactCompleteProtectionAndBothTerminalOutcomes() {
    for (Outcome outcome : List.of(Outcome.COMMITTED, Outcome.ABORTED)) {
      var evidence = evidence(outcome, false);
      var input = evidence.input();
      var request =
          AccountInitialAdmissionSettlementReadGrpcCodec.toRequest(input, "world-runtime");
      var response = AccountInitialAdmissionSettlementReadGrpcCodec.toResponse(evidence);
      assertThat(response.getSchemaVersionList()).containsExactly(1);
      assertThat(
              AccountInitialAdmissionSettlementReadGrpcCodec.fromRequestWireBytes(
                      request.toByteArray(), "world-runtime")
                  .protection()
                  .canonicalBytes())
          .containsExactly(input.protection().canonicalBytes());
      var decoded =
          AccountInitialAdmissionSettlementReadGrpcCodec.fromResponseWireBytes(
              input, response.toByteArray());
      assertThat(decoded.protection().canonicalBytes())
          .containsExactly(evidence.protection().canonicalBytes());
      assertThat(
              GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                  decoded.terminal()))
          .containsExactly(
              GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                  evidence.terminal()));
      assertThat(decoded.readRequestId()).isEqualTo(input.readRequestId());
    }
  }

  @Test
  void rejectsNonterminalAndChangedCompleteHoldDespiteSameOriginalProtection() {
    var evidence = evidence(Outcome.COMMITTED, false);
    var hold = evidence.terminal().holdIdentity();
    var pending =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            hold, Outcome.PENDING, null, null, null, false, null);
    assertThatThrownBy(
            () ->
                new AccountInitialAdmissionSettlementReadEvidence(
                    evidence.readRequestId(), evidence.protection(), pending))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Nonterminal");
    var changed =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            hold.request(), UUID.randomUUID(), hold.holdFence());
    var differentHold =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            changed, Outcome.ABORTED, null, null, "sha256:" + "a".repeat(64), true, Instant.EPOCH);
    assertThatThrownBy(
            () ->
                new AccountInitialAdmissionSettlementReadEvidence(
                    evidence.readRequestId(), evidence.protection(), differentHold))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete protected hold");
    assertThatThrownBy(
            () ->
                new AccountInitialAdmissionSettlementReadInput(
                    new UUID(0L, 0L), evidence.protection()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesMissingDuplicateUnknownAndSubstitutedRequestFields() {
    var input = evidence(Outcome.COMMITTED, false).input();
    var request = AccountInitialAdmissionSettlementReadGrpcCodec.toRequest(input, "world-runtime");
    var invalid =
        List.of(
            request.toBuilder().clearSchemaVersion().build(),
            request.toBuilder().addSchemaVersion(1).build(),
            request.toBuilder().clearTargetNamespace().build(),
            request.toBuilder().addTargetNamespace("world-runtime").build(),
            request.toBuilder().setTargetNamespace(0, "other").build(),
            request.toBuilder().clearReadRequestId().build(),
            request.toBuilder().addReadRequestId(input.readRequestId().toString()).build(),
            request.toBuilder().setReadRequestId(0, "00000000-0000-0000-0000-000000000000").build(),
            request.toBuilder().clearAccountProtectionEvidence().build(),
            request.toBuilder()
                .addAccountProtectionEvidence(request.getAccountProtectionEvidence(0))
                .build(),
            request.toBuilder().setAccountProtectionEvidence(0, ByteString.EMPTY).build(),
            request.toBuilder().setUnknownFields(unknown()).build());
    for (var value : invalid) {
      assertThatThrownBy(
              () ->
                  AccountInitialAdmissionSettlementReadGrpcCodec.fromRequest(
                      value, "world-runtime"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () -> AccountInitialAdmissionSettlementReadGrpcCodec.fromRequest(request, "other"))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] duplicateWire = new byte[request.getSerializedSize() * 2];
    System.arraycopy(request.toByteArray(), 0, duplicateWire, 0, request.getSerializedSize());
    System.arraycopy(
        request.toByteArray(),
        0,
        duplicateWire,
        request.getSerializedSize(),
        request.getSerializedSize());
    assertThatThrownBy(
            () ->
                AccountInitialAdmissionSettlementReadGrpcCodec.fromRequestWireBytes(
                    duplicateWire, "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesMissingDuplicateUnknownAndSubstitutedResponseFields() {
    var evidence = evidence(Outcome.ABORTED, false);
    var response = AccountInitialAdmissionSettlementReadGrpcCodec.toResponse(evidence);
    var changed = evidence(Outcome.ABORTED, false);
    var invalid =
        List.of(
            response.toBuilder().clearSchemaVersion().build(),
            response.toBuilder().addSchemaVersion(1).build(),
            response.toBuilder().setSchemaVersion(0, 2).build(),
            response.toBuilder().clearReadRequestId().build(),
            response.toBuilder().addReadRequestId(evidence.readRequestId().toString()).build(),
            response.toBuilder().setReadRequestId(0, UUID.randomUUID().toString()).build(),
            response.toBuilder().clearAccountProtectionEvidence().build(),
            response.toBuilder()
                .addAccountProtectionEvidence(response.getAccountProtectionEvidence(0))
                .build(),
            response.toBuilder()
                .setAccountProtectionEvidence(
                    0, ByteString.copyFrom(changed.protection().canonicalBytes()))
                .build(),
            response.toBuilder().clearCanonicalGameSessionOwnerProof().build(),
            response.toBuilder()
                .addCanonicalGameSessionOwnerProof(response.getCanonicalGameSessionOwnerProof(0))
                .build(),
            response.toBuilder().setCanonicalGameSessionOwnerProof(0, ByteString.EMPTY).build(),
            response.toBuilder()
                .setCanonicalGameSessionOwnerProof(0, ByteString.copyFrom(new byte[66_049]))
                .build(),
            response.toBuilder().setUnknownFields(unknown()).build());
    for (var value : invalid) {
      assertThatThrownBy(
              () ->
                  AccountInitialAdmissionSettlementReadGrpcCodec.fromResponse(
                      evidence.input(), value))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void refusesNoncanonicalNestedEvidenceMalformedFramingAndPurposeOverflow() {
    var evidence = evidence(Outcome.COMMITTED, false);
    var request =
        AccountInitialAdmissionSettlementReadGrpcCodec.toRequest(evidence.input(), "world-runtime");
    String canonical = new String(evidence.protection().canonicalBytes(), StandardCharsets.UTF_8);
    for (String invalid :
        List.of(
            canonical + " ",
            canonical + "{}",
            canonical.replaceFirst("\\{", "{\"schema\":\"duplicate\","))) {
      var changed =
          request.toBuilder()
              .setAccountProtectionEvidence(0, ByteString.copyFromUtf8(invalid))
              .build();
      assertThatThrownBy(
              () ->
                  AccountInitialAdmissionSettlementReadGrpcCodec.fromRequest(
                      changed, "world-runtime"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var response = AccountInitialAdmissionSettlementReadGrpcCodec.toResponse(evidence);
    byte[] proof = response.getCanonicalGameSessionOwnerProof(0).toByteArray();
    byte[] trailing = Arrays.copyOf(proof, proof.length + 1);
    assertThatThrownBy(
            () ->
                AccountInitialAdmissionSettlementReadGrpcCodec.fromResponse(
                    evidence.input(),
                    response.toBuilder()
                        .setCanonicalGameSessionOwnerProof(0, ByteString.copyFrom(trailing))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountInitialAdmissionSettlementReadGrpcCodec.fromRequest(
                    request.toBuilder()
                        .setAccountProtectionEvidence(
                            0,
                            ByteString.copyFrom(
                                new byte
                                    [AccountStartSessionAdmissionProtectionEvidence
                                            .MAX_CANONICAL_BYTES
                                        + 1]))
                        .build(),
                    "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountInitialAdmissionSettlementReadGrpcCodec.fromResponseWireBytes(
                    evidence.input(),
                    new byte
                        [AccountInitialAdmissionSettlementReadGrpcCodec.MAX_RESPONSE_WIRE_BYTES
                            + 1]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static AccountInitialAdmissionSettlementReadEvidence evidence(Outcome outcome, boolean large) {
    var small = AccountInitialAdmissionSettlementTestFixtures.evidence(outcome);
    if (!large) return small;
    var sources = new ArrayList<SourceEvidence>();
    for (int index = 0; index < 32; index++) {
      sources.add(
          new SourceEvidence(
              SourceKind.ISSUER,
              "https://issuer-" + index + ".example",
              null,
              "17",
              null,
              null,
              new byte[100 * 1024]));
    }
    var original = small.protection();
    var protection =
        AccountStartSessionAdmissionProtectionEvidence.create(
            original.request(),
            original.accountProtectionId(),
            original.accountProtectionFence(),
            original.originalSourceCaptureReferenceBytes(),
            original.originalSourceCaptureReferenceSha256(),
            sources);
    return new AccountInitialAdmissionSettlementReadEvidence(
        small.readRequestId(), protection, small.terminal());
  }

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }
}
