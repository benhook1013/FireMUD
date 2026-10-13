package net.firedevops.firemud.entitymanagement.service.impl;

import net.firedevops.firemud.entitymanagement.dto.RuntimeInstanceCleanupResultDto;
import net.firedevops.firemud.entitymanagement.repository.QuarantinedActorRetentionRepository;
import net.firedevops.firemud.entitymanagement.service.RuntimeInstanceCleanupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuntimeInstanceCleanupServiceImpl implements RuntimeInstanceCleanupService {
  private final QuarantinedActorRetentionRepository quarantinedActorRetentionRepository;

  public RuntimeInstanceCleanupServiceImpl(
      QuarantinedActorRetentionRepository quarantinedActorRetentionRepository) {
    this.quarantinedActorRetentionRepository = quarantinedActorRetentionRepository;
  }

  @Override
  @Transactional
  public RuntimeInstanceCleanupResultDto cleanupRuntimeInstance(
      Long tenantId, String gameInstanceId, String terminationRequestId) {
    if (tenantId == null || tenantId <= 0L) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: tenantId is required");
    }
    if (gameInstanceId == null || gameInstanceId.isBlank()) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: gameInstanceId is required");
    }
    if (terminationRequestId == null || terminationRequestId.isBlank()) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: terminationRequestId is required");
    }
    if (quarantinedActorRetentionRepository.hasUnclassifiedOrQuarantinedRuntimeEvidence(
        tenantId, gameInstanceId)) {
      throw new IllegalStateException(
          "ENTITY_UNCLASSIFIED_OR_QUARANTINED_EVIDENCE_BLOCKS_CLEANUP: runtime rows lack owner classification or quarantined actor audit evidence is present");
    }
    return new RuntimeInstanceCleanupResultDto(0, 0, 0, 0);
  }
}
