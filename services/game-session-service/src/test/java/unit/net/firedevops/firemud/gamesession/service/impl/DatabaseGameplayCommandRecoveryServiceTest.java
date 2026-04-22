package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
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
  void convergesAcceptedButUnstagedCommandsToTerminalLostState() {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId("cmd-1");
    command.setExecutionOutcome("ACCEPTED");
    command.setGameplayResult("PENDING");
    command.setAcceptedAt(Instant.parse("2026-04-15T00:00:00Z"));
    GameplayCommandRepository repository = Mockito.mock(GameplayCommandRepository.class);
    Instant cutoff = Instant.parse("2026-04-15T00:00:01Z");
    when(repository.findByExecutionOutcomeAndStagedAtIsNullAndAcceptedAtBefore("ACCEPTED", cutoff))
        .thenReturn(List.of(command));
    DatabaseGameplayCommandRecoveryService service =
        new DatabaseGameplayCommandRecoveryService(repository);

    int recovered = service.convergeAcceptedButUnstagedCommands(cutoff);

    assertThat(recovered).isEqualTo(1);
    assertThat(command.getExecutionOutcome()).isEqualTo("LOST_BEFORE_STAGING");
    assertThat(command.getGameplayResult()).isEqualTo("NOT_APPLIED");
    assertThat(command.getFailureCode()).isEqualTo("LOST_BEFORE_STAGING");
    assertThat(command.getCompletedAt()).isNotNull();
    verify(repository).saveAll(List.of(command));
  }
}
