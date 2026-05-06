package net.firedevops.firemud.automationscripting.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchReadinessProjection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScriptPatchReadinessProjectionRepository
    extends JpaRepository<ScriptPatchReadinessProjection, Long> {
  Optional<ScriptPatchReadinessProjection> findByTenantIdAndScriptPatchVersion(
      String tenantId, String scriptPatchVersion);

  List<ScriptPatchReadinessProjection> findByTenantIdOrderByLastChangedAtDesc(String tenantId);

  List<ScriptPatchReadinessProjection> findByTenantIdAndReadinessStatusInOrderByLastChangedAtAsc(
      String tenantId, Collection<String> statuses);
}
