package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.opentelemetry.api.trace.Span;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.command.text.GameplayLoggingContext;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.logging.GameSessionCommandLogSanitizer;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.SessionRateLimiter;
import net.firedevops.firemud.gamesession.service.TickService;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/** Default implementation of {@link CommandService}. */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected collaborators are framework-managed and retained internally")
public class CommandServiceImpl implements CommandService {
  private static final Logger logger = LoggingUtil.getLogger(CommandServiceImpl.class);

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected dependencies are internal")
  private final TickService tickService;

  private final SessionRateLimiter sessionRateLimiter;
  private final GameInstanceRepository gameInstanceRepository;
  private final GameplayCommandRepository gameplayCommandRepository;
  private final SessionContextService sessionContextService;
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;
  private final ScriptEventPublisher scriptEventPublisher;

  private record QueueTarget(long tenantId, long queueTargetId) {}

  private record RoutingMetadata(
      String playableStateScope, String worldSlug, String realmSlug, Long pointerVersion) {}

  private record RoutingBundle(String worldSlug, String realmSlug, Long pointerVersion) {
    private static final RoutingBundle EMPTY = new RoutingBundle(null, null, null);

    private boolean isPresent() {
      return worldSlug != null && realmSlug != null && pointerVersion != null;
    }
  }

  public CommandServiceImpl(
      TickService tickService,
      SessionRateLimiter sessionRateLimiter,
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      SessionContextService sessionContextService,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      ScriptEventPublisher scriptEventPublisher) {
    this.tickService = tickService;
    this.sessionRateLimiter = sessionRateLimiter;
    this.gameInstanceRepository = gameInstanceRepository;
    this.gameplayCommandRepository = gameplayCommandRepository;
    this.sessionContextService = sessionContextService;
    this.gameplayAdmissionPointerAuthorityService = gameplayAdmissionPointerAuthorityService;
    this.scriptEventPublisher = scriptEventPublisher;
  }

  @Override
  public CommandEnqueueResult enqueue(
      String sessionIdText, String command, boolean requiresSoloTick) {
    String traceId = Span.current().getSpanContext().getTraceId();
    var sessionContext = resolveSessionContext(sessionIdText);
    Optional<QueueTarget> queueTarget = resolveQueueTarget(sessionIdText, sessionContext);
    String tenantContext =
        queueTarget.map(target -> String.valueOf(target.tenantId())).orElse("unknown");
    GameplayLoggingContext gameplayLoggingContext =
        sessionContext
            .<GameplayLoggingContext>map(GameplayLoggingContext::from)
            .orElseGet(() -> GameplayLoggingContext.open(tenantContext, null, null, null));
    try (MDC.MDCCloseable trace = MDC.putCloseable("traceId", traceId);
        GameplayLoggingContext ignored = gameplayLoggingContext) {
      logger.info(
          "Enqueue request traceId={} tenantId={} gameInstanceId={} characterId={} sessionId={} command={}",
          traceId,
          tenantContext,
          sessionContext.map(SessionContext::gameInstanceId).filter(id -> id > 0).orElse(null),
          sessionContext.map(SessionContext::characterId).filter(id -> id > 0).orElse(null),
          sessionIdText,
          GameSessionCommandLogSanitizer.sanitize(command));

      if (command == null || command.isBlank()) {
        return CommandEnqueueResult.failure("INVALID_ARGUMENT", "Command cannot be blank");
      }

      long sessionId;
      try {
        sessionId = Long.parseLong(sessionIdText);
      } catch (NumberFormatException ex) {
        return CommandEnqueueResult.failure("INVALID_ARGUMENT", "sessionId must be numeric");
      }

      if (!sessionRateLimiter.allow(sessionId)) {
        return CommandEnqueueResult.failure("RATE_LIMIT", "Command rate limit exceeded");
      }

      if (queueTarget.isEmpty()) {
        return CommandEnqueueResult.failure("NOT_FOUND", "Unable to resolve command queue target");
      }

      GameplayCommand gameplayCommand =
          persistAcceptedCommand(
              sessionId, command, requiresSoloTick, queueTarget.get(), sessionContext);
      logger.info(
          "Accepted gameplay command commandId={} tenantId={} gameInstanceId={} sessionId={} command={}",
          gameplayCommand.getCommandId(),
          gameplayCommand.getTenantId(),
          gameplayCommand.getGameInstanceId(),
          sessionId,
          gameplayCommand.getSanitizedCommandText());

      try (MDC.MDCCloseable commandIdContext =
          MDC.putCloseable("commandId", gameplayCommand.getCommandId())) {
        tickService.enqueueCommand(
            queueTarget.get().tenantId(),
            queueTarget.get().queueTargetId(),
            gameplayCommand.getCommandId(),
            command,
            requiresSoloTick);
        markStaged(gameplayCommand);
        triggerImmediateTick(queueTarget.get());
        sessionContext.ifPresent(context -> publishScriptEvent(context, gameplayCommand));
        return CommandEnqueueResult.success(gameplayCommand.getCommandId());
      } catch (IllegalArgumentException ex) {
        markFailed(gameplayCommand, "INVALID_ARGUMENT", ex.getMessage());
        return CommandEnqueueResult.failure(
            gameplayCommand.getCommandId(), "INVALID_ARGUMENT", ex.getMessage());
      }
    }
  }

