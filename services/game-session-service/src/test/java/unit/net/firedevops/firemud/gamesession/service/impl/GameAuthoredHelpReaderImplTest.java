package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.GameAuthoredHelpReader;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GameAuthoredHelpReaderImplTest {
  private final GameInstanceRepository gameInstanceRepository =
      Mockito.mock(GameInstanceRepository.class);
  private final GameDesignClient gameDesignClient = Mockito.mock(GameDesignClient.class);
  private final GameAuthoredHelpReaderImpl reader =
      new GameAuthoredHelpReaderImpl(gameInstanceRepository, gameDesignClient);

  @Test
  void resolvesPublishedHelpUsingTheAdmittedInstancesTemplate() {
    GameInstance instance = new GameInstance();
    instance.setTenantId(22L);
    instance.setGameTemplateId(71L);
    SessionContext context = gameplayContext();
    whenInstance(7L, instance);
    Mockito.when(gameDesignClient.resolveAuthoredHelpTopic(22L, 71L, "moon"))
        .thenReturn(
            Optional.of(
                new GameAuthoredHelpReader.ResolvedTopic(
                    "Moon Shrine", "Follow the silver path.")));

    Optional<GameAuthoredHelpReader.ResolvedTopic> resolved = reader.resolve(context, "moon");

    assertThat(resolved)
        .contains(
            new GameAuthoredHelpReader.ResolvedTopic("Moon Shrine", "Follow the silver path."));
  }

  @Test
  void doesNotReadAuthoredHelpWhenTheRuntimeInstanceBelongsToAnotherTenant() {
    GameInstance instance = new GameInstance();
    instance.setTenantId(99L);
    instance.setGameTemplateId(71L);
    whenInstance(7L, instance);

    assertThat(reader.resolve(gameplayContext(), "moon")).isEmpty();

    Mockito.verifyNoInteractions(gameDesignClient);
  }

  private void whenInstance(long gameInstanceId, GameInstance instance) {
    Mockito.when(gameInstanceRepository.findById(gameInstanceId)).thenReturn(Optional.of(instance));
  }

  private SessionContext gameplayContext() {
    return new SessionContext(
        41L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 7L, "R-1", "jwt");
  }
}
