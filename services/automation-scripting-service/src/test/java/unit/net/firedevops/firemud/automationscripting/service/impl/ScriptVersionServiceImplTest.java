package net.firedevops.firemud.automationscripting.service.impl;

import static org.mockito.Mockito.*;

import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleDefinitionService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScriptVersionServiceImplTest {
  private ScriptDefinitionRepository repository;
  private ScriptScheduleDefinitionService scheduleDefinitionService;
  private ScriptScheduleInstanceService scheduleInstanceService;
  private ScriptVersionServiceImpl service;

  @BeforeEach
  void setup() {
    repository = mock(ScriptDefinitionRepository.class);
    scheduleDefinitionService = mock(ScriptScheduleDefinitionService.class);
    scheduleInstanceService = mock(ScriptScheduleInstanceService.class);
    service =
        new ScriptVersionServiceImpl(
            repository, scheduleDefinitionService, scheduleInstanceService);
  }

  @Test
  void notifyUpdateReloadsScriptsForPatchVersionAndRefreshesSchedules() {
    ScriptDefinition def = new ScriptDefinition();
    def.setTenantId(1L);
    def.setName("npc-barkeep");
    def.setDefinition("{}");
    when(repository.findByTenantIdAndScriptVersionAndNameIn(
            1L, "v1-script.1", List.of("npc-barkeep")))
        .thenReturn(List.of(def));

    service.notifyUpdate("1", "v1-script.1", List.of("npc-barkeep"));

    verify(repository)
        .findByTenantIdAndScriptVersionAndNameIn(1L, "v1-script.1", List.of("npc-barkeep"));
    verify(scheduleDefinitionService)
        .refreshPatchSchedules("1", "v1-script.1", List.of(def), List.of("npc-barkeep"));
    verify(scheduleInstanceService).reconcilePinnedPatchInstances("1", "v1-script.1");
  }
}
