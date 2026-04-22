package net.firedevops.firemud.automationscripting.repository;

import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptEventBindingRepository extends JpaRepository<ScriptEventBinding, Long> {
  void deleteByTenantIdAndScriptPatchVersionAndScriptId(
      Long tenantId, String scriptPatchVersion, String scriptId);

  List<ScriptEventBinding>
      findByTenantIdAndScriptPatchVersionAndEventTypeAndEventSchemaVersionAndEnabledTrueOrderByPriorityAscScriptIdAsc(
          Long tenantId, String scriptPatchVersion, String eventType, String eventSchemaVersion);

  List<ScriptEventBinding>
      findByTenantIdOrderByScriptPatchVersionAscEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
          Long tenantId);

  List<ScriptEventBinding>
      findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
          Long tenantId, String scriptPatchVersion);
}
