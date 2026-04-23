package net.firedevops.firemud.automationscripting.repository;

import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptScheduleInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptScheduleInstanceRepository
    extends JpaRepository<ScriptScheduleInstance, Long> {
  List<ScriptScheduleInstance>
      findByTenantIdAndGameInstanceIdOrderByUpdatedAtDescScheduleDefinitionIdAsc(
          String tenantId, String gameInstanceId);

  List<ScriptScheduleInstance>
      findByTenantIdAndGameInstanceIdAndScriptPatchVersionOrderByUpdatedAtDescScheduleDefinitionIdAsc(
          String tenantId, String gameInstanceId, String scriptPatchVersion);

  void deleteByTenantIdAndGameInstanceId(String tenantId, String gameInstanceId);
}
