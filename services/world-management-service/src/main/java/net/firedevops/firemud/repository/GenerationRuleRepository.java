package net.firedevops.firemud.repository;

import net.firedevops.firemud.entity.GenerationRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GenerationRuleRepository extends JpaRepository<GenerationRule, Long> {
  Page<GenerationRule> findByTenantId(Long tenantId, Pageable pageable);
}
