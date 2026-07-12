package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.config.AuthoredActionProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.service.AuthoredCommandAdmission;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AuthoredActionTextCommandDispatchHandlerTest {
  private final AdmittedTextCommandRegistryResolver admittedRegistryResolver =
      Mockito.mock(AdmittedTextCommandRegistryResolver.class);
  private final CommandService commandService = Mockito.mock(CommandService.class);
  private final AuthoredActionTextCommandDispatchHandler handler =
      new AuthoredActionTextCommandDispatchHandler(
          new AuthoredActionCommandHandler(
              new ConfiguredAuthoredActionCatalog(configuredAuthoredActions())),
          admittedRegistryResolver,
          commandService);

  @Test
  void enqueuesGameplayScopedAuthoredActionWithItsAdmittedSnapshot() {
    SessionContext context = context();
    AuthoredCommandAdmission admission =
        new AuthoredCommandAdmission(
            300L,
            41L,
            "wave-salute",
            """
            [{"effectKind":"APPLY_ACTION_STATE","schemaVersion":1,"targeting":"SELF","replayPolicy":"EFFECT_IDEMPOTENT","payload":{"conditionKey":"SALUTING","durationSeconds":30,"effectPayload":{}}}]
            """);
    when(admittedRegistryResolver.resolveAdmission(context, "wave-salute"))
        .thenReturn(Optional.of(admission));
    when(commandService.enqueue("session-1", "salute captain", false, admission))
        .thenReturn(CommandEnqueueResult.success());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                authoredAction("wave-salute", "salute captain", List.of("captain")),
                false,
                Optional.of(context)));

    assertThat(result.commandResult().accepted()).isTrue();
    verify(commandService).enqueue("session-1", "salute captain", false, admission);
  }

  @Test
  void rejectsGameplayScopedAuthoredActionWithoutAnAdmittedSnapshot() {
    SessionContext context = context();
    when(admittedRegistryResolver.resolveAdmission(context, "wave-salute"))
        .thenReturn(Optional.empty());

    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                authoredAction("wave-salute", "salute captain", List.of("captain")),
                false,
                Optional.of(context)));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode())
        .isEqualTo("AUTHORED_ACTION_EXECUTION_UNAVAILABLE");
    verifyNoInteractions(commandService);
  }

  @Test
  void usesFixtureCatalogOnlyWithoutGameplayContext() {
    TextCommandInterpretationResult result =
        handler.handle(
            new TextCommandDispatchRequest(
                "session-1",
                authoredAction("wave-salute", "salute captain", List.of("captain")),
                false,
                Optional.empty()));

    assertThat(result.commandResult().accepted()).isTrue();
    verifyNoInteractions(admittedRegistryResolver, commandService);
  }

  private static SessionContext context() {
    return new SessionContext(
        7L, 22L, 41L, "emberline@example.com", 7001L, "Emberline", 9L, "R-1", "jwt");
  }

  private static TextCommand authoredAction(String commandId, String rawLine, List<String> args) {
    return new TextCommand(
        commandId,
        TextCommandType.AUTHORED,
        args,
        rawLine,
        args.isEmpty() ? commandId : args.getFirst(),
        new TextCommandPayload.AuthoredActionInvocation(commandId, args));
  }

  private static AuthoredActionProperties configuredAuthoredActions() {
    AuthoredActionProperties properties = new AuthoredActionProperties();
    AuthoredActionProperties.Action action = new AuthoredActionProperties.Action();
    action.setActionId("wave-salute");
    action.setCommandId("wave-salute");
    action.setAliases(List.of("salute"));
    properties.setActions(List.of(action));
    return properties;
  }
}
