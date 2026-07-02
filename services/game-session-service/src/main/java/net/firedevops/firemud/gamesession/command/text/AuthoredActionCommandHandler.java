package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
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
}
