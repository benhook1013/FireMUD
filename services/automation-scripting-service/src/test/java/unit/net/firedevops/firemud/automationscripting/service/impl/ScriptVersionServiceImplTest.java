package net.firedevops.firemud.automationscripting.service.impl;

import static org.mockito.Mockito.*;

import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScriptVersionServiceImplTest {
  private ScriptDefinitionRepository repository;
  private ScriptVersionServiceImpl service;

  @BeforeEach
  void setup() {
    repository = mock(ScriptDefinitionRepository.class);
    service = new ScriptVersionServiceImpl(repository);
  }

  @Test
  void notifyUpdateReloadsScripts() {
    ScriptDefinition def = new ScriptDefinition();
    def.setTenantId(1L);
    def.setName("npc-barkeep");
    def.setDefinition("{}");
    when(repository.findByTenantIdAndNameIn(1L, List.of("npc-barkeep"))).thenReturn(List.of(def));

    service.notifyUpdate("1", "v1-script.1", List.of("npc-barkeep"));

    verify(repository).findByTenantIdAndNameIn(1L, List.of("npc-barkeep"));
  }
}
