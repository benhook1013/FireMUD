package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.opentelemetry.api.trace.Span;
import java.util.Optional;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.command.text.GameplayLoggingContext;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.logging.GameSessionCommandLogSanitizer;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.SessionRateLimiter;
import net.firedevops.firemud.gamesession.service.TickService;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Default implementation of {@link CommandService}. */
@Service
@ConditionalOnProperty(
    name = "game-session.dev-isolated",
    havingValue = "false",
    matchIfMissing = false)
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
  private final DevIsolatedProperties devIsolatedProperties;
  private final GameInstanceRepository gameInstanceRepository;
  private final SessionContextService sessionContextService;

  private record QueueTarget(long tenantId, long queueTargetId) {}

  public CommandServiceImpl(
      TickService tickService,
      SessionRateLimiter sessionRateLimiter,
      DevIsolatedProperties devIsolatedProperties,
      GameInstanceRepository gameInstanceRepository,
      SessionContextService sessionContextService) {
    this.tickService = tickService;
    this.sessionRateLimiter = sessionRateLimiter;
    this.devIsolatedProperties = devIsolatedProperties;
    this.gameInstanceRepository = gameInstanceRepository;
    this.sessionContextService = sessionContextService;
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

      if (devIsolatedProperties.isDevIsolated()) {
        logger.info(
            "Dev-isolated mode enabled; acknowledging enqueue for session {} command {}",
            sessionIdText,
            GameSessionCommandLogSanitizer.sanitize(command));
        return CommandEnqueueResult.success();
      }

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

      try {
        tickService.enqueueCommand(
            queueTarget.get().tenantId(),
            queueTarget.get().queueTargetId(),
            command,
            requiresSoloTick);
        return CommandEnqueueResult.success();
      } catch (IllegalArgumentException ex) {
        return CommandEnqueueResult.failure("INVALID_ARGUMENT", ex.getMessage());
      }
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
}
