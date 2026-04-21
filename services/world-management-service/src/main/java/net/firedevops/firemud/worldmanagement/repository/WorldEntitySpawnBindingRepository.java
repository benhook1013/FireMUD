package net.firedevops.firemud.worldmanagement.repository;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.WorldEntitySpawnBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorldEntitySpawnBindingRepository
    extends JpaRepository<WorldEntitySpawnBinding, Long> {
  Optional<WorldEntitySpawnBinding> findByTenantIdAndVersionIdAndId(
      Long tenantId, Long versionId, Long id);

  Optional<WorldEntitySpawnBinding>
      findByTenantIdAndVersionIdAndRoomIdAndEntityTemplateTypeAndEntityTemplateId(
          Long tenantId,
          Long versionId,
          Long roomId,
          String entityTemplateType,
          Long entityTemplateId);

  List<WorldEntitySpawnBinding> findByTenantIdAndVersionIdOrderByIdAsc(
      Long tenantId, Long versionId);
}
