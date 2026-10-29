package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.accountservice.client.AccountOriginalNewFinalAdmissionReadClient.AuthenticatedFinalAdmissionReadback;
import net.firedevops.firemud.accountservice.client.AccountOriginalNewFinalAdmissionReadClient.ExpectedOriginalAdmission;
import org.junit.jupiter.api.Test;

class AccountOriginalNewFinalAdmissionReadClientTest {
  private static final long ADMISSION_AT_MILLIS = 1_800_000_000_000L;
  private static final long MAX_CONTINUITY_DURATION_MILLIS = 300_000L;

  @Test
  void acceptsTheContinuityCapAndPositiveShorterDurations() {
    var atCap =
        readback(
            ADMISSION_AT_MILLIS,
            MAX_CONTINUITY_DURATION_MILLIS,
            ADMISSION_AT_MILLIS + MAX_CONTINUITY_DURATION_MILLIS);
    var shorter = readback(ADMISSION_AT_MILLIS, 1L, ADMISSION_AT_MILLIS + 1L);

    assertThat(atCap.canonicalSessionDurationMillis()).isEqualTo(MAX_CONTINUITY_DURATION_MILLIS);
    assertThat(shorter.canonicalSessionDurationMillis()).isEqualTo(1L);
  }

  @Test
  void rejectsContinuityDurationAboveTheCanonicalCap() {
    assertThatThrownBy(
            () ->
                readback(
                    ADMISSION_AT_MILLIS,
                    MAX_CONTINUITY_DURATION_MILLIS + 1L,
                    ADMISSION_AT_MILLIS + MAX_CONTINUITY_DURATION_MILLIS + 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Protected original final-admission readback is malformed");
  }

  @Test
  void preservesOverflowAndExpiryMismatchRejection() {
    assertThatThrownBy(() -> readback(Long.MAX_VALUE - 10L, 20L, Long.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Protected original final-admission readback is malformed");
    assertThatThrownBy(() -> readback(ADMISSION_AT_MILLIS, 1L, ADMISSION_AT_MILLIS + 2L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Protected original final-admission readback is malformed");
  }

  private static AuthenticatedFinalAdmissionReadback readback(
      long admissionAtMillis, long durationMillis, long expiryMillis) {
    return new AuthenticatedFinalAdmissionReadback(
        2,
        "COMPLETE",
        expectedAnchors(),
        admissionAtMillis,
        expiryMillis,
        durationMillis,
        "0".repeat(64));
  }

  private static ExpectedOriginalAdmission expectedAnchors() {
    UUID lineageId = UUID.randomUUID();
    UUID tokenJti = UUID.randomUUID();
    while (tokenJti.equals(lineageId)) tokenJti = UUID.randomUUID();
    return new ExpectedOriginalAdmission(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        1L,
        "1".repeat(64),
        lineageId,
        tokenJti,
        "2".repeat(64),
        1L,
        1L,
        "3".repeat(64),
        "4".repeat(64),
        "5".repeat(64),
        "6".repeat(64));
  }
}
