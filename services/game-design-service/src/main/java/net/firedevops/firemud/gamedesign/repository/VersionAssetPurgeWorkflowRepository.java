package net.firedevops.firemud.gamedesign.repository;

import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.VersionAssetPurgeWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VersionAssetPurgeWorkflowRepository
    extends JpaRepository<VersionAssetPurgeWorkflow, Long> {
  Optional<VersionAssetPurgeWorkflow> findByTenantIdAndVersionIdAndPurgeWorkflowId(
      String tenantId, Long versionId, String purgeWorkflowId);
}
