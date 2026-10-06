package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gradually erases expired retained-tenant raw payloads while preserving anti-reassignment rows.
 */
@Component
@ConditionalOnProperty(
    name = "firemud.retained-tenant.payload-retention.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class GameSessionRetainedTenantPayloadRetentionJob {
  private static final Logger LOG =
      LoggerFactory.getLogger(GameSessionRetainedTenantPayloadRetentionJob.class);

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The Spring-managed DSLContext is intentionally shared for owner transactions.")
  public GameSessionRetainedTenantPayloadRetentionJob(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Scheduled(fixedDelayString = "${firemud.retained-tenant.payload-retention.sweep-ms:60000}")
  @Transactional
  public void purgeExpiredPayloadBatch() {
    int deleted = GameSessionRetainedTenantAssociationRepository.purgeExpiredRawPayloads(dsl);
    if (deleted > 0) {
      LOG.info("Purged {} expired retained-tenant payload(s)", deleted);
    }
  }
}
