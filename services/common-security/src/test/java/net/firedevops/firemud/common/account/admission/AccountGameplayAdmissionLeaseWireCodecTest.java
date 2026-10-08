package net.firedevops.firemud.common.account.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.firedevops.firemud.account.v1.GameplayAdmissionLeaseOperationReadback;
import net.firedevops.firemud.account.v1.GameplayAdmissionLeaseReference;
import net.firedevops.firemud.account.v1.GameplayAdmissionLeaseState;
import org.junit.jupiter.api.Test;

/** Wire shape proof only; fixtures establish no authenticated receipt or admission authority. */
class AccountGameplayAdmissionLeaseWireCodecTest {
  private static final UUID DECISION = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID CLEANUP = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

  @Test
  void preservesOriginalSharedCarrierBytesDigestAndExactCounters() {
    var evidence = evidence();

    var wire = AccountGameplayAdmissionLeaseWireCodec.encodeReference(evidence);
    var parsed = AccountGameplayAdmissionLeaseWireCodec.parseReference(wire);

    assertThat(wire.getEvidenceCanonicalJson())
        .isEqualTo(ByteString.copyFromUtf8(evidence.canonicalJson()));
    assertThat(wire.getEvidenceSha256()).isEqualTo(evidence.sha256());
    assertThat(wire.getRequestId()).isEqualTo(evidence.carrier().get("requestId"));
    assertThat(parsed).isEqualTo(evidence);
    assertThat(parsed.leaseFence()).isEqualTo(new BigInteger("9007199254740993"));
    assertThat(AccountGameplayAdmissionLeaseWireCodec.encodeReference(parsed)).isEqualTo(wire);
  }

  @Test
  void preservesResumeCarrierAndOptionalPriorGenerationWithoutClaimingRuntimeSupport() {
    var carrier = AccountGameplayAdmissionLeaseEvidenceTest.fixture();
    carrier.put("leaseKind", "RESUME");
    carrier.put("expectedOldBindingGeneration", "9007199254740993");
    carrier.put("resumeEpisodeId", DECISION.toString());
    var value = AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);

