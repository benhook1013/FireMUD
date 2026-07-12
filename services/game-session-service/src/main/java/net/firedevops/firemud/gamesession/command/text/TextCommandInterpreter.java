package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PromptComposer;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Constructor validation only guards injected collaborators before the interpreter is"
            + " used.")
@Component
public class TextCommandInterpreter {
  private final SessionAuthenticationService sessionAuthenticationService;
  private final PromptComposer promptComposer;
  private final TextCommandParser parser;
  private final TextCommandRegistry registry;
  private final AdmittedTextCommandRegistryResolver admittedRegistryResolver;
  private final TextCommandDispatcher dispatcher;

  TextCommandInterpreter(
      CommandService commandService,
      LookCommandHandler lookHandler,
      LoginCommandHandler loginHandler,
      LogoutCommandHandler logoutHandler,
      PlayCommandHandler playHandler,
      MoveCommandHandler moveHandler,
      AfkCommandHandler afkHandler,
      HelpCommandHandler helpHandler,
      WhoCommandHandler whoHandler,
      StatusCommandHandler statusHandler,
      FriendsCommandHandler friendsHandler,
      AuthoredActionCommandHandler authoredActionHandler,
      ConfiguredAuthoredActionCatalog authoredActionCatalog,
      InventoryCommandHandler inventoryHandler,
      EquipmentCommandHandler equipmentHandler,
      ContainerCommandHandler containerHandler,
      SessionAuthenticationService sessionAuthenticationService,
      ScriptEventPublisher scriptEventPublisher,
      CommunicationCommandHandler communicationHandler,
      WorldsCommandHandler worldsHandler,
      PromptComposer promptComposer,
      TextCommandRegistry registry,
      TextCommandParser parser,
      MeterRegistry meterRegistry) {
    this(
        commandService,
        lookHandler,
        loginHandler,
        logoutHandler,
        playHandler,
        moveHandler,
        afkHandler,
        helpHandler,
        whoHandler,
        statusHandler,
        friendsHandler,
        authoredActionHandler,
        authoredActionCatalog,
        inventoryHandler,
        equipmentHandler,
        containerHandler,
        sessionAuthenticationService,
        scriptEventPublisher,
        communicationHandler,
        worldsHandler,
        promptComposer,
        registry,
        parser,
        null,
        meterRegistry);
  }

  @Autowired
  public TextCommandInterpreter(
      CommandService commandService,
      LookCommandHandler lookHandler,
      LoginCommandHandler loginHandler,
      LogoutCommandHandler logoutHandler,
      PlayCommandHandler playHandler,
      MoveCommandHandler moveHandler,
      AfkCommandHandler afkHandler,
      HelpCommandHandler helpHandler,
      WhoCommandHandler whoHandler,
      StatusCommandHandler statusHandler,
      FriendsCommandHandler friendsHandler,
      AuthoredActionCommandHandler authoredActionHandler,
      ConfiguredAuthoredActionCatalog authoredActionCatalog,
      InventoryCommandHandler inventoryHandler,
      EquipmentCommandHandler equipmentHandler,
      ContainerCommandHandler containerHandler,
      SessionAuthenticationService sessionAuthenticationService,
      ScriptEventPublisher scriptEventPublisher,
      CommunicationCommandHandler communicationHandler,
      WorldsCommandHandler worldsHandler,
      PromptComposer promptComposer,
      TextCommandRegistry registry,
      TextCommandParser parser,
      AdmittedTextCommandRegistryResolver admittedRegistryResolver,
      MeterRegistry meterRegistry) {
    this(
        sessionAuthenticationService,
        promptComposer,
        parser,
        registry,
        admittedRegistryResolver,
        buildDispatcher(
            commandService,
            lookHandler,
            loginHandler,
            logoutHandler,
            playHandler,
            moveHandler,
            afkHandler,
            helpHandler,
            whoHandler,
            statusHandler,
            friendsHandler,
            authoredActionHandler,
            authoredActionCatalog,
            new ItemCommandHandler(
                inventoryHandler,
                equipmentHandler,
                containerHandler,
                meterRegistry,
                scriptEventPublisher),
            scriptEventPublisher,
            communicationHandler,
            worldsHandler));
  }

  TextCommandInterpreter(
      SessionAuthenticationService sessionAuthenticationService,
      PromptComposer promptComposer,
      TextCommandParser parser,
      TextCommandRegistry registry,
      TextCommandDispatcher dispatcher) {
    this(sessionAuthenticationService, promptComposer, parser, registry, null, dispatcher);
  }

  TextCommandInterpreter(
      SessionAuthenticationService sessionAuthenticationService,
      PromptComposer promptComposer,
      TextCommandParser parser,
      TextCommandRegistry registry,
      AdmittedTextCommandRegistryResolver admittedRegistryResolver,
      TextCommandDispatcher dispatcher) {
    this.sessionAuthenticationService =
        Objects.requireNonNull(
            sessionAuthenticationService, "sessionAuthenticationService must not be null");
    this.promptComposer = Objects.requireNonNull(promptComposer, "promptComposer must not be null");
    this.parser = Objects.requireNonNull(parser, "parser must not be null");
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.admittedRegistryResolver = admittedRegistryResolver;
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
  }

