package net.firedevops.firemud.gamedesign.repository;

import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VersionAssetArtifactRepository extends JpaRepository<VersionAssetArtifact, Long> {
  Optional<VersionAssetArtifact> findByTenantIdAndVersionId(String tenantId, Long versionId);
}
