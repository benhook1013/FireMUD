package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import net.firedevops.firemud.entitymanagement.v1.ActorConditionState;
import net.firedevops.firemud.entitymanagement.v1.ApplyActorConditionResponse;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ActionStateCommandHandlerTest {
  private static final Instant NOW = Instant.parse("2026-04-24T00:00:00Z");
  private final GameLogicClient gameLogicClient = Mockito.mock(GameLogicClient.class);
  private final ActionStateCommandHandler handler =
      new ActionStateCommandHandler(gameLogicClient, Clock.fixed(NOW, ZoneOffset.UTC));
  private final SessionContext context =
      new SessionContext(42L, 22L, 7L, "demo@example.com", 91L, "Demo", 5L, "R-1", "jwt-token");

  @Test
  void blockAppliesShortLivedBlockingState() {
    when(gameLogicClient.applyActorCondition(
            eq(context),
            eq("blocking"),
            eq("ACTION_STATE"),
            eq("effect-1"),
            eq(NOW.plusSeconds(5)),
            Mockito.contains("block_mitigation")))
        .thenReturn(
            ApplyActorConditionResponse.newBuilder()
                .setActiveCondition(
                    ActorConditionState.newBuilder().setConditionKey("blocking").build())
                .build());

    var result =
        handler.handle(
            context,
            new TextCommand(TextCommandType.BLOCK, java.util.List.of(), "BLOCK"),
            "effect-1");

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .extracting(output -> output.text())
        .containsExactly("You brace for the next blow.");
    verify(gameLogicClient)
        .applyActorCondition(
            eq(context),
            eq("blocking"),
            eq("ACTION_STATE"),
            eq("effect-1"),
            eq(NOW.plusSeconds(5)),
            Mockito.contains("block_mitigation"));
  }

  @Test
  void blockRejectsArguments() {
    var result =
        handler.handle(
            context,
            new TextCommand(TextCommandType.BLOCK, java.util.List.of("now"), "BLOCK now"),
            "effect-1");

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
  }
}
