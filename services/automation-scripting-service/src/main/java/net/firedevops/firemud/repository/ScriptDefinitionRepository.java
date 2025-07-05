package net.firedevops.firemud.repository;

import net.firedevops.firemud.entity.ScriptDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptDefinitionRepository extends JpaRepository<ScriptDefinition, Long> {
}
