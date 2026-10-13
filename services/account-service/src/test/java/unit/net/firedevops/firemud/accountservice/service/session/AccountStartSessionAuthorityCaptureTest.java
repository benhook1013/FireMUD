package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import org.junit.jupiter.api.Test;

class AccountStartSessionAuthorityCaptureTest {
  @Test
  void referenceIdentifiesTheDurableCaptureAndRetainsIndependentFenceValues() {
    byte[] snapshot =
        AccountControlUiAuthority.canonical(
            Map.of(
                "accountSourceVersion", "41",
                "issuanceFence", "9",
                "issuanceFenceSourceVersion", "12",
                "sourceVector", java.util.List.of("vector-entry"),
                "outboxCheckpoints", java.util.List.of(Map.of("stream", "account/one", "seq", "7")),
                "tuple", "exact-tuple"));

    // The two independent numeric domains may coincide; the bundle reference still denotes the
    // durable capture allocations and never substitutes them for the actual Account fence.
    var capture =
        AccountStartSessionAuthorityCapture.create(
            "control-plane-request-1",
            9L,
            9L,
            "18446744073709551615",
            "2026-10-10T12:34:56.789Z",
            snapshot);

    assertThat(capture.bundleReference().sourceVersion()).isEqualTo("9");
    assertThat(capture.bundleReference().sourceFence()).isEqualTo("9");
    assertThat(capture.bundleReference().linearization()).isEqualTo("18446744073709551615");
    assertThat(capture.capturedAt()).isEqualTo("2026-10-10T12:34:56.789Z");
    assertThat(capture.snapshotSha256()).hasSize(64);
    assertThat(capture.canonicalSha256()).hasSize(64);
    assertThat(capture.canonicalBytes())
        .contains("account-start-session-authority-capture/v1".getBytes(StandardCharsets.UTF_8));

    var readBack =
        AccountStartSessionAuthorityCapture.fromStorage(
            capture.controlPlaneRequestId(),
            capture.sourceVersion(),
            capture.sourceFence(),
            capture.linearization(),
            capture.capturedAt(),
            capture.snapshotBytes(),
            capture.snapshotSha256(),
            capture.canonicalBytes(),
            capture.canonicalSha256());
    assertThat(readBack.sameStoredValue(capture)).isTrue();
    assertThat(readBack.bundleReference()).isEqualTo(capture.bundleReference());
  }

  @Test
  void snapshotBytesAndReferenceBytesAreDefensiveAndChangingFenceSourceChangesCapture() {
    byte[] originalSnapshot =
        AccountControlUiAuthority.canonical(
            Map.of(
                "issuanceFence",
                "25",
                "issuanceFenceSourceVersion",
                "31",
                "sourceVector",
                java.util.List.of("same-vector"),
                "checkpoints",
                java.util.List.of("same-checkpoint")));
    var original =
        AccountStartSessionAuthorityCapture.create(
            "control-plane-request-2",
            44L,
            57L,
            "812",
            "2026-10-10T12:34:56.789Z",
            originalSnapshot);

    byte[] returnedSnapshot = original.snapshotBytes();
    byte[] returnedReference = original.canonicalBytes();
    returnedSnapshot[0] ^= 1;
    returnedReference[0] ^= 1;
    assertThat(original.snapshotBytes()).isEqualTo(originalSnapshot);
    assertThat(original.canonicalBytes())
        .contains("account-start-session-authority-capture/v1".getBytes(StandardCharsets.UTF_8));

    byte[] changedFenceVersion =
        AccountControlUiAuthority.canonical(
            Map.of(
                "issuanceFence",
                "25",
                "issuanceFenceSourceVersion",
                "32",
                "sourceVector",
                java.util.List.of("same-vector"),
                "checkpoints",
                java.util.List.of("same-checkpoint")));
    var changed =
        AccountStartSessionAuthorityCapture.create(
            "control-plane-request-2",
            45L,
            58L,
            "813",
            "2026-10-10T12:34:56.790Z",
            changedFenceVersion);

    assertThat(original.sameSnapshot(changed)).isFalse();
    assertThat(original.bundleReference()).isNotEqualTo(changed.bundleReference());
  }

  @Test
  void rejectsMismatchedRetainedCaptureDigestAndNonPositiveCaptureAllocations() {
    byte[] snapshot = AccountControlUiAuthority.canonical(Map.of("source", "owner"));
    var capture =
        AccountStartSessionAuthorityCapture.create(
            "control-plane-request-3", 1L, 2L, "3", "2026-10-10T12:34:56.789Z", snapshot);

    assertThatThrownBy(
            () ->
                AccountStartSessionAuthorityCapture.fromStorage(
                    capture.controlPlaneRequestId(),
                    1L,
                    2L,
                    "3",
                    capture.capturedAt(),
                    snapshot,
                    "0".repeat(64),
                    capture.canonicalBytes(),
                    capture.canonicalSha256()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionAuthorityCapture.create(
                    "control-plane-request-3", 0L, 2L, "3", "2026-10-10T12:34:56.789Z", snapshot))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
