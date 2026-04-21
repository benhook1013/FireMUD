package net.firedevops.firemud.entitymanagement.repository;

import net.firedevops.firemud.entitymanagement.entity.Npc;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NpcRepository extends JpaRepository<Npc, Long> {
  java.util.Optional<Npc> findByTenantIdAndVersionIdAndId(Long tenantId, Long versionId, Long id);

  java.util.List<Npc> findByTenantIdOrderByIdAsc(Long tenantId);

  java.util.List<Npc> findByTenantIdAndVersionIdOrderByIdAsc(Long tenantId, Long versionId);
}
