package net.firedevops.firemud.automationscripting.repository;

import java.util.Collection;
import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptScheduleDefinitionRepository
    extends JpaRepository<ScriptScheduleDefinition, Long> {
  List<ScriptScheduleDefinition>
      findByTenantIdAndScriptPatchVersionOrderByScriptIdAscEventTypeAscScheduleDefinitionIdAsc(
          Long tenantId, String scriptPatchVersion);

  void deleteByTenantIdAndScriptPatchVersionAndScriptIdIn(
      Long tenantId, String scriptPatchVersion, Collection<String> scriptIds);
}
