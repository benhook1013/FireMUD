package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class DatabaseGameplayCommandRecoveryServiceTest {
  @Test
  void reDrivesAcceptedButUnstagedCommandsWithoutTerminalizingThem() {
    GameplayCommand command = new GameplayCommand();
    command.setId(10L);
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
    when(repository.findAcceptedButUnstagedPage(cutoff, null, 0L, 100))
        .thenReturn(List.of(command));
    DatabaseGameplayCommandRecoveryService service =
        new DatabaseGameplayCommandRecoveryService(repository, queue, Runnable::run);

    int recovered = service.convergeAcceptedButUnstagedCommands(cutoff);

    assertThat(recovered).isEqualTo(1);
    assertThat(command.getExecutionOutcome()).isEqualTo("ACCEPTED");
    verify(queue).enqueueCommand(1L, 2L, "cmd-1", "look", false);
    verify(repository)
        .findAcceptedButUnstagedPage(cutoff, command.getAcceptedAt(), command.getId(), 100);
    verify(repository, never()).saveAll(Mockito.any());
  }

  @Test
  void leavesAcceptedCommandRetryableWhenMaterializationFails() {
    GameplayCommand command = new GameplayCommand();
    command.setId(20L);
    command.setCommandId("cmd-2");
    command.setTenantId(1L);
    command.setGameInstanceId(2L);
    command.setCommandText("look");
    command.setExecutionOutcome("ACCEPTED");
    command.setAcceptedAt(Instant.parse("2026-04-15T00:00:00Z"));
    GameplayCommandRepository repository = Mockito.mock(GameplayCommandRepository.class);
    TickQueueControlService queue = Mockito.mock(TickQueueControlService.class);
    Instant cutoff = Instant.parse("2026-04-15T00:00:01Z");
    when(repository.findAcceptedButUnstagedPage(cutoff, null, 0L, 100))
        .thenReturn(List.of(command));
    Mockito.doThrow(new TickQueueControlService.QueueUnavailableException("redis unavailable"))
        .when(queue)
        .enqueueCommand(1L, 2L, "cmd-2", "look", false);

    DatabaseGameplayCommandRecoveryService service =
        new DatabaseGameplayCommandRecoveryService(repository, queue, Runnable::run);

    assertThat(service.convergeAcceptedButUnstagedCommands(cutoff)).isZero();
    assertThat(command.getExecutionOutcome()).isEqualTo("ACCEPTED");
    verify(repository)
        .findAcceptedButUnstagedPage(cutoff, command.getAcceptedAt(), command.getId(), 100);
    verify(repository, never()).saveAll(Mockito.any());
  }

  @Test
  void pagesEqualAcceptedTimestampsWithIdTieBreaker() {
    Instant acceptedAt = Instant.parse("2026-04-15T00:00:00Z");
    Instant cutoff = Instant.parse("2026-04-15T00:00:01Z");
    List<GameplayCommand> firstPage = new ArrayList<>();
    List<String> expectedCommandIds = new ArrayList<>();
    List<GameplayCommand> commands = new ArrayList<>();
    for (long id = 1L; id <= 101L; id++) {
      GameplayCommand command = new GameplayCommand();
      command.setId(id);
      command.setCommandId("cmd-" + id);
      command.setTenantId(1L);
      command.setGameInstanceId(2L);
      command.setCommandText("look");
      command.setExecutionOutcome("ACCEPTED");
      command.setAcceptedAt(acceptedAt);
      commands.add(command);
      expectedCommandIds.add(command.getCommandId());
      if (id <= 100L) {
        firstPage.add(command);
      }
    }

    GameplayCommandRepository repository = Mockito.mock(GameplayCommandRepository.class);
    TickQueueControlService queue = Mockito.mock(TickQueueControlService.class);
    when(repository.findAcceptedButUnstagedPage(cutoff, null, 0L, 100)).thenReturn(firstPage);
    when(repository.findAcceptedButUnstagedPage(cutoff, acceptedAt, 100L, 100))
        .thenReturn(List.of(commands.get(100)));
    when(repository.findAcceptedButUnstagedPage(cutoff, acceptedAt, 101L, 100))
        .thenReturn(List.of());
    DatabaseGameplayCommandRecoveryService service =
        new DatabaseGameplayCommandRecoveryService(repository, queue, Runnable::run);

    assertThat(service.convergeAcceptedButUnstagedCommands(cutoff)).isEqualTo(101);

    ArgumentCaptor<String> commandIds = ArgumentCaptor.forClass(String.class);
    verify(queue, times(101))
        .enqueueCommand(
            Mockito.eq(1L),
            Mockito.eq(2L),
            commandIds.capture(),
            Mockito.eq("look"),
            Mockito.eq(false));
    assertThat(commandIds.getAllValues()).containsExactlyElementsOf(expectedCommandIds);
    verify(repository).findAcceptedButUnstagedPage(cutoff, acceptedAt, 100L, 100);
    verify(repository).findAcceptedButUnstagedPage(cutoff, acceptedAt, 101L, 100);
  }

  @Test
  void startupListenerReturnsAfterSubmittingRecovery() {
    GameplayCommandRepository repository = Mockito.mock(GameplayCommandRepository.class);
    TickQueueControlService queue = Mockito.mock(TickQueueControlService.class);
    Executor executor = Mockito.mock(Executor.class);
    DatabaseGameplayCommandRecoveryService service =
        new DatabaseGameplayCommandRecoveryService(repository, queue, executor);

    service.convergeAcceptedButUnstagedCommandsOnStartup();

    ArgumentCaptor<Runnable> recovery = ArgumentCaptor.forClass(Runnable.class);
    verify(executor).execute(recovery.capture());
    verifyNoInteractions(repository, queue);
    assertThat(recovery.getValue()).isNotNull();
  }
}
