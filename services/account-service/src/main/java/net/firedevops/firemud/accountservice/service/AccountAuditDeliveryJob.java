package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded delivery for retained V1 Account audit envelopes through Logging & Admin receipts. */
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

  @Scheduled(
      fixedDelayString = "${firemud.account.audit.delivery.interval-ms:60000}",
      timeUnit = TimeUnit.MILLISECONDS)
  public void deliverPending() {
    Instant capturedNow = Instant.now();
    for (var envelope : outbox.pending(50, capturedNow)) {
      try {
        var receipt = loggingAdminClient.deliver(envelope);
        outbox.markDelivered(
            envelope.auditEventId(),
            receipt.receiptId(),
            receipt.logEventId(),
            receipt.minimized());
      } catch (RuntimeException ex) {
        try {
          outbox.recordAttempt(envelope.auditEventId());
        } catch (RuntimeException bookkeepingFailure) {
          logger.warn(
              "Failed to record Account audit attempt for event {}", envelope.auditEventId());
        }
        logger.warn("Account audit delivery remains pending for event {}", envelope.auditEventId());
      }
    }
  }
}
