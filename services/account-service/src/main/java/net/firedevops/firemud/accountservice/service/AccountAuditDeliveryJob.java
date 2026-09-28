package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** At-least-once delivery of Account's immutable audit outbox envelopes. */
@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected collaborators are internal Spring singletons.")
public class AccountAuditDeliveryJob {
  private static final Logger logger = LoggerFactory.getLogger(AccountAuditDeliveryJob.class);
  private final AccountAuditOutboxRepository outbox;
  private final LoggingAdminClient loggingAdminClient;

  public AccountAuditDeliveryJob(
      AccountAuditOutboxRepository outbox, LoggingAdminClient loggingAdminClient) {
    this.outbox = outbox;
    this.loggingAdminClient = loggingAdminClient;
  }

  @Scheduled(fixedDelayString = "${firemud.account.audit-delivery-delay-ms:5000}")
  public void deliverPending() {
    for (var envelope : outbox.pending(50)) {
      try {
        var receipt = loggingAdminClient.deliver(envelope);
        outbox.markDelivered(
            envelope.auditEventId(),
            receipt.receiptId(),
            receipt.logEventId(),
            receipt.minimized());
      } catch (RuntimeException ex) {
        outbox.recordAttempt(envelope.auditEventId());
        logger.warn("Account audit delivery remains pending for event {}", envelope.auditEventId());
      }
    }
  }
}