  private GameplayCommand persistAcceptedCommand(
      long sessionId,
      String command,
      boolean requiresSoloTick,
      QueueTarget queueTarget,
      Optional<SessionContext> sessionContext) {
    Instant now = Instant.now();
    GameplayCommand gameplayCommand = new GameplayCommand();
    gameplayCommand.setCommandId("cmd-" + UUID.randomUUID());
    gameplayCommand.setTenantId(queueTarget.tenantId());
    gameplayCommand.setGameInstanceId(queueTarget.queueTargetId());
    gameplayCommand.setSessionId(sessionId);
    sessionContext
        .map(SessionContext::accountId)
        .filter(id -> id > 0)
        .ifPresent(gameplayCommand::setAccountId);
    sessionContext
        .map(SessionContext::characterId)
        .filter(id -> id > 0)
        .ifPresent(gameplayCommand::setCharacterId);
    gameplayCommand.setCommandName(commandName(command));
    gameplayCommand.setCommandText(command);
    gameplayCommand.setSanitizedCommandText(GameSessionCommandLogSanitizer.sanitize(command));
    gameplayCommand.setRequiresSoloTick(requiresSoloTick);
    gameplayCommand.setExecutionOutcome("ACCEPTED");
    gameplayCommand.setGameplayResult("PENDING");
    gameplayCommand.setAcceptedAt(now);
    gameplayCommand.setLastAttemptAt(now);
    gameplayCommand.setAttemptCount(1);
    RoutingMetadata routingMetadata = resolveRoutingMetadata(sessionContext, queueTarget);
    gameplayCommand.setPlayableStateScope(routingMetadata.playableStateScope());
    gameplayCommand.setWorldSlug(routingMetadata.worldSlug());
    gameplayCommand.setRealmSlug(routingMetadata.realmSlug());
    gameplayCommand.setPointerVersion(routingMetadata.pointerVersion());
    return gameplayCommandRepository.save(gameplayCommand);
  }

  private void markStaged(GameplayCommand gameplayCommand) {
    Instant now = Instant.now();
    gameplayCommand.setExecutionOutcome("STAGED");
    gameplayCommand.setStagedAt(now);
    gameplayCommand.setLastAttemptAt(now);
    gameplayCommandRepository.save(gameplayCommand);
    logger.info(
        "Staged gameplay command commandId={} tenantId={} gameInstanceId={}",
        gameplayCommand.getCommandId(),
        gameplayCommand.getTenantId(),
        gameplayCommand.getGameInstanceId());
  }

  private void markFailed(GameplayCommand gameplayCommand, String code, String message) {
    Instant now = Instant.now();
    gameplayCommand.setExecutionOutcome("FAILED");
    gameplayCommand.setGameplayResult("NOT_APPLIED");
    gameplayCommand.setCompletedAt(now);
    gameplayCommand.setLastAttemptAt(now);
    gameplayCommand.setFailureCode(code);
    gameplayCommand.setFailureMessage(message);
    gameplayCommandRepository.save(gameplayCommand);
    logger.warn(
        "Failed gameplay command staging commandId={} tenantId={} gameInstanceId={} code={} message={}",
        gameplayCommand.getCommandId(),
        gameplayCommand.getTenantId(),
        gameplayCommand.getGameInstanceId(),
        code,
        message);
  }

  private String commandName(String command) {
    String trimmed = command == null ? "" : command.trim();
    if (trimmed.isEmpty()) {
      return "UNKNOWN";
    }
    int firstSpace = trimmed.indexOf(' ');
    String token = firstSpace < 0 ? trimmed : trimmed.substring(0, firstSpace);
    return token.toUpperCase(java.util.Locale.ROOT);
  }

  private void triggerImmediateTick(QueueTarget queueTarget) {
    try {
      tickService.processTick(queueTarget.tenantId(), queueTarget.queueTargetId());
    } catch (RuntimeException ex) {
      logger.warn(
          "Immediate tick kick failed tenantId={} queueTargetId={}",
          queueTarget.tenantId(),
          queueTarget.queueTargetId(),
          ex);
    }
  }

  private void publishScriptEvent(SessionContext context, GameplayCommand gameplayCommand) {
    try {
      scriptEventPublisher.publishCommandEvent(context, gameplayCommand);
    } catch (RuntimeException ex) {
      logger.warn(
          "Script event publish failed commandId={} tenantId={} gameInstanceId={}",
          gameplayCommand.getCommandId(),
          gameplayCommand.getTenantId(),
          gameplayCommand.getGameInstanceId(),
          ex);
    }
  }

