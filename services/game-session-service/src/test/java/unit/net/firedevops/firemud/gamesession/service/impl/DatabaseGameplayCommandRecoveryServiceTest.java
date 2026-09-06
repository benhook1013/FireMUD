package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DatabaseGameplayCommandRecoveryServiceTest {
  @Test
  void reDrivesAcceptedButUnstagedCommandsWithoutTerminalizingThem() {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId("cmd-1");
    command.setTenantId(1L);
    command.setGameInstanceId(2L);
    command.setCommandText("look");
    command.setExecutionOutcome("ACCEPTED");
    command.setGameplayResult("PENDING");
    command.setAcceptedAt(Instant.parse("2026-04-15T00:00:00Z"));
    GameplayCommandRepository repository = Mockito.mock(GameplayCommandRepository.class);
    TickQueueControlService queue = Mockito.mock(TickQueueControlService.class);
    Instant cutoff = Instant.parse("2026-04-15T00:00:01Z");
    when(repository.findByExecutionOutcomeAndStagedAtIsNullAndAcceptedAtBefore("ACCEPTED", cutoff))
        .thenReturn(List.of(command));
    DatabaseGameplayCommandRecoveryService service =
        new DatabaseGameplayCommandRecoveryService(repository, queue);

    int recovered = service.convergeAcceptedButUnstagedCommands(cutoff);

    assertThat(recovered).isEqualTo(1);
    assertThat(command.getExecutionOutcome()).isEqualTo("ACCEPTED");
    verify(queue).enqueueCommand(1L, 2L, "cmd-1", "look", false);
    verify(repository, never()).saveAll(Mockito.any());
  }

  @Test
  void leavesAcceptedCommandRetryableWhenMaterializationFails() {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId("cmd-2");
    command.setTenantId(1L);
    command.setGameInstanceId(2L);
    command.setCommandText("look");
    command.setExecutionOutcome("ACCEPTED");
    command.setAcceptedAt(Instant.parse("2026-04-15T00:00:00Z"));
    GameplayCommandRepository repository = Mockito.mock(GameplayCommandRepository.class);
    TickQueueControlService queue = Mockito.mock(TickQueueControlService.class);
    Instant cutoff = Instant.parse("2026-04-15T00:00:01Z");
    when(repository.findByExecutionOutcomeAndStagedAtIsNullAndAcceptedAtBefore("ACCEPTED", cutoff))
        .thenReturn(List.of(command));
    Mockito.doThrow(new TickQueueControlService.QueueUnavailableException("redis unavailable"))
        .when(queue)
        .enqueueCommand(1L, 2L, "cmd-2", "look", false);

    DatabaseGameplayCommandRecoveryService service =
        new DatabaseGameplayCommandRecoveryService(repository, queue);

    assertThat(service.convergeAcceptedButUnstagedCommands(cutoff)).isZero();
    assertThat(command.getExecutionOutcome()).isEqualTo("ACCEPTED");
    verify(repository, never()).saveAll(Mockito.any());
  }
}
