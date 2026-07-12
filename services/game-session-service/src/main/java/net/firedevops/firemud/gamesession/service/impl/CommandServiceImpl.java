package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.opentelemetry.api.trace.Span;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.command.text.GameplayLoggingContext;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.logging.GameSessionCommandLogSanitizer;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.AuthoredCommandAdmission;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionIdParsing;
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
  private final RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private final SessionAuthenticationService sessionAuthenticationService;
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;
  private final ScriptEventPublisher scriptEventPublisher;

  private record QueueTarget(long tenantId, long queueTargetId) {}

  private record RoutingMetadata(
      String playableStateScope, String worldSlug, String realmSlug, Long pointerVersion) {}

  public CommandServiceImpl(
      TickService tickService,
      SessionRateLimiter sessionRateLimiter,
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      SessionAuthenticationService sessionAuthenticationService,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      ScriptEventPublisher scriptEventPublisher) {
    this.tickService = tickService;
    this.sessionRateLimiter = sessionRateLimiter;
    this.gameInstanceRepository = gameInstanceRepository;
    this.gameplayCommandRepository = gameplayCommandRepository;
    this.runtimeRegionStatusRepository = runtimeRegionStatusRepository;
    this.sessionAuthenticationService = sessionAuthenticationService;
    this.gameplayAdmissionPointerAuthorityService = gameplayAdmissionPointerAuthorityService;
    this.scriptEventPublisher = scriptEventPublisher;
  }

  @Override
  public CommandEnqueueResult enqueue(
      String sessionIdText, String command, boolean requiresSoloTick) {
    return enqueue(sessionIdText, command, requiresSoloTick, null);
  }

  @Override
  public CommandEnqueueResult enqueue(
      String sessionIdText,
      String command,
      boolean requiresSoloTick,
      AuthoredCommandAdmission authoredAdmission) {
    String traceId = Span.current().getSpanContext().getTraceId();
    if (command == null || command.isBlank()) {
      return CommandEnqueueResult.failure("INVALID_ARGUMENT", "Command cannot be blank");
    }

    long sessionId;
    try {
      sessionId = SessionIdParsing.require(sessionIdText);
    } catch (IllegalArgumentException ex) {
      return CommandEnqueueResult.failure("INVALID_ARGUMENT", ex.getMessage());
    }

    var sessionContext = resolveSessionContext(sessionIdText);
    Optional<QueueTarget> queueTarget = resolveQueueTarget(sessionContext);
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

      if (!sessionRateLimiter.allow(sessionId)) {
        return CommandEnqueueResult.failure("RATE_LIMIT", "Command rate limit exceeded");
      }

      if (queueTarget.isEmpty()) {
        return CommandEnqueueResult.failure("NOT_FOUND", "Unable to resolve command queue target");
      }

      GameplayCommand gameplayCommand =
          persistAcceptedCommand(
              sessionId,
              command,
              requiresSoloTick,
              queueTarget.get(),
              sessionContext,
              authoredAdmission);
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
        sessionContext
            .filter(SessionContext::hasGameplayRegionBinding)
            .ifPresent(context -> publishScriptEvent(context, gameplayCommand));
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
      Optional<SessionContext> sessionContext,
      AuthoredCommandAdmission authoredAdmission) {
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
        .ifPresent(
            characterId -> {
              gameplayCommand.setCharacterId(characterId);
              gameplayCommand.setTargetEntityId(Long.toString(characterId));
            });
    gameplayCommand.setCommandName(commandName(command));
    if (authoredAdmission != null) {
      gameplayCommand.setCommandName(authoredAdmission.commandId());
      gameplayCommand.setAdmittedReleaseBundleId(authoredAdmission.releaseBundleId());
      gameplayCommand.setAdmittedVersionId(authoredAdmission.versionId());
      gameplayCommand.setDeclaredEffectsJson(authoredAdmission.declaredEffectsJson());
    }
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
    resolveRuntimeScope(queueTarget)
        .ifPresent(
            runtimeScope -> {
              gameplayCommand.setRegionId(runtimeScope.getRegionId());
              gameplayCommand.setRegionEpoch(runtimeScope.getRegionEpoch());
            });
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
    return sessionAuthenticationService.resolveUnverifiedSessionContext(sessionIdText);
  }

  private Optional<QueueTarget> resolveQueueTarget(Optional<SessionContext> sessionContext) {
    if (sessionContext.isEmpty()) {
      return Optional.empty();
    }
    SessionContext context = sessionContext.get();
    if (context.tenantId() <= 0) {
      return Optional.empty();
    }
    if (context.gameInstanceId() > 0) {
      return Optional.of(new QueueTarget(context.tenantId(), context.gameInstanceId()));
    }
    if (hasBootstrapRoutingAuthority(context)) {
      return Optional.of(new QueueTarget(context.tenantId(), context.bootstrapGameInstanceId()));
    }
    return Optional.empty();
  }

  private RoutingMetadata resolveRoutingMetadata(
      Optional<SessionContext> sessionContext, QueueTarget queueTarget) {
    if (sessionContext.isEmpty()) {
      return unspecifiedRoutingMetadata();
    }
    SessionContext context = sessionContext.orElseThrow();
    GameplayAdmissionPointerSnapshots.RoutingBundle contextRoutingBundle =
        GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
            context.worldSlug(), context.realmSlug(), context.pointerVersion());
    if (context.playableStateScope() != null && !context.playableStateScope().isBlank()) {
      long runtimeTarget = expectedRuntimeTarget(context, queueTarget);
      if (contextRoutingBundle != null
          && GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(
              gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(
                  context.tenantId(), runtimeTarget),
              context.tenantId(),
              runtimeTarget,
              contextRoutingBundle.worldSlug(),
              contextRoutingBundle.realmSlug(),
              contextRoutingBundle.pointerVersion(),
              context.playableStateScope())) {
        return new RoutingMetadata(
            context.playableStateScope(),
            contextRoutingBundle.worldSlug(),
            contextRoutingBundle.realmSlug(),
            contextRoutingBundle.pointerVersion());
      }
      return resolveAuthoritativeRoutingMetadata(context, queueTarget)
          .orElseGet(CommandServiceImpl::unspecifiedRoutingMetadata);
    }
    Optional<GameplayAdmissionPointerSnapshot> pointer =
        resolveRoutingPointer(context, queueTarget);
    if (pointer.isPresent()) {
      GameplayAdmissionPointerSnapshot snapshot = pointer.orElseThrow();
      GameplayAdmissionPointerSnapshots.RoutingBundle pointerRoutingBundle =
          GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
              snapshot.worldSlug(), snapshot.realmSlug(), snapshot.pointerVersion());
      return new RoutingMetadata(
          blankToNull(snapshot.stateScope()),
          pointerRoutingBundle == null ? null : pointerRoutingBundle.worldSlug(),
          pointerRoutingBundle == null ? null : pointerRoutingBundle.realmSlug(),
          pointerRoutingBundle == null ? null : pointerRoutingBundle.pointerVersion());
    }
    return new RoutingMetadata(
        "UNSPECIFIED",
        contextRoutingBundle == null ? null : contextRoutingBundle.worldSlug(),
        contextRoutingBundle == null ? null : contextRoutingBundle.realmSlug(),
        contextRoutingBundle == null ? null : contextRoutingBundle.pointerVersion());
  }

  private Optional<GameplayAdmissionPointerSnapshot> resolveRoutingPointer(
      SessionContext context, QueueTarget queueTarget) {
    long runtimeTarget = expectedRuntimeTarget(context, queueTarget);
    if (context.tenantId() > 0 && runtimeTarget > 0) {
      return resolveUnambiguousRuntimePointer(context.tenantId(), runtimeTarget);
    }
    return resolveUnambiguousRuntimePointer(queueTarget.tenantId(), queueTarget.queueTargetId());
  }

  private long expectedRuntimeTarget(SessionContext context, QueueTarget queueTarget) {
    if (context.gameInstanceId() > 0) {
      return context.gameInstanceId();
    }
    if (hasBootstrapRoutingAuthority(context)) {
      return context.bootstrapGameInstanceId();
    }
    return queueTarget.queueTargetId();
  }

  private boolean hasBootstrapRoutingAuthority(SessionContext context) {
    return context.bootstrapGameInstanceId() > 0
        && GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
                context.worldSlug(), context.realmSlug(), context.pointerVersion())
            != null;
  }

  private Optional<GameplayAdmissionPointerSnapshot> resolveUnambiguousRuntimePointer(
      long tenantId, long gameInstanceId) {
    return GameplayAdmissionPointerSnapshots.singularCompletePointer(
        gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(tenantId, gameInstanceId));
  }

  private Optional<RoutingMetadata> resolveAuthoritativeRoutingMetadata(
      SessionContext context, QueueTarget queueTarget) {
    return resolveRoutingPointer(context, queueTarget)
        .flatMap(CommandServiceImpl::authoritativeRoutingMetadata);
  }

  private static Optional<RoutingMetadata> authoritativeRoutingMetadata(
      GameplayAdmissionPointerSnapshot snapshot) {
    String playableStateScope = blankToNull(snapshot.stateScope());
    GameplayAdmissionPointerSnapshots.RoutingBundle routingBundle =
        GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
            snapshot.worldSlug(), snapshot.realmSlug(), snapshot.pointerVersion());
    if (playableStateScope == null || routingBundle == null) {
      return Optional.empty();
    }
    return Optional.of(
        new RoutingMetadata(
            playableStateScope,
            routingBundle.worldSlug(),
            routingBundle.realmSlug(),
            routingBundle.pointerVersion()));
  }

  private static RoutingMetadata unspecifiedRoutingMetadata() {
    return new RoutingMetadata("UNSPECIFIED", null, null, null);
  }

  private Optional<RuntimeRegionStatus> resolveRuntimeScope(QueueTarget queueTarget) {
    return runtimeRegionStatusRepository
        .findByTenantIdAndGameInstanceId(queueTarget.tenantId(), queueTarget.queueTargetId())
        .filter(status -> status.getRegionId() != null && !status.getRegionId().isBlank())
        .filter(status -> status.getRegionEpoch() > 0);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
