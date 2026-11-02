package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.stripe.exception.SignatureVerificationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import net.firedevops.firemud.accountservice.client.StripeHostingWebhookVerifier;
import net.firedevops.firemud.accountservice.repository.StripeEventInboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class StripeWebhookIntakeServiceTest {
  private static final String SECRET = "whsec_local_test_secret";
  private static final String INVALID_SIGNATURE = "t=1793491200,v1=invalid";
  private static final String PAYLOAD =
      "{\"id\":\"evt_intake_unit\",\"object\":\"event\","
          + "\"api_version\":\"2025-02-24.acacia\",\"created\":1793491200,"
          + "\"data\":{\"object\":{\"id\":\"unmapped_provider_object\"}},"
          + "\"livemode\":false,\"type\":\"unknown.unmapped_event\"}";

  @AfterEach
  void clearThreadTransactionState() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void invalidSignatureFailsBeforeStartingTransactionOrCallingInbox() {
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    StripeEventInboxRepository inbox = mock(StripeEventInboxRepository.class);
    StripeWebhookIntakeService service = service(transactions, inbox);

    assertThatThrownBy(() -> service.accept(PAYLOAD, INVALID_SIGNATURE))
        .isInstanceOf(SignatureVerificationException.class);

    verifyNoInteractions(transactions, inbox);
  }

  @Test
  void ambientTransactionIsRejectedBeforeSignatureVerificationOrStorage() {
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    StripeEventInboxRepository inbox = mock(StripeEventInboxRepository.class);
    StripeWebhookIntakeService service = service(transactions, inbox);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> service.accept(PAYLOAD, INVALID_SIGNATURE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient transaction or synchronization");

    verifyNoInteractions(transactions, inbox);
  }

  @Test
  void activeSynchronizationWithoutTransactionIsAlsoRejectedBeforeVerification() {
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    StripeEventInboxRepository inbox = mock(StripeEventInboxRepository.class);
    StripeWebhookIntakeService service = service(transactions, inbox);
    TransactionSynchronizationManager.initSynchronization();

    assertThatThrownBy(() -> service.accept(PAYLOAD, INVALID_SIGNATURE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient transaction or synchronization");

    verifyNoInteractions(transactions, inbox);
  }

  private static StripeWebhookIntakeService service(
      PlatformTransactionManager transactions, StripeEventInboxRepository inbox) {
    return new StripeWebhookIntakeService(
        new StripeHostingWebhookVerifier(
            SECRET, Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)),
        inbox,
        transactions);
  }
}