  public TextCommandInterpretationResult interpret(
      String sessionId, String rawLine, boolean requiresSoloTick) {
    Optional<SessionContext> context =
        sessionAuthenticationService.resolveSessionContext(sessionId);
    TextCommandRegistry activeRegistry = registryFor(context);
    return interpret(
        sessionId,
        parser.parse(rawLine, activeRegistry),
        requiresSoloTick,
        context,
        activeRegistry);
  }

  public TextCommandInterpretationResult interpret(
      String sessionId, TextCommand command, boolean requiresSoloTick) {
    Optional<SessionContext> context =
        sessionAuthenticationService.resolveSessionContext(sessionId);
    TextCommandRegistry activeRegistry = registryFor(context);
    TextCommand activeCommand =
        admittedRegistryResolver == null
            ? command
            : parser.parse(command.rawLine(), activeRegistry);
    return interpret(sessionId, activeCommand, requiresSoloTick, context, activeRegistry);
  }

  private TextCommandInterpretationResult interpret(
      String sessionId,
      TextCommand command,
      boolean requiresSoloTick,
      Optional<SessionContext> maybeContext,
      TextCommandRegistry activeRegistry) {
    TextCommandDefinition definition =
        activeRegistry
            .findDefinition(command.commandId())
            .orElseGet(
                () ->
                    TextCommandDefinition.extensionDefinition(command.type(), command.commandId()));
    if (command.type() == TextCommandType.NOOP) {
      return withResolvedCommand(
          new TextCommandInterpretationResult(CommandEnqueueResult.success()), command, definition);
    }
    if (command.type() == TextCommandType.UNKNOWN) {
      String message = "Unknown command";
      return withResolvedCommand(
          new TextCommandInterpretationResult(
              CommandEnqueueResult.failure("UNKNOWN_COMMAND", message),
              List.of(
                  PlayerOutput.error(
                      "UNKNOWN_COMMAND", message, "error.unknown-command", Map.of()))),
          command,
          definition);
    }
    boolean hasLogin = maybeContext.isPresent();
    boolean hasPlay = maybeContext.filter(SessionContext::hasGameplayRegionBinding).isPresent();

    if (definition.stageRequirement() != TextCommandStageRequirement.NONE && !hasLogin) {
      return withResolvedCommand(
          stageFailure(
              GameplayStageCommandConstants.LOGIN_REQUIRED_CODE,
              GameplayStageCommandConstants.LOGIN_REQUIRED_MESSAGE),
          command,
          definition);
    }
    if (definition.stageRequirement() == TextCommandStageRequirement.GAMEPLAY && !hasPlay) {
      return withResolvedCommand(
          stageFailure(
              GameplayStageCommandConstants.PLAY_REQUIRED_CODE,
              GameplayStageCommandConstants.PLAY_REQUIRED_MESSAGE),
          command,
          definition);
    }

    TextCommandInterpretationResult dispatchResult =
        dispatcher.dispatch(
            definition.dispatchGroup(),
            new TextCommandDispatchRequest(sessionId, command, requiresSoloTick, maybeContext));
    Optional<net.firedevops.firemud.gamesession.service.SessionContext> promptContext =
        promptContextAfterDispatch(sessionId, definition, dispatchResult, maybeContext);
    TextCommandInterpretationResult promptApplied =
        applyPromptPolicy(dispatchResult, definition.promptPolicy(), promptContext);
    return withResolvedCommand(
        withMeaningfulGameplayActivity(
            promptApplied,
            dispatchResult.commandResult().accepted() && isMeaningfulGameplay(definition)),
        command,
        definition);
  }

  private TextCommandRegistry registryFor(Optional<SessionContext> context) {
    return admittedRegistryResolver == null
        ? registry
        : admittedRegistryResolver.resolve(context.orElse(null));
  }

  private TextCommandInterpretationResult applyPromptPolicy(
      TextCommandInterpretationResult result,
      TextCommandPromptPolicy promptPolicy,
      Optional<net.firedevops.firemud.gamesession.service.SessionContext> maybeContext) {
    if (!result.commandResult().accepted() || result.reconnectRedrawRecommended()) {
      return result;
    }
    return switch (promptPolicy) {
      case NEVER -> result;
      case WHEN_LOGGED_IN ->
          maybeContext
              .map(
                  context ->
                      new TextCommandInterpretationResult(
                          result.commandResult(),
                          appendPrompt(context, result.outputs()),
                          result.reconnectRedrawRecommended(),
                          result.meaningfulGameplayActivity(),
                          result.resolvedCommand(),
                          result.resolvedMetadata()))
              .orElse(result);
      case WHEN_GAMEPLAY ->
          maybeContext
              .filter(SessionContext::hasGameplayRegionBinding)
              .map(
                  context ->
                      new TextCommandInterpretationResult(
                          result.commandResult(),
                          appendPrompt(context, result.outputs()),
                          result.reconnectRedrawRecommended(),
                          result.meaningfulGameplayActivity(),
                          result.resolvedCommand(),
                          result.resolvedMetadata()))
              .orElse(result);
    };
  }

