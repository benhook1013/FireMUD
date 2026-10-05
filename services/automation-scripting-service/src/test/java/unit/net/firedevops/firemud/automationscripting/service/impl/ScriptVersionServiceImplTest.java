package net.firedevops.firemud.automationscripting.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    when(commandService.notifyUpdate("1", 1L, "patch-1", List.of("guard-script"))).thenReturn(true);

    assertTrue(service.notifyUpdate("1", 1L, "patch-1", List.of("guard-script")));

    verify(commandService).notifyUpdate("1", 1L, "patch-1", List.of("guard-script"));
    verify(orchestrator).startTracking("1", "patch-1");
  }

  @Test
  void notifyUpdateSkipsTemporalTrackingWhenReadinessIsTerminal() {
    ScriptPatchVersionCommandService commandService = mock(ScriptPatchVersionCommandService.class);
    TemporalScriptPatchReadinessOrchestrator orchestrator =
        mock(TemporalScriptPatchReadinessOrchestrator.class);
    ScriptVersionServiceImpl service =
        new ScriptVersionServiceImpl(commandService, Optional.of(orchestrator));

    when(commandService.notifyUpdate("1", 1L, "patch-1", List.of("guard-script")))
        .thenReturn(false);

    assertFalse(service.notifyUpdate("1", 1L, "patch-1", List.of("guard-script")));

    verify(commandService).notifyUpdate("1", 1L, "patch-1", List.of("guard-script"));
    org.mockito.Mockito.verifyNoInteractions(orchestrator);
  }

  @Test
  void notifyUpdatePropagatesEmptyPatchRejectionWithoutTemporalTracking() {
    ScriptPatchVersionCommandService commandService = mock(ScriptPatchVersionCommandService.class);
    TemporalScriptPatchReadinessOrchestrator orchestrator =
        mock(TemporalScriptPatchReadinessOrchestrator.class);
    ScriptVersionServiceImpl service =
        new ScriptVersionServiceImpl(commandService, Optional.of(orchestrator));

    when(commandService.notifyUpdate("1", 1L, "patch-1", List.of()))
        .thenThrow(new IllegalArgumentException("zero_handler_manifest_unverifiable"));

    assertThrows(
        IllegalArgumentException.class, () -> service.notifyUpdate("1", 1L, "patch-1", List.of()));

    verify(commandService).notifyUpdate("1", 1L, "patch-1", List.of());
    org.mockito.Mockito.verifyNoInteractions(orchestrator);
  }
}
