package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;

/** Result of interpreting a text command with structured player outputs. */
public record TextCommandInterpretationResult(
    CommandEnqueueResult commandResult,
    List<PlayerOutput> outputs,
    boolean reconnectRedrawRecommended,
    boolean meaningfulGameplayActivity,
    TextCommand resolvedCommand,
    TextCommandMetadataResolver.ResolvedTextCommandMetadata resolvedMetadata) {

  public TextCommandInterpretationResult {
    Objects.requireNonNull(commandResult, "commandResult must not be null");
    outputs = outputs == null ? List.of() : List.copyOf(outputs);
  }

  public TextCommandInterpretationResult(CommandEnqueueResult commandResult) {
    this(commandResult, List.of(), false, false, null, null);
  }

  public TextCommandInterpretationResult(
      CommandEnqueueResult commandResult, List<PlayerOutput> outputs) {
    this(commandResult, outputs, false, false, null, null);
  }

  public TextCommandInterpretationResult(
      CommandEnqueueResult commandResult,
      List<PlayerOutput> outputs,
      boolean reconnectRedrawRecommended) {
    this(commandResult, outputs, reconnectRedrawRecommended, false, null, null);
  }

  public TextCommandInterpretationResult(
      CommandEnqueueResult commandResult,
      List<PlayerOutput> outputs,
      boolean reconnectRedrawRecommended,
      boolean meaningfulGameplayActivity) {
    this(
        commandResult, outputs, reconnectRedrawRecommended, meaningfulGameplayActivity, null, null);
  }

  public boolean hasResponse() {
    return !outputs.isEmpty();
  }
}
