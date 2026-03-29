package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import net.firedevops.firemud.gamelogic.v1.BroadcastSayResponse;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SayCommandHandlerTest {
  private final GameLogicClient gameLogicClient = Mockito.mock(GameLogicClient.class);
  private final GameLogicProperties gameLogicProperties = new GameLogicProperties();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private SayCommandHandler handler;
  private final SessionContext sessionContext =
      new SessionContext(1L, 22L, 123L, 911L, 0L, "room-7", "jwt-token");

  @BeforeEach
  void setUp() {
    handler = new SayCommandHandler(gameLogicClient, gameLogicProperties, meterRegistry);
  }

  @Test
  void successReturnsCanonicalText() {
    BroadcastSayResponse response =
        BroadcastSayResponse.newBuilder()
            .setSuccess(true)
            .setMessage("Hello travelers")
            .addDeliveredTo("Emberline")
            .addDeliveredTo("Sora")
            .build();
    when(gameLogicClient.broadcastSay(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(response);

    SayCommandHandlingResult result =
        handler.handle(
            sessionContext,
            new TextCommand(
                TextCommandType.SAY, List.of("Hello travelers"), "SAY Hello travelers"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.responseText()).isNotNull();
    List<String> lines = result.responseText().lines().map(String::trim).toList();
    assertThat(lines)
        .containsExactly(
            "Speaker: Emberline", "Delivered-To: Emberline, Sora", "Message: Hello travelers");
    Mockito.verify(gameLogicClient)
        .broadcastSay("22", "1", "911", "room-7", "SAY", "Hello travelers");
  }

  @Test
  void missingMessageReturnsInvalidArgument() {
    SayCommandHandlingResult result =
        handler.handle(sessionContext, new TextCommand(TextCommandType.SAY, List.of(), "SAY"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
  }

  @Test
  void underlyingFailurePropagates() {
    ErrorDetail error =
        ErrorDetail.newBuilder().setCode("PERMISSION_DENIED").setMessage("silenced").build();
    when(gameLogicClient.broadcastSay(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()))
        .thenReturn(
            BroadcastSayResponse.newBuilder()
                .setSuccess(false)
                .setError(error)
                .setMessage("unused")
                .build());

    SayCommandHandlingResult result =
        handler.handle(
            sessionContext, new TextCommand(TextCommandType.SAY, List.of("Hello"), "SAY Hello"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("SAY_NOT_DELIVERED");
    assertThat(result.commandResult().errorMessage()).isEqualTo("silenced");
  }
}
