package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord.DisplayMetadata;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord.State;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRepository.Attachment;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRepository.AvailabilityObservation;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountPaymentInstrumentRepositoryTest {
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID INSTRUMENT = UUID.randomUUID();
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final DisplayMetadata DISPLAY = new DisplayMetadata("visa", "4242", 12, 2030);
  private final DSLContext dsl = mock(DSLContext.class);
  private final AccountPaymentInstrumentRepository instruments =
      new AccountPaymentInstrumentRepository(dsl);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void attachmentObservationAndLockedReadRequireWritableTransactionBeforeSql() {
    assertTransactionRefusal();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertTransactionRefusal();
    verifyNoInteractions(dsl);
  }

  private void assertTransactionRefusal() {
    assertThatThrownBy(() -> instruments.retainAttachment(attachment("pm_safe", DIGEST)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active writable Account transaction");
    assertThatThrownBy(() -> instruments.findByOwnerForUpdate(ACCOUNT, INSTRUMENT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active writable Account transaction");
    assertThatThrownBy(
            () ->
                instruments.recordAvailability(
                    new AvailabilityObservation(
                        UUID.randomUUID(),
                        INSTRUMENT,
                        ACCOUNT,
                        1,
                        State.UNAVAILABLE,
                        DISPLAY,
                        DIGEST)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active writable Account transaction");
  }

  @Test
  void attachmentRequiresProviderReferencesOpaqueIdentityAndEvidenceDigest() {
    assertThatThrownBy(() -> attachment("4242424242424242", DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> attachment("pm_safe", "raw-provider-response"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Attachment(
                    UUID.randomUUID(),
                    new UUID(0L, 0L),
                    ACCOUNT,
                    UUID.randomUUID(),
                    "cus_safe",
                    "pm_safe",
                    State.ATTACHABLE,
                    DISPLAY,
                    DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(dsl);
  }

  @Test
  void onlyFixedDisplayFieldsAndAdvanceableExpectedVersionsAreAccepted() {
    assertThat(new DisplayMetadata(null, null, null, null).lastFour()).isNull();
    assertThatThrownBy(() -> new DisplayMetadata("visa", "4242424242424242", 12, 2030))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DisplayMetadata("visa", "4242", 12, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DisplayMetadata("visa", "4242", 13, 2030))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DisplayMetadata("arbitrary provider payload", "4242", 12, 2030))
        .isInstanceOf(IllegalArgumentException.class);
    for (long version : new long[] {0, -1, Long.MAX_VALUE}) {
      assertThatThrownBy(
              () ->
                  new AvailabilityObservation(
                      UUID.randomUUID(),
                      INSTRUMENT,
                      ACCOUNT,
                      version,
                      State.UNAVAILABLE,
                      DISPLAY,
                      DIGEST))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(State.values()).containsExactly(State.ATTACHABLE, State.UNAVAILABLE);
    verifyNoInteractions(dsl);
  }

  private static Attachment attachment(String providerMethod, String evidenceDigest) {
    return new Attachment(
        UUID.randomUUID(),
        INSTRUMENT,
        ACCOUNT,
        UUID.randomUUID(),
        "cus_safe",
        providerMethod,
        State.ATTACHABLE,
        DISPLAY,
        evidenceDigest);
  }
}