  private Optional<SessionContext> resolveSessionContext(String sessionIdText) {
    try {
      long sessionId = Long.parseLong(sessionIdText);
      return sessionContextService.findBySessionId(sessionId);
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private Optional<QueueTarget> resolveQueueTarget(
      String sessionIdText, Optional<SessionContext> sessionContext) {
    try {
      long sessionId = Long.parseLong(sessionIdText);
      if (sessionContext.isPresent()) {
        SessionContext context = sessionContext.get();
        long queueTargetId = context.gameInstanceId() > 0 ? context.gameInstanceId() : sessionId;
        return Optional.of(new QueueTarget(context.tenantId(), queueTargetId));
      }
      return gameInstanceRepository
          .findById(sessionId)
          .map(GameInstance::getTenantId)
          .map(tenantId -> new QueueTarget(tenantId, sessionId));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private RoutingMetadata resolveRoutingMetadata(
      Optional<SessionContext> sessionContext, QueueTarget queueTarget) {
    if (sessionContext.isEmpty()) {
      return new RoutingMetadata("UNSPECIFIED", null, null, null);
    }
    SessionContext context = sessionContext.orElseThrow();
    RoutingBundle contextRoutingBundle =
        normalizeRoutingBundle(context.worldSlug(), context.realmSlug(), context.pointerVersion());
    if (context.playableStateScope() != null && !context.playableStateScope().isBlank()) {
      if (contextRoutingBundle.isPresent()) {
        return new RoutingMetadata(
            context.playableStateScope(),
            contextRoutingBundle.worldSlug(),
            contextRoutingBundle.realmSlug(),
            contextRoutingBundle.pointerVersion());
      }
      Optional<GameplayAdmissionPointerSnapshot> pointer =
          resolveRoutingPointer(context, queueTarget);
      if (pointer.isPresent()) {
        GameplayAdmissionPointerSnapshot snapshot = pointer.orElseThrow();
        RoutingBundle pointerRoutingBundle =
            normalizeRoutingBundle(
                snapshot.worldSlug(), snapshot.realmSlug(), snapshot.pointerVersion());
        return new RoutingMetadata(
            firstNonBlank(blankToNull(snapshot.stateScope()), context.playableStateScope()),
            pointerRoutingBundle.worldSlug(),
            pointerRoutingBundle.realmSlug(),
            pointerRoutingBundle.pointerVersion());
      }
      return new RoutingMetadata(context.playableStateScope(), null, null, null);
    }
    Optional<GameplayAdmissionPointerSnapshot> pointer =
        resolveRoutingPointer(context, queueTarget);
    if (pointer.isPresent()) {
      GameplayAdmissionPointerSnapshot snapshot = pointer.orElseThrow();
      RoutingBundle pointerRoutingBundle =
          normalizeRoutingBundle(
              snapshot.worldSlug(), snapshot.realmSlug(), snapshot.pointerVersion());
      return new RoutingMetadata(
          blankToNull(snapshot.stateScope()),
          pointerRoutingBundle.worldSlug(),
          pointerRoutingBundle.realmSlug(),
          pointerRoutingBundle.pointerVersion());
    }
    return new RoutingMetadata(
        "UNSPECIFIED",
        contextRoutingBundle.worldSlug(),
        contextRoutingBundle.realmSlug(),
        contextRoutingBundle.pointerVersion());
  }

  private Optional<GameplayAdmissionPointerSnapshot> resolveRoutingPointer(
      SessionContext context, QueueTarget queueTarget) {
    if (context.worldSlug() != null
        && !context.worldSlug().isBlank()
        && context.realmSlug() != null
        && !context.realmSlug().isBlank()) {
      return gameplayAdmissionPointerAuthorityService.findPointer(
          context.worldSlug(), context.realmSlug());
    }
    if (context.bootstrapGameInstanceId() > 0) {
      return gameplayAdmissionPointerAuthorityService.findByRuntimeTarget(
          context.tenantId(), context.bootstrapGameInstanceId());
    }
    if (context.gameInstanceId() > 0) {
      return gameplayAdmissionPointerAuthorityService.findByRuntimeTarget(
          context.tenantId(), context.gameInstanceId());
    }
    return gameplayAdmissionPointerAuthorityService.findByRuntimeTarget(
        queueTarget.tenantId(), queueTarget.queueTargetId());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String firstNonBlank(String primary, String fallback) {
    String normalizedPrimary = blankToNull(primary);
    return normalizedPrimary != null ? normalizedPrimary : blankToNull(fallback);
  }

  private static RoutingBundle normalizeRoutingBundle(
      String worldSlug, String realmSlug, Long pointerVersion) {
    String normalizedWorldSlug = blankToNull(worldSlug);
    String normalizedRealmSlug = blankToNull(realmSlug);
    Long normalizedPointerVersion =
        pointerVersion != null && pointerVersion > 0L ? pointerVersion : null;
    boolean hasAny =
        normalizedWorldSlug != null
            || normalizedRealmSlug != null
            || normalizedPointerVersion != null;
    boolean hasAll =
        normalizedWorldSlug != null
            && normalizedRealmSlug != null
            && normalizedPointerVersion != null;
    if (!hasAny || !hasAll) {
      return RoutingBundle.EMPTY;
    }
    return new RoutingBundle(normalizedWorldSlug, normalizedRealmSlug, normalizedPointerVersion);
  }
}
