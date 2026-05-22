package net.firedevops.firemud.automationscripting.service.impl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScriptVersionServiceImplTest {
  @Test
  void notifyUpdateDelegatesToTemporalTrackingWhenAvailable() {
    ScriptPatchVersionCommandService commandService = mock(ScriptPatchVersionCommandService.class);
    TemporalScriptPatchReadinessOrchestrator orchestrator =
        mock(TemporalScriptPatchReadinessOrchestrator.class);
    ScriptVersionServiceImpl service =
        new ScriptVersionServiceImpl(commandService, Optional.of(orchestrator));

    service.notifyUpdate("1", "patch-1", List.of("guard-script"));

    verify(commandService).notifyUpdate("1", "patch-1", List.of("guard-script"));
    verify(orchestrator).startTracking("1", "patch-1");
  }

  @Test
  void notifyUpdateSkipsTemporalTrackingForEmptyPatch() {
    ScriptPatchVersionCommandService commandService = mock(ScriptPatchVersionCommandService.class);
    TemporalScriptPatchReadinessOrchestrator orchestrator =
        mock(TemporalScriptPatchReadinessOrchestrator.class);
    ScriptVersionServiceImpl service =
        new ScriptVersionServiceImpl(commandService, Optional.of(orchestrator));

    service.notifyUpdate("1", "patch-1", List.of());

    verify(commandService).notifyUpdate("1", "patch-1", List.of());
    org.mockito.Mockito.verifyNoInteractions(orchestrator);
  }
}
