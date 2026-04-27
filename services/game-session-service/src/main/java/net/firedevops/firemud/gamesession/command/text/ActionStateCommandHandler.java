package net.firedevops.firemud.gamesession.command.text;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.entitymanagement.v1.ApplyActorConditionResponse;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ActionStateCommandHandler {
  private static final Duration BLOCK_DURATION = Duration.ofSeconds(5);
  private static final String BLOCK_EFFECT_PAYLOAD =
      """
      {"modifiers":[{"operation":"ADD","target_key":"block_mitigation","value":1,"scope_kind":"ACTION_FAMILY","scope_key":"defense"}]}
      """;

  private final GameLogicClient gameLogicClient;
  private final Clock clock;

  @Autowired
  public ActionStateCommandHandler(GameLogicClient gameLogicClient) {
    this(gameLogicClient, Clock.systemUTC());
  }

  ActionStateCommandHandler(GameLogicClient gameLogicClient, Clock clock) {
    this.gameLogicClient = gameLogicClient;
    this.clock = clock;
  }

  public ActionStateCommandHandlingResult handle(
      SessionContext context, TextCommand command, String effectId) {
    if (context == null || context.gameInstanceId() <= 0) {
      return failure("NOT_PLAYING", "You are not in the game.");
    }
    if (command.type() != TextCommandType.BLOCK || !command.args().isEmpty()) {
      return failure("INVALID_ARGUMENT", "Usage: BLOCK");
    }
    Instant expiresAt = clock.instant().plus(BLOCK_DURATION);
    ApplyActorConditionResponse response =
        gameLogicClient.applyActorCondition(
            context,
            "blocking",
            "ACTION_STATE",
            effectId == null ? "" : effectId,
            expiresAt,
            BLOCK_EFFECT_PAYLOAD);
    if (response.hasError()) {
      String message =
          response.getError().getMessage().isBlank()
              ? "Could not apply blocking state."
              : response.getError().getMessage();
      String code =
          response.getError().getCode().isBlank()
              ? "ACTOR_STATE_UNAVAILABLE"
              : response.getError().getCode();
      return failure(code, message);
    }
    return new ActionStateCommandHandlingResult(
        CommandEnqueueResult.success(),
        List.of(PlayerOutput.notice("You brace for the next blow.")));
  }

  private ActionStateCommandHandlingResult failure(String code, String message) {
    return new ActionStateCommandHandlingResult(
        CommandEnqueueResult.failure(code, message), List.of(PlayerOutput.notice(message)));
  }
}
