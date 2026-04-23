package net.firedevops.firemud.automationscripting.repository;

import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptDefinitionRepository extends JpaRepository<ScriptDefinition, Long> {
  java.util.Optional<ScriptDefinition> findByTenantIdAndScriptVersionAndName(
      Long tenantId, String scriptVersion, String name);

  java.util.List<ScriptDefinition> findByTenantIdAndNameIn(
      Long tenantId, java.util.List<String> names);

  java.util.List<ScriptDefinition> findByTenantIdAndScriptVersionOrderByNameAsc(
      Long tenantId, String scriptVersion);

  java.util.List<ScriptDefinition> findByTenantIdOrderByNameAscScriptVersionAsc(Long tenantId);
}