    assertThat(
            AccountGameplayAdmissionLeaseWireCodec.parseReference(
                AccountGameplayAdmissionLeaseWireCodec.encodeReference(value)))
        .isEqualTo(value);
  }

  @Test
  void rejectsTamperedRequestHashBytesAndUnknownWireFields() {
    List<Consumer<GameplayAdmissionLeaseReference.Builder>> changes =
        List.of(
            b -> b.setRequestId(DECISION.toString()),
            b -> b.setRequestId("not-a-uuid"),
            b -> b.setRequestId(DECISION.toString().toUpperCase(java.util.Locale.ROOT)),
            b -> b.setEvidenceSha256("0".repeat(64)),
            b -> b.setEvidenceSha256(evidence().sha256().toUpperCase(java.util.Locale.ROOT)),
            b -> b.setEvidenceSha256(""),
            b -> b.setEvidenceCanonicalJson(ByteString.EMPTY),
            b -> b.setEvidenceCanonicalJson(ByteString.copyFrom(new byte[] {(byte) 0xc3, 0x28})),
            b ->
                b.setEvidenceCanonicalJson(
                    ByteString.copyFrom(new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80})),
            b ->
                b.setEvidenceCanonicalJson(
                    ByteString.copyFromUtf8(
                        " ".repeat(AccountGameplayAdmissionLeaseEvidence.MAX_EVIDENCE_BYTES + 1))),
            b ->
                b.setEvidenceCanonicalJson(
                    ByteString.copyFromUtf8(" " + evidence().canonicalJson())),
            b ->
                b.setEvidenceCanonicalJson(
                    ByteString.copyFromUtf8(
                        evidence().canonicalJson().replace("\"world\"", "\"changed\""))),
            b -> b.setUnknownFields(unknownFields()));
    for (Consumer<GameplayAdmissionLeaseReference.Builder> change : changes) {
      var builder = reference().toBuilder();
      change.accept(builder);
      assertInvalid(() -> AccountGameplayAdmissionLeaseWireCodec.parseReference(builder.build()));
    }
  }

  @Test
  void rejectsExtraUnknownInvalidNullAndNoncanonicalJsonThroughExistingCodec() {
    String json = evidence().canonicalJson();
    for (String invalid :
        List.of(
            json.substring(0, json.length() - 1) + ",\"extra\":\"1\"}",
            json.replace("\"schemaVersion\":\"1\"", "\"schemaVersion\":\"2\""),
            json.replace("\"leaseKind\":\"NEW_BINDING\"", "\"leaseKind\":\"UNKNOWN\""),
            json.replace("\"leaseKind\":\"NEW_BINDING\"", "\"leaseKind\":null"),
            json.replace("\"leaseFence\":\"9007199254740993\"", "\"leaseFence\":\"01\""),
            json.replace(
                "\"schemaVersion\":\"1\"", "\"schemaVersion\":\"1\",\"schemaVersion\":\"1\""),
            "null")) {
      var wire =
          reference().toBuilder()
              .setEvidenceCanonicalJson(ByteString.copyFromUtf8(invalid))
              .build();
      assertInvalid(() -> AccountGameplayAdmissionLeaseWireCodec.parseReference(wire));
    }
  }

  @Test
  void rejectsUnsupportedSourceRangeWithoutRoundingOrSubstitution() {
    String invalid = evidence().canonicalJson().replace("9007199254740993", "9223372036854775808");
    var wire =
        reference().toBuilder().setEvidenceCanonicalJson(ByteString.copyFromUtf8(invalid)).build();

    assertInvalid(() -> AccountGameplayAdmissionLeaseWireCodec.parseReference(wire));
  }

  @Test
  void validOperationShapesPreserveOriginalEvidenceAndUnknownAbortDecision() {
    var pending =
        operation(GameplayAdmissionLeaseState.GAMEPLAY_ADMISSION_LEASE_STATE_PENDING, null, null);
    var committed =
        operation(
            GameplayAdmissionLeaseState.GAMEPLAY_ADMISSION_LEASE_STATE_COMMITTED, DECISION, null);
    var abortedUnknown =
        operation(
            GameplayAdmissionLeaseState.GAMEPLAY_ADMISSION_LEASE_STATE_ABORTED, null, CLEANUP);
    var abortedKnown =
        operation(
            GameplayAdmissionLeaseState.GAMEPLAY_ADMISSION_LEASE_STATE_ABORTED, DECISION, CLEANUP);

    for (var operation : List.of(pending, committed, abortedUnknown, abortedKnown)) {
      assertThat(AccountGameplayAdmissionLeaseWireCodec.validateOperation(operation))
          .isEqualTo(evidence());
      assertThat(operation.getLease()).isEqualTo(reference());
    }
    assertThat(pending.hasBindingDecisionId()).isFalse();
    assertThat(committed.getBindingDecisionId()).isEqualTo(DECISION.toString());
    assertThat(abortedUnknown.hasBindingDecisionId()).isFalse();
    assertThat(abortedUnknown.getPendingOrphanCleanup()).isTrue();
    assertThat(abortedKnown.getOrphanCleanupId()).isEqualTo(CLEANUP.toString());
  }

  @Test
  void rejectsUnknownMissingAndContradictoryOperationShapes() {
    var pending =
        operation(GameplayAdmissionLeaseState.GAMEPLAY_ADMISSION_LEASE_STATE_PENDING, null, null);
    List<Consumer<GameplayAdmissionLeaseOperationReadback.Builder>> changes =
        List.of(
            b -> b.clearLease(),
            b -> b.setStateValue(99),
            b -> b.setState(GameplayAdmissionLeaseState.GAMEPLAY_ADMISSION_LEASE_STATE_UNSPECIFIED),
            b -> b.setBindingDecisionId(DECISION.toString()),
            b -> b.setOrphanCleanupId(CLEANUP.toString()),
            b -> b.setPendingOrphanCleanup(true),
            b -> b.setState(GameplayAdmissionLeaseState.GAMEPLAY_ADMISSION_LEASE_STATE_COMMITTED),
            b -> b.setState(GameplayAdmissionLeaseState.GAMEPLAY_ADMISSION_LEASE_STATE_ABORTED),
            b -> b.setUnknownFields(unknownFields()));
    for (Consumer<GameplayAdmissionLeaseOperationReadback.Builder> change : changes) {
      var builder = pending.toBuilder();
      change.accept(builder);
      assertInvalid(
          () -> AccountGameplayAdmissionLeaseWireCodec.validateOperation(builder.build()));
    }
    var committed =
        operation(
            GameplayAdmissionLeaseState.GAMEPLAY_ADMISSION_LEASE_STATE_COMMITTED, DECISION, null);
    assertInvalid(
        () ->
            AccountGameplayAdmissionLeaseWireCodec.validateOperation(
                committed.toBuilder().setOrphanCleanupId(CLEANUP.toString()).build()));
    assertInvalid(
        () ->
            AccountGameplayAdmissionLeaseWireCodec.validateOperation(
                committed.toBuilder().setPendingOrphanCleanup(true).build()));
    var aborted =
        operation(
            GameplayAdmissionLeaseState.GAMEPLAY_ADMISSION_LEASE_STATE_ABORTED, null, CLEANUP);
    assertInvalid(
        () ->
            AccountGameplayAdmissionLeaseWireCodec.validateOperation(
                aborted.toBuilder().clearOrphanCleanupId().build()));
    assertInvalid(
        () ->
            AccountGameplayAdmissionLeaseWireCodec.validateOperation(
                aborted.toBuilder().setPendingOrphanCleanup(false).build()));
    assertInvalid(
        () ->
            AccountGameplayAdmissionLeaseWireCodec.validateOperation(
                aborted.toBuilder().setBindingDecisionId("").build()));
    assertInvalid(
        () ->
            AccountGameplayAdmissionLeaseWireCodec.validateOperation(
                aborted.toBuilder().setOrphanCleanupId("not-a-uuid").build()));
  }

  @Test
  void rejectsNullAndMalformedEncoderInputs() {
    assertInvalid(() -> AccountGameplayAdmissionLeaseWireCodec.encodeReference(null));
    assertInvalid(() -> AccountGameplayAdmissionLeaseWireCodec.parseReference(null));
    assertInvalid(() -> AccountGameplayAdmissionLeaseWireCodec.validateOperation(null));
    assertInvalid(
        () -> AccountGameplayAdmissionLeaseWireCodec.encodeOperation(evidence(), null, null, null));
    assertInvalid(() -> operation(GameplayAdmissionLeaseState.UNRECOGNIZED, null, null));
    assertInvalid(
        () ->
            operation(
                GameplayAdmissionLeaseState.GAMEPLAY_ADMISSION_LEASE_STATE_COMMITTED,
                new UUID(0, 0),
                null));
  }

  private static AccountGameplayAdmissionLeaseEvidence evidence() {
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(
        AccountGameplayAdmissionLeaseEvidenceTest.fixture());
  }

  private static GameplayAdmissionLeaseReference reference() {
    return AccountGameplayAdmissionLeaseWireCodec.encodeReference(evidence());
  }

  private static GameplayAdmissionLeaseOperationReadback operation(
      GameplayAdmissionLeaseState state, UUID decision, UUID cleanup) {
    return AccountGameplayAdmissionLeaseWireCodec.encodeOperation(
        evidence(), state, decision, cleanup);
  }

  private static UnknownFieldSet unknownFields() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }

  private static void assertInvalid(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Malformed Account admission lease wire evidence")
        .hasNoCause();
  }
}
