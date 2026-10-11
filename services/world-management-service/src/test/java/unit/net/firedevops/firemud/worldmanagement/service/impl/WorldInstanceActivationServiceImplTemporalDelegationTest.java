package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidenceProvider;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;
import net.firedevops.firemud.worldmanagement.entity.WorldInstance;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import net.firedevops.firemud.worldmanagement.service.WorldLifecycleCommandService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WorldInstanceActivationServiceImplTemporalDelegationTest {
  @Test
  void prepareDelegatesToTemporalWhenAvailable() {
    WorldLifecycleCommandService commandService = Mockito.mock(WorldLifecycleCommandService.class);
    TemporalWorldLifecycleOrchestrator orchestrator =
        Mockito.mock(TemporalWorldLifecycleOrchestrator.class);
    WorldInstanceActivationServiceImpl service =
        new WorldInstanceActivationServiceImpl(commandService, Optional.of(orchestrator));
    PreparedWorldInstanceRequest request =
        new PreparedWorldInstanceRequest(
            42L, 101L, 7L, "cp-1", "ld-1", 11L, null, "{}", "genrev-11", 77L, "prb:42:11:77", 77L);
    WorldInstanceLifecycleSnapshotDto snapshot =
        new WorldInstanceLifecycleSnapshotDto(
            42L,
            101L,
            7L,
            "cp-1",
            "ld-1",
            11L,
            77L,
            "genrev-11",
            "prb:42:11:77",
            77L,
            1L,
            "PREPARING");
    when(orchestrator.prepareWorldInstance(request)).thenReturn(snapshot);

    WorldInstanceLifecycleSnapshotDto result = service.prepareWorldInstance(request);

    assertSame(snapshot, result);
    verify(orchestrator).prepareWorldInstance(request);
  }

  @Test
  void getLifecycleFallsBackToCommandServiceWhenTemporalUnavailable() {
    WorldLifecycleCommandService commandService = Mockito.mock(WorldLifecycleCommandService.class);
    WorldInstanceActivationServiceImpl service =
        new WorldInstanceActivationServiceImpl(commandService, Optional.empty());
    WorldInstanceLifecycleSnapshotDto snapshot =
        new WorldInstanceLifecycleSnapshotDto(
            42L,
            101L,
            7L,
            "cp-1",
            "ld-1",
            11L,
            77L,
            "genrev-11",
            "prb:42:11:77",
            77L,
            2L,
            "ACTIVE");
    when(commandService.getWorldInstanceLifecycle(42L, 101L)).thenReturn(snapshot);

    WorldInstanceLifecycleSnapshotDto result = service.getWorldInstanceLifecycle(42L, 101L);

    assertSame(snapshot, result);
    verify(commandService).getWorldInstanceLifecycle(42L, 101L);
  }

  @Test
  void configuredFixtureFailsClosedBeforeTemporalPrepare() {
    WorldLifecycleCommandService commandService = mock(WorldLifecycleCommandService.class);
    TemporalWorldLifecycleOrchestrator orchestrator =
        mock(TemporalWorldLifecycleOrchestrator.class);
    RunOwnedWorldLaunchEvidenceProvider evidenceProvider =
        mock(RunOwnedWorldLaunchEvidenceProvider.class);
    when(evidenceProvider.isEnabled()).thenReturn(true);
    WorldInstanceActivationServiceImpl service =
        new WorldInstanceActivationServiceImpl(
            commandService,
            Optional.of(orchestrator),
            evidenceProvider,
            mock(WorldInstanceRepository.class));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.prepareWorldInstance(
                    new PreparedWorldInstanceRequest(
                        42L,
                        101L,
                        7L,
                        "cp-1",
                        "ld-1",
                        11L,
                        null,
                        "{}",
                        "genrev-11",
                        77L,
                        "8ce42642-53ae-4d07-aab8-37c43ce66e0c",
                        77L)));

    assertTrue(error.getMessage().startsWith("RUN_OWNED_WORLD_LAUNCH_TEMPORAL_UNSUPPORTED:"));
    verifyNoInteractions(orchestrator, commandService);
  }

  @Test
  void disabledFixtureStillRejectsTemporalForAnAlreadyBoundWorldRow() {
    WorldLifecycleCommandService commandService = mock(WorldLifecycleCommandService.class);
    TemporalWorldLifecycleOrchestrator orchestrator =
        mock(TemporalWorldLifecycleOrchestrator.class);
    RunOwnedWorldLaunchEvidenceProvider evidenceProvider =
        mock(RunOwnedWorldLaunchEvidenceProvider.class);
    WorldInstanceRepository repository = mock(WorldInstanceRepository.class);
    when(evidenceProvider.isEnabled()).thenReturn(false);
    WorldInstance bound = new WorldInstance();
    bound.setRunOwnedFixtureBindingSha256("a".repeat(64));
    when(repository.findByTenantIdAndGameInstanceId(42L, 101L)).thenReturn(Optional.of(bound));
    WorldInstanceActivationServiceImpl service =
        new WorldInstanceActivationServiceImpl(
            commandService, Optional.of(orchestrator), evidenceProvider, repository);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.activatePreparedWorldInstance(42L, 101L, 1L));

    assertTrue(error.getMessage().startsWith("RUN_OWNED_WORLD_LAUNCH_TEMPORAL_UNSUPPORTED:"));
    verifyNoInteractions(orchestrator, commandService);
  }
}
