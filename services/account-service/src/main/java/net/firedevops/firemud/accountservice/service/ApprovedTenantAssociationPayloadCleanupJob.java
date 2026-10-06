package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.common.LoggingUtil;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded deletion of signed Account association payload after its immutable capture-based expiry.
 */
@Component
public class ApprovedTenantAssociationPayloadCleanupJob {
  private static final Logger logger =
      LoggingUtil.getLogger(ApprovedTenantAssociationPayloadCleanupJob.class);

  private final ApprovedLegacyTenantAssociationRepository repository;
  private final Counter deleted;
  private final Counter failures;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected repository is an internal Spring collaborator and is not exposed by this job.")
  public ApprovedTenantAssociationPayloadCleanupJob(
      ApprovedLegacyTenantAssociationRepository repository, MeterRegistry meterRegistry) {
    this.repository = repository;
    this.deleted =
        Counter.builder("account.tenant_association_payload.cleanup.deleted")
            .description("Expired Account tenant-association approval payloads deleted")
            .register(meterRegistry);
    this.failures =
        Counter.builder("account.tenant_association_payload.cleanup.failure")
            .description("Account tenant-association approval payload cleanup failures")
            .register(meterRegistry);
  }

  @Timed(value = "account.tenant_association_payload.cleanup")
  @Scheduled(
      fixedDelayString = "${firemud.account.tenant-association.cleanup.interval-ms:3600000}",
      timeUnit = TimeUnit.MILLISECONDS)
  public void cleanupExpiredPayloads() {
    try {
      deleted.increment(repository.deleteExpiredApprovalPayloads(100));
    } catch (RuntimeException ex) {
      failures.increment();
      logger.warn(
          "Expired tenant-association payload cleanup failed ({})", ex.getClass().getSimpleName());
    }
  }
}
