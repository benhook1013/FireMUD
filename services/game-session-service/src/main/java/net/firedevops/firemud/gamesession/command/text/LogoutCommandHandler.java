package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.springframework.stereotype.Component;

/** Handles deliberate player logout distinct from reconnect-loss recovery. */
@Component
public final class LogoutCommandHandler {
  private final SessionAuthenticationService sessionAuthenticationService;
  private final SessionContextService sessionContextService;
  private final GameInstanceService gameInstanceService;
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;
  private final GameplayPresenceLifecycleService gameplayPresenceLifecycleService;
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry;
  private final ScriptEventPublisher scriptEventPublisher;

  public LogoutCommandHandler(
      SessionAuthenticationService sessionAuthenticationService,
      SessionContextService sessionContextService,
      GameInstanceService gameInstanceService,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      GameplayPresenceLifecycleService gameplayPresenceLifecycleService,
      FirstPartyConnectContextRegistry firstPartyConnectContextRegistry,
      ScriptEventPublisher scriptEventPublisher) {
    this.sessionAuthenticationService =
        Objects.requireNonNull(
            sessionAuthenticationService, "sessionAuthenticationService must not be null");
    this.sessionContextService =
        Objects.requireNonNull(sessionContextService, "sessionContextService must not be null");
    this.gameInstanceService =
        Objects.requireNonNull(gameInstanceService, "gameInstanceService must not be null");
    this.gameplayAdmissionPointerAuthorityService =
        Objects.requireNonNull(
            gameplayAdmissionPointerAuthorityService,
            "gameplayAdmissionPointerAuthorityService must not be null");
    this.gameplayPresenceLifecycleService =
        Objects.requireNonNull(
            gameplayPresenceLifecycleService, "gameplayPresenceLifecycleService must not be null");
    this.firstPartyConnectContextRegistry =
        Objects.requireNonNull(
            firstPartyConnectContextRegistry, "firstPartyConnectContextRegistry must not be null");
    this.scriptEventPublisher =
        Objects.requireNonNull(scriptEventPublisher, "scriptEventPublisher must not be null");
  }

  public LogoutCommandHandlingResult handle(String sessionId, TextCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    Optional<SessionContext> maybePersistedContext = resolvePersistedSessionContext(sessionId);
    Optional<SessionContext> maybeContext =
        sessionAuthenticationService.resolveSessionContext(sessionId);
    if (maybeContext.isEmpty()) {
      return failure(
          "NOT_LOGGED_IN", "You are not logged in.", "error.logout.not-logged-in", Map.of());
    }

    SessionContext context = maybeContext.orElseThrow();
    SessionContext persistedContext = maybePersistedContext.orElse(context);
    try {
      if (context.hasGameplayIdentity()) {
        scriptEventPublisher.publishCommandEvent(context, logoutCommand(context, command));
      }
      boolean stopSession = shouldStopSession(context, persistedContext);
      if (stopSession) {
        gameInstanceService.stopSession(context.gameInstanceId());
      }
      gameplayPresenceLifecycleService.recordDisconnected(
          context.sessionId(), AccountRecentPresenceDisposition.LOGOUT);
      firstPartyConnectContextRegistry.unregister(context.sessionId());
      sessionContextService.deleteBySessionId(context.tenantId(), context.sessionId());
      return new LogoutCommandHandlingResult(
          CommandEnqueueResult.success(),
          List.of(PlayerOutput.notice("Logged out.", "notice.logout.success", Map.of())));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return failure(
          "LOGOUT_FAILED",
          "Logout failed.",
          "error.logout.failed",
          Map.of("reason", Optional.ofNullable(ex.getMessage()).orElse("unknown")));
    }
  }

  private LogoutCommandHandlingResult failure(
      String code, String message, String messageKey, Map<String, String> arguments) {
    return new LogoutCommandHandlingResult(
        CommandEnqueueResult.failure(code, message),
        List.of(PlayerOutput.error(code, message, messageKey, arguments)));
  }

  private static GameplayCommand logoutCommand(SessionContext context, TextCommand command) {
    return ScriptEventGameplayCommands.syntheticWithId(
        "logout-command:"
            + context.sessionId()
            + ":"
            + context.gameInstanceId()
            + ":"
            + context.characterId(),
        command,
        TextCommandType.LOGOUT.name(),
        null);
  }

  private boolean shouldStopSession(SessionContext context, SessionContext persistedContext) {
    return context.gameInstanceId() > 0
        && isConfirmedIsolatedRuntime(context)
        && isConfirmedIsolatedRuntime(persistedContext);
  }

  private boolean isConfirmedIsolatedRuntime(SessionContext context) {
    if ("ISOLATED".equals(context.playableStateScope())) {
      return true;
    }
    if ("SHARED".equals(context.playableStateScope())) {
      return false;
    }
    return singularRuntimePointer(context)
        .map(pointer -> "ISOLATED".equals(pointer.stateScope()))
        .orElse(false);
  }

  private Optional<net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot>
      singularRuntimePointer(SessionContext context) {
    return GameplayAdmissionPointerSnapshots.singularCompletePointer(
        gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(
            context.tenantId(), context.gameInstanceId()));
  }

  private Optional<SessionContext> resolvePersistedSessionContext(String sessionIdText) {
    return sessionAuthenticationService.resolveSessionContext(sessionIdText);
  }
}
