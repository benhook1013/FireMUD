package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForPluginVersionRequest;
import net.firedevops.firemud.gamesession.v1.PurgeQueuedTickCommandsForScriptPatchRequest;
import org.junit.jupiter.api.Test;

class GameSessionOperatorControlPlaneServiceTest {
  @Test
  void rejectsBlankScriptPatchRollbackPurgeReasonBeforePersistence() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameSessionOperatorControlPlaneService service = service(gameInstanceRepository, tickService);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.purgeQueuedTickCommandsForScriptPatch(
                    1L,
                    7L,
                    PurgeQueuedTickCommandsForScriptPatchRequest.newBuilder()
                        .setScriptPatchVersion("patch-1")
                        .setControlPlaneRequestId("request-1")
                        .setActorPrincipal("operator")
                        .setReason("   ")
                        .build()))
        .withMessage("reason is required");

    verifyNoInteractions(gameInstanceRepository, tickService);
  }

  @Test
  void rejectsBlankPluginRollbackPurgeReasonBeforePersistence() {
    GameInstanceRepository gameInstanceRepository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    GameSessionOperatorControlPlaneService service = service(gameInstanceRepository, tickService);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.purgeQueuedTickCommandsForPluginVersion(
                    1L,
                    7L,
                    PurgeQueuedTickCommandsForPluginVersionRequest.newBuilder()
                        .setPluginId("plugin-1")
                        .setPluginVersionId("plugin-v1")
                        .setControlPlaneRequestId("request-1")
                        .setActorPrincipal("operator")
                        .setReason("")
                        .build()))
        .withMessage("reason is required");

    verifyNoInteractions(gameInstanceRepository, tickService);
  }

  private GameSessionOperatorControlPlaneService service(
      GameInstanceRepository gameInstanceRepository, TickService tickService) {
    return new GameSessionOperatorControlPlaneService(
        gameInstanceRepository,
        tickService,
        mock(GameDesignClient.class),
        mock(GameSessionProperties.class));
  }
}
