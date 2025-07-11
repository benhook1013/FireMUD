package net.firedevops.firemud.repository;

import java.util.List;
import net.firedevops.firemud.entity.GenerationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GenerationRuleRepository extends JpaRepository<GenerationRule, Long> {
  List<GenerationRule> findByTenantId(Long tenantId);
}
