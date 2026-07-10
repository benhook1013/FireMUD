package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.annotation.Timed;
import java.util.Comparator;
import java.util.List;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.ActorStateViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Projects the authoritative evaluated actor state for the active gameplay character. */
@Component
public class StatusCommandHandler {
  private static final Logger LOG = LoggerFactory.getLogger(StatusCommandHandler.class);

  private final GameLogicClient gameLogicClient;
  private final ScriptEventPublisher scriptEventPublisher;

  StatusCommandHandler(GameLogicClient gameLogicClient, ScriptEventPublisher scriptEventPublisher) {
    this.gameLogicClient = gameLogicClient;
    this.scriptEventPublisher = scriptEventPublisher;
  }

  @Timed(value = "gamesession.command.status")
  public TextCommandInterpretationResult handle(TextCommand command, SessionContext context) {
    try {
      var response = gameLogicClient.queryActorState(context);
      if (response.hasError()) {
        return failure(response.getError().getCode(), response.getError().getMessage());
      }
      publishCommandEvent(context, command);
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.success(),
          List.of(
              PlayerOutput.view(
                  new ActorStateViewOutput(
                      response.getResourcesList().stream()
                          .map(
                              resource ->
                                  new ActorStateViewOutput.Resource(
                                      resource.getStatKey(),
                                      resource.getCurrentValue(),
                                      resource.hasMaxValue() ? resource.getMaxValue() : null,
                                      resource.hasBaseValue() ? resource.getBaseValue() : null))
                          .sorted(Comparator.comparing(ActorStateViewOutput.Resource::key))
                          .toList(),
                      response.getActiveConditionsList().stream()
                          .map(
                              condition ->
                                  new ActorStateViewOutput.Condition(
                                      condition.getConditionKey(),
                                      condition.getStackCount(),
                                      condition.getExpiresAt()))
                          .sorted(Comparator.comparing(ActorStateViewOutput.Condition::key))
                          .toList()))));
    } catch (RuntimeException ex) {
      LOG.warn(
          "Actor state query failed tenantId={} characterId={}",
          context.tenantId(),
          context.characterId(),
          ex);
      return unavailable("Actor state unavailable");
    }
  }

  private TextCommandInterpretationResult unavailable(String message) {
    return failure("ACTOR_STATE_UNAVAILABLE", message);
  }

  private TextCommandInterpretationResult failure(String code, String message) {
    String errorCode = StringUtils.hasText(code) ? code : "ACTOR_STATE_UNAVAILABLE";
    String reason = StringUtils.hasText(message) ? message : "Actor state unavailable";
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure(errorCode, reason),
        List.of(
            PlayerOutput.error(
                errorCode, reason, "error.actor-state.unavailable", java.util.Map.of())));
  }

  private void publishCommandEvent(SessionContext context, TextCommand command) {
    try {
      scriptEventPublisher.publishCommandEvent(
          context, ScriptEventGameplayCommands.synthetic("status", command));
    } catch (RuntimeException ex) {
      LOG.warn(
          "Status script event publish failed tenantId={} gameInstanceId={} characterId={}",
          context.tenantId(),
          context.gameInstanceId(),
          context.characterId(),
          ex);
    }
  }
}
