package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
            action -> {
              String notice =
                  StringUtils.hasText(action.noticeText())
                      ? action.noticeText()
                      : "Authored action executed: " + action.commandId();
              return new TextCommandInterpretationResult(
                  CommandEnqueueResult.success(), List.of(PlayerOutput.notice(notice)));
            })
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
