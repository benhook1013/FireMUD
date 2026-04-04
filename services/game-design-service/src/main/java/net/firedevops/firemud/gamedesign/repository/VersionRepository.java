package net.firedevops.firemud.gamedesign.repository;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.Version;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VersionRepository extends JpaRepository<Version, Long> {
  List<Version> findAllByTenantIdOrderByVersionNumberAsc(String tenantId);

  Optional<Version> findTopByTenantIdOrderByVersionNumberDesc(String tenantId);
}
