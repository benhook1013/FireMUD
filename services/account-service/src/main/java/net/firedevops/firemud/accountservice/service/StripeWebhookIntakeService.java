package net.firedevops.firemud.accountservice.service;

import com.stripe.exception.SignatureVerificationException;
import java.util.Objects;
import net.firedevops.firemud.accountservice.client.StripeHostingWebhookVerifier;
import net.firedevops.firemud.accountservice.client.StripeHostingWebhookVerifier.VerifiedEvent;
import net.firedevops.firemud.accountservice.repository.StripeEventInboxRecord;
import net.firedevops.firemud.accountservice.repository.StripeEventInboxRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manually composed Stripe event intake. It verifies the exact raw request before starting its own
 * SQL transaction and retains only pending evidence; it is not registered as a route or bean.
 */
public final class StripeWebhookIntakeService {
  private final StripeHostingWebhookVerifier verifier;
  private final StripeEventInboxRepository inbox;
  private final TransactionTemplate transaction;

  public StripeWebhookIntakeService(
      StripeHostingWebhookVerifier verifier,
      StripeEventInboxRepository inbox,
      PlatformTransactionManager transactionManager) {
    this.verifier = Objects.requireNonNull(verifier, "Stripe webhook verifier is required");
    this.inbox = Objects.requireNonNull(inbox, "Stripe event inbox is required");
    this.transaction =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transaction manager is required"));
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Verifies and insert-once retains one event as pending evidence. No event type is selected or
   * interpreted as a command, subscription, tenant, customer, billing, or entitlement authority.
   */
  public StripeEventInboxRecord accept(String rawPayload, String signatureHeader)
      throws SignatureVerificationException {
    rejectAmbientTransaction();
    VerifiedEvent verified = verifier.verify(rawPayload, signatureHeader);
    StripeEventInboxRecord retained = transaction.execute(status -> inbox.retainOrReplay(verified));
    if (retained == null) {
      throw new IllegalStateException("Stripe event intake transaction returned no receipt");
    }
    return retained;
  }

  private static void rejectAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Stripe webhook verification and intake cannot run inside an ambient transaction or synchronization");
    }
  }
}
