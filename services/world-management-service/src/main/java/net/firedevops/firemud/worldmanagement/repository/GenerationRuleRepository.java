package net.firedevops.firemud.worldmanagement.repository;

import java.util.Optional;
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

  Optional<GenerationRule> findByTenantIdAndVersionIdAndId(Long tenantId, Long versionId, Long id);

  Optional<GenerationRule> findByTenantIdAndVersionIdAndScopeTypeAndScopeIdAndName(
      Long tenantId, Long versionId, String scopeType, String scopeId, String name);

  java.util.List<GenerationRule> findByTenantIdAndVersionIdAndScopeTypeAndScopeIdOrderByIdAsc(
      Long tenantId, Long versionId, String scopeType, String scopeId);
}
