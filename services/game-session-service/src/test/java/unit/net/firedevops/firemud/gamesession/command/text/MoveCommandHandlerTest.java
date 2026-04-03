package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamelogic.v1.MoveResult;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.config.MovementProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
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
  private final MovementProperties movementProperties = new MovementProperties(true);
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
            movementProperties,
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
    when(gameLogicClient.resolveMove("22", "42", "911", "R-1021", "north"))
        .thenReturn(
            MoveResult.newBuilder().setSuccess(true).setDestinationLook(destinationLook).build());
    PlayerOutput destinationOutput = PlayerOutput.view("Crafting Hall of Ember");
    when(lookCommandHandler.toPlayerOutput(any(SessionContext.class), Mockito.eq(destinationLook)))
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
    verify(lookCommandHandler).toPlayerOutput(contextCaptor.getValue(), destinationLook);
  }

  @Test
  void moveFailurePropagatesInvalidExitWithoutSavingSession() {
    when(gameLogicClient.resolveMove("22", "42", "911", "R-1021", "west"))
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
    verify(sessionContextService, never()).save(any());
    verify(lookCommandHandler, never()).toPlayerOutput(any(), any());
  }

  @Test
  void moveCanSkipAutomaticLookWhenConfiguredOff() {
    handler =
        new MoveCommandHandler(
            gameLogicClient,
            sessionContextService,
            lookCommandHandler,
            gameLogicProperties,
            new MovementProperties(false),
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
    when(gameLogicClient.resolveMove("22", "42", "911", "R-1021", "north"))
        .thenReturn(
            MoveResult.newBuilder().setSuccess(true).setDestinationLook(destinationLook).build());

    MoveCommandHandlingResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.MOVE, java.util.List.of("north"), "north"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.responseOutput()).isNull();
    verify(sessionContextService).save(any(SessionContext.class));
    verify(lookCommandHandler, never()).toPlayerOutput(any(), any());
  }
}
