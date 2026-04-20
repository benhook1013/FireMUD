package net.firedevops.firemud.entitymanagement.repository;

import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.EntityMutationEffect;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EntityMutationEffectRepository extends JpaRepository<EntityMutationEffect, Long> {
  Optional<EntityMutationEffect> findByTenantIdAndEffectId(Long tenantId, String effectId);

  @Modifying
  @Query(
      value =
          """
          INSERT INTO entity_mutation_effects (tenant_id, effect_id, operation_name, status)
          VALUES (:tenantId, :effectId, :operationName, 'IN_PROGRESS')
          ON CONFLICT (tenant_id, effect_id) DO NOTHING
          """,
      nativeQuery = true)
  int insertInProgress(
      @Param("tenantId") Long tenantId,
      @Param("effectId") String effectId,
      @Param("operationName") String operationName);
}
