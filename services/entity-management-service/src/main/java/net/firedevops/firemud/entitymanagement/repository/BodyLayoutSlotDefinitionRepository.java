package net.firedevops.firemud.entitymanagement.repository;

import net.firedevops.firemud.entitymanagement.entity.BodyLayoutSlotDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BodyLayoutSlotDefinitionRepository
    extends JpaRepository<BodyLayoutSlotDefinition, Long> {
  boolean existsByTenantIdAndVersionIdAndBodyLayoutKey(
      Long tenantId, Long versionId, String bodyLayoutKey);

  boolean existsByTenantIdAndVersionIdAndBodyLayoutKeyAndSlotKey(
      Long tenantId, Long versionId, String bodyLayoutKey, String slotKey);
}
