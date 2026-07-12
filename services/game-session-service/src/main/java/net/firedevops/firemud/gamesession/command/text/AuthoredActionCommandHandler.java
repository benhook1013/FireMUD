package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;

@Component
final class AuthoredActionCommandHandler implements AuthoredActionRuntimeHandler {
  private final ConfiguredAuthoredActionCatalog catalog;

  AuthoredActionCommandHandler(ConfiguredAuthoredActionCatalog catalog) {
    this.catalog = catalog;
  }

  @Override
  @Timed(value = "gamesession.command.authored")
  public TextCommandInterpretationResult handle(TextCommand command) {
    TextCommandPayload.AuthoredActionInvocation invocation =
        command.authoredActionPayload().orElseThrow();
    return catalog
        .find(invocation.commandId())
        .map(
            action ->
                new TextCommandInterpretationResult(CommandEnqueueResult.success(), List.of()))
        .orElseGet(
            () ->
                new TextCommandInterpretationResult(
                    CommandEnqueueResult.failure(
                        "UNKNOWN_AUTHORED_ACTION",
                        "Unknown authored action: " + invocation.commandId()),
                    List.of(
                        PlayerOutput.error(
                            "UNKNOWN_AUTHORED_ACTION",
                            "Unknown authored action: " + invocation.commandId()))));
  }

  @Override
  public TextCommandInterpretationResult handle(SessionContext context, TextCommand command) {
    // The interpreter resolves the admitted registry once before dispatch. Re-reading it here can
    // turn one accepted command into a transient false rejection when the control-plane read fails.
    return new TextCommandInterpretationResult(CommandEnqueueResult.success(), List.of());
  }

  private TextCommandInterpretationResult unknown(String commandId) {
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure(
            "UNKNOWN_AUTHORED_ACTION", "Unknown authored action: " + commandId),
        List.of(
            PlayerOutput.error(
                "UNKNOWN_AUTHORED_ACTION", "Unknown authored action: " + commandId)));
  }
}
