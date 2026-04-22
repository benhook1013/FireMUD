package net.firedevops.firemud.entitymanagement.repository;

import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.EntityMutationEffect;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EntityMutationEffectRepository extends JpaRepository<EntityMutationEffect, Long> {
  Optional<EntityMutationEffect> findByTenantIdAndEffectId(Long tenantId, String effectId);
}
