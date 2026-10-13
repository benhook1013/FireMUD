package net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StartSessionAccountRedemptionProjectionTest {
  @Test
  void retainsExactlyTheCompleteOwnerTupleWithinTheExistingByteBound() {
    var original = CurrentAttemptTestData.tuple();
    byte[] retained = StartSessionAccountRedemptionProjection.fromOriginalTuple(original);
    assertThat(retained).containsExactly(original.canonicalBytes());
    assertThat(retained.length)
        .isLessThanOrEqualTo(StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES);
    assertThatCode(
            () -> StartSessionAccountRedemptionProjection.requireCurrentBinding(retained, original))
        .doesNotThrowAnyException();
    retained[0] ^= 1;
    assertThat(original.canonicalBytes()[0]).isEqualTo((byte) '{');
  }

  @Test
  void exactLargeFencesRemainDistinctAcrossCurrentOwnerProjectionReplay() {
    var baseline = CurrentAttemptTestData.tuple();
    for (long fence :
        new long[] {
          9_007_199_254_740_991L, 9_007_199_254_740_992L, 9_007_199_254_740_993L, Long.MAX_VALUE
        }) {
      var original = withReservationFence(baseline, fence);
      byte[] retained = StartSessionAccountRedemptionProjection.fromOriginalTuple(original);
      assertThat(retained).containsExactly(original.canonicalBytes());
      assertThat(
              StartSessionAccountRedemptionProjection.decodeOwnerTuple(retained)
                  .reservationClaimFence())
          .isEqualTo(fence);
      assertThatCode(
              () ->
                  StartSessionAccountRedemptionProjection.requireCurrentBinding(retained, original))
          .doesNotThrowAnyException();
      assertThat(StartSessionAccountRedemptionProjection.fromOriginalTuple(original))
          .containsExactly(retained);

      var changed = withReservationFence(baseline, fence == Long.MAX_VALUE ? fence - 1 : fence + 1);
      assertThatThrownBy(
              () ->
                  StartSessionAccountRedemptionProjection.requireCurrentBinding(retained, changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static StartSessionPostAuthorizationExecutionTuple withReservationFence(
      StartSessionPostAuthorizationExecutionTuple original, long fence) {
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        original.preAuthorizationTuple(),
        original.authenticatedWorkloadIdentity(),
        original.authorizationReferenceFingerprint(),
        original.reservationOwnerId(),
        fence,
        original.authorityEvidenceBundleBytes(),
        original.bundleReference());
  }

  @Test
  void independentlyRetainedOwnerTupleRejectsSourceFenceAndLinearizationSubstitutions() {
    var original = CurrentAttemptTestData.tuple();
    byte[] ownerEvidence = original.canonicalBytes();
    var reference = original.bundleReference();
    for (var changedReference :
        new StartSessionAuthorityEvidenceBundle.BundleReference[] {
          new StartSessionAuthorityEvidenceBundle.BundleReference(
              reference.bundleVersion(),
              reference.sourceVersion(),
              "24",
              reference.linearization()),
          new StartSessionAuthorityEvidenceBundle.BundleReference(
              reference.bundleVersion(),
              reference.sourceVersion(),
              reference.sourceFence(),
              "18446744073709551614")
        }) {
      var substituted =
          StartSessionPostAuthorizationExecutionTuple.createHuman(
              original.preAuthorizationTuple(),
              original.authenticatedWorkloadIdentity(),
              original.authorizationReferenceFingerprint(),
              original.reservationOwnerId(),
              original.reservationClaimFence(),
              original.authorityEvidenceBundleBytes(),
              changedReference);
      assertThatThrownBy(
              () ->
                  StartSessionAccountRedemptionProjection.requireCurrentBinding(
                      ownerEvidence, substituted))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("differs");
      assertThatThrownBy(
              () ->
                  StartSessionAccountRedemptionProjection.requireHistoricalBinding(
                      ownerEvidence, substituted))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("differs");
    }
  }

  @Test
  void ownerEvidenceRejectsChangedBundleVersionAndSourceVersion() throws Exception {
    var original = CurrentAttemptTestData.tuple();
    String json = new String(original.canonicalBytes(), StandardCharsets.UTF_8);
    for (String changed :
        new String[] {
          json.replace(
              "\"bundleVersion\":\"authorityEvidenceBundle/v1\"",
              "\"bundleVersion\":\"authorityEvidenceBundle/v2\""),
          json.replace("\"sourceVersion\":\"17\"", "\"sourceVersion\":\"18\"")
        }) {
      assertThat(changed).isNotEqualTo(json);
      byte[] candidate = Rfc8785CanonicalJson.canonicalizeUtf8(changed);
      assertThatThrownBy(
              () ->
                  StartSessionAccountRedemptionProjection.requireCurrentBinding(
                      candidate, original))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void scalarV1ProjectionRemainsBytePreservedHistoricalEvidenceAndFailsCurrentUse()
      throws Exception {
    var original = CurrentAttemptTestData.tuple();
    var bundle =
        StartSessionAuthorityEvidenceBundle.decode(original.authorityEvidenceBundleBytes());
    byte[] oldBytes =
        Rfc8785CanonicalJson.canonicalizeUtf8(
            JsonMapper.builder()
                .build()
                .writeValueAsString(
                    Map.of(
                        "projectionSchemaId",
                        "accountStartSessionRedemptionProjection",
                        "projectionSchemaVersion",
                        "1",
                        "authorizationReferenceFingerprint",
                        original.authorizationReferenceFingerprint(),
                        "authorityEvidenceBundle",
                        bundle.jsonValue(),
                        "issuanceOperationId",
                        bundle.issuanceOperationId().toString(),
                        "issuanceFence",
                        Long.parseLong(original.issuanceFence()))));
    byte[] before = oldBytes.clone();
    assertThatCode(
            () ->
                StartSessionAccountRedemptionProjection.requireHistoricalBinding(
                    oldBytes, original))
        .doesNotThrowAnyException();
    assertThat(oldBytes).containsExactly(before);
    assertThatThrownBy(
            () -> StartSessionAccountRedemptionProjection.requireCurrentBinding(oldBytes, original))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StartSessionAccountRedemptionProjection.decodeOwnerTuple(new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
