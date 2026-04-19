package net.firedevops.firemud.gamedesign.repository;

import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PublishedReleaseBundleRepository
    extends JpaRepository<PublishedReleaseBundle, Long> {
  Optional<PublishedReleaseBundle> findByTenantIdAndVersionId(String tenantId, Long versionId);
}
