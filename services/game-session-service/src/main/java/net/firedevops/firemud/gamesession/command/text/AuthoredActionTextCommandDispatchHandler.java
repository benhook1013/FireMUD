package net.firedevops.firemud.gamesession.command.text;

import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;

@Component
final class AuthoredActionTextCommandDispatchHandler implements TextCommandDispatchHandler {
  private final AuthoredActionCommandHandler handler;
  private final AdmittedTextCommandRegistryResolver admittedRegistryResolver;
  private final CommandService commandService;

  AuthoredActionTextCommandDispatchHandler(
      AuthoredActionCommandHandler handler,
      AdmittedTextCommandRegistryResolver admittedRegistryResolver,
      CommandService commandService) {
    this.handler = handler;
    this.admittedRegistryResolver = admittedRegistryResolver;
    this.commandService = commandService;
  }

  @Override
  public TextCommandDispatchGroup group() {
    return TextCommandDispatchGroup.AUTHORED;
  }

  @Override
  public TextCommandInterpretationResult handle(TextCommandDispatchRequest request) {
    if (request.sessionContext().isEmpty()) {
      return handler.handle(request.command());
    }
    SessionContext context = request.sessionContext().orElseThrow();
    return admittedRegistryResolver
        .resolveAdmission(context, request.command().commandId())
        .map(
            admission ->
                handler.handle(
                    context,
                    request.command(),
                    commandService.enqueue(
                        request.sessionId(),
                        request.command().rawLine(),
                        request.requiresSoloTick(),
                        admission)))
        .orElseGet(() -> handler.handle(context, request.command()));
  }
}
