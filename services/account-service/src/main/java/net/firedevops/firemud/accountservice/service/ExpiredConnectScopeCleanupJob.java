package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.common.LoggingUtil;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded owner-local cleanup of expired connect scopes not retained by a JOIN receipt. */
@Component
@SuppressFBWarnings(
    value = {"EI_EXPOSE_REP2", "CT_CONSTRUCTOR_THROW"},
    justification =
        "Injected Spring collaborators are internal; invalid cleanup configuration must fail startup.")
public class ExpiredConnectScopeCleanupJob {
  private static final Logger logger = LoggingUtil.getLogger(ExpiredConnectScopeCleanupJob.class);

  private final AccountConnectScopeRepository connectScopeRepository;
  private final int batchSize;
  private final Counter deleted;
  private final Counter failures;

  public ExpiredConnectScopeCleanupJob(
      AccountConnectScopeRepository connectScopeRepository,
      MeterRegistry meterRegistry,
      @Value("${firemud.account.connect-scopes.cleanup.batch-size:100}") int batchSize,
      @Value("${firemud.account.connect-scopes.cleanup.interval-ms:60000}") long intervalMs) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException(
          "firemud.account.connect-scopes.cleanup.batch-size must be positive");
    }
    if (intervalMs <= 0) {
      throw new IllegalArgumentException(
          "firemud.account.connect-scopes.cleanup.interval-ms must be positive");
    }
    this.connectScopeRepository = connectScopeRepository;
    this.batchSize = batchSize;
    this.deleted =
        Counter.builder("account.connect_scopes.cleanup.deleted")
            .description("Expired unreferenced Account connect-scope rows deleted")
            .register(meterRegistry);
    this.failures =
        Counter.builder("account.connect_scopes.cleanup.failure")
            .description("Account connect-scope cleanup failures")
            .register(meterRegistry);
  }

  @Timed(value = "account.connect_scopes.cleanup")
  @Scheduled(
      fixedDelayString = "${firemud.account.connect-scopes.cleanup.interval-ms:60000}",
      timeUnit = TimeUnit.MILLISECONDS)
  public void cleanupExpiredConnectScopes() {
    Instant capturedNow = Instant.now();
    try {
      deleted.increment(connectScopeRepository.deleteExpiredUnreferenced(capturedNow, batchSize));
    } catch (RuntimeException ex) {
      failures.increment();
      logger.warn("Expired connect-scope cleanup step failed ({})", ex.getClass().getSimpleName());
    }
  }
}
