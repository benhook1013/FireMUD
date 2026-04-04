package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamelogic.v1.MoveResult;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.config.MovementProperties;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.config.WorldTopologyProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.LookViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class MoveCommandHandlerTest {
  private final GameLogicClient gameLogicClient = Mockito.mock(GameLogicClient.class);
  private final SessionContextService sessionContextService =
      Mockito.mock(SessionContextService.class);
  private final LookCommandHandler lookCommandHandler = Mockito.mock(LookCommandHandler.class);
  private final GameLogicProperties gameLogicProperties = new GameLogicProperties();
  private final EffectiveSettingsResolver settingsResolver =
      new EffectiveSettingsResolver(
          new PresentationProperties(),
          new MovementProperties(true),
          new WorldTopologyProperties(),
          (tenantId, gameInstanceId) -> ScopedSettingsSnapshot.empty());
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private MoveCommandHandler handler;
  private SessionContext context;

  @BeforeEach
  void setUp() {
    handler =
        new MoveCommandHandler(
            gameLogicClient,
            sessionContextService,
            lookCommandHandler,
            gameLogicProperties,
            settingsResolver,
            meterRegistry);
    context =
        new SessionContext(
            42L, 22L, 123L, "emberline@example.com", 911L, "Emberline", 7L, "R-1021", "jwt-token");
  }

  @Test
  void moveSuccessUpdatesSessionAndReturnsStructuredDestinationLook() {
    LookResult destinationLook =
        LookResult.newBuilder()
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId("22")
                    .setGameInstanceId("game-inst-7")
                    .setRoomInstanceId("R-2045")
                    .build())
            .setRoomName("Crafting Hall of Ember")
            .build();
    when(gameLogicClient.resolveMove("22", "42", "911", "R-1021", "north", ""))
        .thenReturn(
            MoveResult.newBuilder().setSuccess(true).setDestinationLook(destinationLook).build());
    PlayerOutput destinationOutput =
        PlayerOutput.view(
            new LookViewOutput(
                "R-2045",
                "Crafting Hall of Ember",
                "Crafting Hall of Ember",
                "A forge-lit hall full of ember glow.",
                true,
                LookViewOutput.RefreshReason.MOVE_REFRESH,
                java.util.List.of(),
                java.util.List.of()));
    when(lookCommandHandler.toPlayerOutput(
            any(SessionContext.class),
            Mockito.eq(destinationLook),
            Mockito.eq(true),
            Mockito.eq(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
                    .MOVE_REFRESH),
            Mockito.eq(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.BriefRenderingHint
                    .PREFER_BRIEF)))
        .thenReturn(destinationOutput);

    MoveCommandHandlingResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.MOVE, java.util.List.of("north"), "north"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.responseOutput()).isEqualTo(destinationOutput);

    ArgumentCaptor<SessionContext> contextCaptor = ArgumentCaptor.forClass(SessionContext.class);
    verify(sessionContextService).save(contextCaptor.capture());
    assertThat(contextCaptor.getValue().roomInstanceId()).isEqualTo("R-2045");
    assertThat(contextCaptor.getValue().loginName()).isEqualTo("emberline@example.com");
    assertThat(contextCaptor.getValue().characterName()).isEqualTo("Emberline");
    verify(lookCommandHandler)
        .toPlayerOutput(
            contextCaptor.getValue(),
            destinationLook,
            true,
            net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
                .MOVE_REFRESH,
            net.firedevops.firemud.gamesession.presentation.LookViewOutput.BriefRenderingHint
                .PREFER_BRIEF);
  }

  @Test
  void moveFailurePropagatesInvalidExitWithoutSavingSession() {
    when(gameLogicClient.resolveMove("22", "42", "911", "R-1021", "west", ""))
        .thenReturn(
            MoveResult.newBuilder()
                .setSuccess(false)
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("INVALID_EXIT")
                        .setMessage("No exit WEST from room R-1021")
                        .build())
                .build());

    MoveCommandHandlingResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.MOVE, java.util.List.of("west"), "MOVE west"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_EXIT");
    assertThat(result.responseOutput()).isNotNull();
    assertThat(
            new TextPlayerOutputRenderer(new PresentationProperties())
                .render(result.responseOutput(), "fr"))
        .isEqualTo("ERROR INVALID_EXIT Aucune sortie WEST depuis la salle R-1021.");
    verify(sessionContextService, never()).save(any());
    verify(lookCommandHandler, never()).toPlayerOutput(any(), any(), anyBoolean(), any());
  }

  @Test
  void moveCanSkipAutomaticLookWhenConfiguredOff() {
    handler =
        new MoveCommandHandler(
            gameLogicClient,
            sessionContextService,
            lookCommandHandler,
            gameLogicProperties,
            new EffectiveSettingsResolver(
                new PresentationProperties(),
                new MovementProperties(false),
                new WorldTopologyProperties(),
                (tenantId, gameInstanceId) -> ScopedSettingsSnapshot.empty()),
            meterRegistry);
    LookResult destinationLook =
        LookResult.newBuilder()
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId("22")
                    .setGameInstanceId("game-inst-7")
                    .setRoomInstanceId("R-2045")
                    .build())
            .setRoomName("Crafting Hall of Ember")
            .build();
    when(gameLogicClient.resolveMove("22", "42", "911", "R-1021", "north", ""))
        .thenReturn(
            MoveResult.newBuilder().setSuccess(true).setDestinationLook(destinationLook).build());

    MoveCommandHandlingResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.MOVE, java.util.List.of("north"), "north"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.responseOutput()).isNull();
    verify(sessionContextService).save(any(SessionContext.class));
    verify(lookCommandHandler, never()).toPlayerOutput(any(), any(), anyBoolean(), any());
  }
}
