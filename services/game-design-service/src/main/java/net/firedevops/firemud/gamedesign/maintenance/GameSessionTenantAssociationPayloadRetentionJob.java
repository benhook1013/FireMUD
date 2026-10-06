package net.firedevops.firemud.gamedesign.maintenance;

import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gradually erases expired approval payloads while preserving association anti-reassignment rows.
 */
@Component
@ConditionalOnProperty(
    name = "firemud.retained-tenant.payload-retention.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class GameSessionTenantAssociationPayloadRetentionJob {
  private static final Logger LOG =
      LoggerFactory.getLogger(GameSessionTenantAssociationPayloadRetentionJob.class);

  private final GameSessionTenantAssociationRepository repository;

  public GameSessionTenantAssociationPayloadRetentionJob(
      GameSessionTenantAssociationRepository repository) {
    this.repository = repository;
  }

  @Scheduled(fixedDelayString = "${firemud.retained-tenant.payload-retention.sweep-ms:60000}")
  @Transactional
  public void purgeExpiredPayloadBatch() {
    int deleted = repository.purgeExpiredRawPayloads();
    if (deleted > 0) {
      LOG.info("Purged {} expired Game Session tenant approval payload(s)", deleted);
    }
  }
}
