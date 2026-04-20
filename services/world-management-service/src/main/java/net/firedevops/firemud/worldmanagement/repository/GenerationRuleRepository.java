package net.firedevops.firemud.worldmanagement.repository;

import net.firedevops.firemud.worldmanagement.entity.GenerationRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GenerationRuleRepository extends JpaRepository<GenerationRule, Long> {
  Page<GenerationRule> findByTenantId(Long tenantId, Pageable pageable);

  java.util.List<GenerationRule> findByTenantIdOrderByIdAsc(Long tenantId);

  java.util.List<GenerationRule> findByTenantIdAndVersionIdOrderByIdAsc(
      Long tenantId, Long versionId);
}
