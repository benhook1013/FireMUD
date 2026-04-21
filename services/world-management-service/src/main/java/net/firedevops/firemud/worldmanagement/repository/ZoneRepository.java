package net.firedevops.firemud.worldmanagement.repository;

import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.Zone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ZoneRepository extends JpaRepository<Zone, Long> {
  java.util.List<Zone> findByTenantIdOrderByIdAsc(Long tenantId);

  java.util.List<Zone> findByTenantIdAndVersionIdOrderByIdAsc(Long tenantId, Long versionId);

  Optional<Zone> findByTenantIdAndVersionIdAndId(Long tenantId, Long versionId, Long id);
}
