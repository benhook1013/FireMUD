package net.firedevops.firemud.entitymanagement.repository;

import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.EquipmentSlotDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentSlotDefinitionRepository
    extends JpaRepository<EquipmentSlotDefinition, Long> {
  boolean existsByTenantIdAndVersionId(Long tenantId, Long versionId);

  Optional<EquipmentSlotDefinition> findByTenantIdAndVersionIdAndSlotKey(
      Long tenantId, Long versionId, String slotKey);
}