  private TextCommandInterpretationResult withMeaningfulGameplayActivity(
      TextCommandInterpretationResult result, boolean meaningfulGameplayActivity) {
    return new TextCommandInterpretationResult(
        result.commandResult(),
        result.outputs(),
        result.reconnectRedrawRecommended(),
        meaningfulGameplayActivity,
        result.resolvedCommand(),
        result.resolvedMetadata());
  }

  private TextCommandInterpretationResult withResolvedCommand(
      TextCommandInterpretationResult result,
      TextCommand command,
      TextCommandDefinition definition) {
    return new TextCommandInterpretationResult(
        result.commandResult(),
        result.outputs(),
        result.reconnectRedrawRecommended(),
        result.meaningfulGameplayActivity(),
        command,
        new TextCommandMetadataResolver.ResolvedTextCommandMetadata(
            definition.dispatchGroup(),
            definition.promptPolicy(),
            definition.actionCategory(),
            definition.actionTags()));
  }

  private boolean isMeaningfulGameplay(TextCommandDefinition definition) {
    return definition.actionCategory() == TextCommandActionCategory.GAMEPLAY;
  }

  private Optional<net.firedevops.firemud.gamesession.service.SessionContext>
      promptContextAfterDispatch(
          String sessionId,
          TextCommandDefinition definition,
          TextCommandInterpretationResult result,
          Optional<net.firedevops.firemud.gamesession.service.SessionContext> maybeContext) {
    if (!result.commandResult().accepted()) {
      return maybeContext;
    }
    if (definition.dispatchGroup() == TextCommandDispatchGroup.SESSION) {
      return sessionAuthenticationService.resolveSessionContext(sessionId);
    }
    return maybeContext;
  }

  private TextCommandInterpretationResult stageFailure(String code, String message) {
    String messageKey =
        switch (code) {
          case GameplayStageCommandConstants.LOGIN_REQUIRED_CODE -> "error.login-required";
          case GameplayStageCommandConstants.PLAY_REQUIRED_CODE -> "error.play-required";
          default -> null;
        };
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure(code, message),
        List.of(PlayerOutput.error(code, message, messageKey, Map.of())));
  }

  private List<PlayerOutput> appendPrompt(
      net.firedevops.firemud.gamesession.service.SessionContext context,
      List<PlayerOutput> outputs) {
    return promptComposer
        .compose(context)
        .map(
            prompt -> {
              java.util.ArrayList<PlayerOutput> combined = new java.util.ArrayList<>(outputs);
              combined.add(prompt);
              return List.copyOf(combined);
            })
        .orElse(outputs);
  }

  private static TextCommandDispatcher buildDispatcher(
      CommandService commandService,
      LookCommandHandler lookHandler,
      LoginCommandHandler loginHandler,
      LogoutCommandHandler logoutHandler,
      PlayCommandHandler playHandler,
      MoveCommandHandler moveHandler,
      AfkCommandHandler afkHandler,
      HelpCommandHandler helpHandler,
      WhoCommandHandler whoHandler,
      StatusCommandHandler statusHandler,
      FriendsCommandHandler friendsHandler,
      AuthoredActionCommandHandler authoredActionHandler,
      ConfiguredAuthoredActionCatalog authoredActionCatalog,
      ItemCommandHandler itemHandler,
      ScriptEventPublisher scriptEventPublisher,
      CommunicationCommandHandler communicationHandler,
      WorldsCommandHandler worldsHandler) {
    return new TextCommandDispatcher(
        List.of(
            new EnqueueOnlyTextCommandDispatchHandler(commandService),
            new WorldsTextCommandDispatchHandler(worldsHandler, scriptEventPublisher),
            new SessionTextCommandDispatchHandler(loginHandler, logoutHandler, playHandler),
            new ActivityTextCommandDispatchHandler(commandService),
            new HelpTextCommandDispatchHandler(helpHandler, scriptEventPublisher),
            new WhoTextCommandDispatchHandler(whoHandler),
            new StatusTextCommandDispatchHandler(statusHandler),
            new FriendsTextCommandDispatchHandler(friendsHandler),
            new AuthoredActionTextCommandDispatchHandler(
                authoredActionHandler, authoredActionCatalog, scriptEventPublisher),
            new ItemTextCommandDispatchHandler(commandService, itemHandler),
            new CommunicationTextCommandDispatchHandler(commandService),
            new MoveTextCommandDispatchHandler(commandService),
            new LookTextCommandDispatchHandler(commandService, lookHandler)));
  }
}
