package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.opentelemetry.api.trace.Span;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.CommandService;
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
public class CommandServiceImpl implements CommandService {
  private static final Logger logger = LoggingUtil.getLogger(CommandServiceImpl.class);

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected dependencies are internal")
  private final TickService tickService;

  private final SessionRateLimiter sessionRateLimiter;
  private final DevIsolatedProperties devIsolatedProperties;
  private final GameInstanceRepository gameInstanceRepository;

  public CommandServiceImpl(
      TickService tickService,
      SessionRateLimiter sessionRateLimiter,
      DevIsolatedProperties devIsolatedProperties,
      GameInstanceRepository gameInstanceRepository) {
    this.tickService = tickService;
    this.sessionRateLimiter = sessionRateLimiter;
    this.devIsolatedProperties = devIsolatedProperties;
    this.gameInstanceRepository = gameInstanceRepository;
  }

  @Override
  public CommandEnqueueResult enqueue(
      String sessionIdText, String command, boolean requiresSoloTick) {
    String traceId = Span.current().getSpanContext().getTraceId();
    String tenantContext = resolveTenantContext(sessionIdText);
    try (MDC.MDCCloseable trace = MDC.putCloseable("traceId", traceId);
        MDC.MDCCloseable tenant = MDC.putCloseable("tenantId", tenantContext)) {
      logger.info(
          "Enqueue request traceId={} tenantId={} sessionId={} command={}",
          traceId,
          tenantContext,
          sessionIdText,
          command);

      if (devIsolatedProperties.isDevIsolated()) {
        logger.info(
            "Dev-isolated mode enabled; acknowledging enqueue for session {} command {}",
            sessionIdText,
            command);
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

      try {
        tickService.enqueueCommand(sessionId, command, requiresSoloTick);
        return CommandEnqueueResult.success();
      } catch (IllegalArgumentException ex) {
        return CommandEnqueueResult.failure("INVALID_ARGUMENT", ex.getMessage());
      }
    }
  }

  private String resolveTenantContext(String sessionIdText) {
    try {
      long sessionId = Long.parseLong(sessionIdText);
      return gameInstanceRepository
          .findById(sessionId)
          .map(GameInstance::getTenantId)
          .map(String::valueOf)
          .orElse("unknown");
    } catch (NumberFormatException ex) {
      return "unknown";
    }
  }
}
