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
  private final AdmittedTextCommandRegistryResolver admittedRegistryResolver;

  AuthoredActionCommandHandler(ConfiguredAuthoredActionCatalog catalog) {
    this(catalog, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  AuthoredActionCommandHandler(
      ConfiguredAuthoredActionCatalog catalog,
      AdmittedTextCommandRegistryResolver admittedRegistryResolver) {
    this.catalog = catalog;
    this.admittedRegistryResolver = admittedRegistryResolver;
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
    if (admittedRegistryResolver == null) {
      return handle(command);
    }
    TextCommandPayload.AuthoredActionInvocation invocation =
        command.authoredActionPayload().orElseThrow();
    boolean admitted =
        admittedRegistryResolver
            .resolve(context)
            .findDefinition(invocation.commandId())
            .filter(definition -> definition.type() == TextCommandType.AUTHORED)
            .isPresent();
    return admitted
        ? new TextCommandInterpretationResult(CommandEnqueueResult.success(), List.of())
        : unknown(invocation.commandId());
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
