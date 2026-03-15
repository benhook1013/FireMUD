package net.firedevops.firemud.gamedesign.repository;

import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameTemplateRepository extends JpaRepository<GameTemplate, Long> {
  Page<GameTemplate> findByTenantId(String tenantId, Pageable pageable);
}
