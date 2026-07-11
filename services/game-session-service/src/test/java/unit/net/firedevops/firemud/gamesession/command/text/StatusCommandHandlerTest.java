package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.entitymanagement.v1.ActorConditionState;
import net.firedevops.firemud.entitymanagement.v1.ActorResourceValue;
import net.firedevops.firemud.entitymanagement.v1.QueryActorStateResponse;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.presentation.ActorStateViewOutput;
import net.firedevops.firemud.gamesession.presentation.ErrorOutput;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class StatusCommandHandlerTest {
  private final GameLogicClient gameLogicClient = Mockito.mock(GameLogicClient.class);
  private final ScriptEventPublisher scriptEventPublisher =
      Mockito.mock(ScriptEventPublisher.class);
  private final StatusCommandHandler handler =
      new StatusCommandHandler(gameLogicClient, scriptEventPublisher);
  private final SessionContext context =
      new SessionContext(
          1L, 22L, 123L, "emberline@example.com", 911L, "Emberline", 77L, "R-7", "jwt-token");

  @Test
  void statusProjectsEvaluatedResourcesAndVisibleConditionsInStableOrder() {
    when(gameLogicClient.queryActorState(context))
        .thenReturn(
            QueryActorStateResponse.newBuilder()
                .addResources(
                    ActorResourceValue.newBuilder().setStatKey("stamina").setCurrentValue(8))
                .addResources(
                    ActorResourceValue.newBuilder()
                        .setStatKey("health")
                        .setCurrentValue(12)
                        .setMaxValue(20)
                        .setBaseValue(10))
                .addActiveConditions(
                    ActorConditionState.newBuilder()
                        .setConditionKey("blocking")
                        .setStackCount(1)
                        .setExpiresAt("2026-07-11T00:00:00Z"))
                .build());

    TextCommandInterpretationResult result =
        handler.handle(new TextCommand(TextCommandType.STATUS, List.of(), "STATUS"), context);

    assertThat(result.commandResult().accepted()).isTrue();
    ActorStateViewOutput view = (ActorStateViewOutput) result.outputs().get(0).payload();
    assertThat(view.resources())
        .containsExactly(
            new ActorStateViewOutput.Resource("health", 12, 20L, 10L),
            new ActorStateViewOutput.Resource("stamina", 8, null, null));
    assertThat(view.conditions())
        .containsExactly(new ActorStateViewOutput.Condition("blocking", 1, "2026-07-11T00:00:00Z"));
  }

  @Test
  void statusPreservesCanonicalActorStateApplicationErrors() {
    when(gameLogicClient.queryActorState(context))
        .thenReturn(
            QueryActorStateResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("PERMISSION_DENIED")
                        .setMessage("actor read denied"))
                .build());

    TextCommandInterpretationResult result =
        handler.handle(new TextCommand(TextCommandType.STATUS, List.of(), "STATUS"), context);

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("PERMISSION_DENIED");
    assertThat(result.outputs().get(0).text()).contains("actor read denied");
    assertThat(((ErrorOutput) result.outputs().get(0).payload()).messageKey())
        .isEqualTo("error.actor-state.permission-denied");
  }
}
