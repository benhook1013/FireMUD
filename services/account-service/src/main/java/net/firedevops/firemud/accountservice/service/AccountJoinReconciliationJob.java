package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically invokes Account's bounded, readback-only JOIN reconciler. */
@Component
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification = "Invalid scheduling configuration must fail startup.")
public class AccountJoinReconciliationJob {
  private final AccountJoinReconciliationService reconciliationService;

  public AccountJoinReconciliationJob(
      AccountJoinReconciliationService reconciliationService,
      @Value("${firemud.account.join-reconciliation.interval-ms:30000}") long intervalMillis) {
    if (intervalMillis < 1) {
      throw new IllegalArgumentException(
          "firemud.account.join-reconciliation.interval-ms must be positive");
    }
    this.reconciliationService = reconciliationService;
  }

  @Scheduled(
      fixedDelayString = "${firemud.account.join-reconciliation.interval-ms:30000}",
      timeUnit = TimeUnit.MILLISECONDS)
  public void reconcilePendingJoinOperations() {
    reconciliationService.reconcileDueOperations(Instant.now());
  }
}
