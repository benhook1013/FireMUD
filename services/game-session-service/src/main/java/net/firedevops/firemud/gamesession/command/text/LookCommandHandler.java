package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.command.text.LookCommandConstants;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected services/configuration are stored internally")
public final class LookCommandHandler {
  private static final Logger LOG = LoggerFactory.getLogger(LookCommandHandler.class);
  private static final String INVOCATIONS_METRIC = "gamesession.command.look.invocations";
  private static final String FAILURES_METRIC = "gamesession.command.look.failures";

  private final GameLogicClient gameLogicClient;
  private final LookTextRenderer lookTextRenderer;
  private final SessionAuthenticationService sessionAuthenticationService;
  private final GameLogicProperties gameLogicProperties;
  private final MeterRegistry meterRegistry;
  private final LookCacheService lookCacheService;
  private final DevIsolatedProperties devIsolatedProperties;

  public String describe(String sessionId) {
    Optional<SessionContext> maybeContext =
        sessionAuthenticationService.resolveSessionContext(sessionId);
    if (maybeContext.isEmpty()) {
      meterRegistry.counter(INVOCATIONS_METRIC, "tenantId", "unknown").increment();
      return null;
    }
    SessionContext context = maybeContext.get();
    String tenantTag = Long.toString(context.tenantId());
    meterRegistry.counter(INVOCATIONS_METRIC, "tenantId", tenantTag).increment();
    if (devIsolatedProperties.isDevIsolated()) {
      return LookCommandConstants.ROOM_DESCRIPTION;
    }
    try {
      LookResult lookResult = resolveLook(context);
      String rendered = lookTextRenderer.render(lookResult);
      if (!devIsolatedProperties.isDevIsolated()) {
        cacheLook(context, lookResult, rendered);
      }
      return rendered;
    } catch (StatusRuntimeException ex) {
      String errorCode = mapStatusToError(ex);
      recordFailure(tenantTag, errorCode, ex);
      return formatErrorResponse(errorCode, ex.getStatus().getDescription());
    } catch (RuntimeException ex) {
      recordFailure(tenantTag, "UNEXPECTED", ex);
      return formatErrorResponse("UNEXPECTED", "Internal LOOK failure");
    }
  }

  public String describeProtocol(String sessionId) {
    Optional<SessionContext> maybeContext =
        sessionAuthenticationService.resolveSessionContext(sessionId);
    if (maybeContext.isEmpty()) {
      meterRegistry.counter(INVOCATIONS_METRIC, "tenantId", "unknown").increment();
      return null;
    }
    SessionContext context = maybeContext.get();
    String rendered = describe(sessionId);
    if (rendered == null || rendered.isBlank() || rendered.startsWith("ERROR ")) {
      return rendered;
    }
    return buildProtocolResponse(rendered);
  }

  String renderProtocol(SessionContext context, LookResult lookResult) {
    String rendered = lookTextRenderer.render(lookResult);
    if (!devIsolatedProperties.isDevIsolated()) {
      cacheLook(context, lookResult, rendered);
    }
    return buildProtocolResponse(rendered);
  }

  private LookResult resolveLook(SessionContext context) {
    String roomId =
        StringUtils.hasText(context.roomInstanceId())
            ? context.roomInstanceId()
            : gameLogicProperties.getDefaultRoomId();
    return gameLogicClient.resolveLook(
        Long.toString(context.tenantId()),
        Long.toString(context.sessionId()),
        Long.toString(context.characterId()),
        roomId);
  }

  private String mapStatusToError(StatusRuntimeException ex) {
    Status.Code code = ex.getStatus().getCode();
    String description = ex.getStatus().getDescription();
    return switch (code) {
      case NOT_FOUND -> "ROOM_NOT_FOUND";
      case UNAVAILABLE ->
          description != null && description.contains("EntityManagement")
              ? "ENTITY_UNAVAILABLE"
              : "WORLD_UNAVAILABLE";
      case DEADLINE_EXCEEDED -> "WORLD_UNAVAILABLE";
      case PERMISSION_DENIED -> "NOT_AUTHORIZED";
      default -> "LOOK_UNAVAILABLE";
    };
  }

  private String formatErrorResponse(String code, String description) {
    String message =
        description != null && !description.isBlank() ? description : "Look unavailable";
    return "ERROR " + code + " " + message;
  }

  private void recordFailure(String tenantTag, String errorTag, RuntimeException ex) {
    meterRegistry.counter(FAILURES_METRIC, "tenantId", tenantTag, "error", errorTag).increment();
    LOG.warn(
        "LOOK failed for tenantId={}, error={}, sessionId={}",
        tenantTag,
        errorTag,
        ex.getMessage(),
        ex);
  }

  private void cacheLook(SessionContext context, LookResult lookResult, String rendered) {
    long cacheKey = effectiveLookCacheKey(context);
    try {
      lookCacheService.cache(
          context.tenantId(),
          cacheKey,
          lookRoomId(lookResult),
          rendered,
          buildProtocolResponse(rendered));
    } catch (RuntimeException ex) {
      LOG.warn("Failed to cache LOOK for game instance {}", cacheKey, ex);
    }
  }

  public Optional<String> cachedLook(String tenantIdHeader, String sessionIdHeader) {
    if (devIsolatedProperties.isDevIsolated()) {
      return Optional.empty();
    }
    if (!StringUtils.hasText(tenantIdHeader) || !StringUtils.hasText(sessionIdHeader)) {
      return Optional.empty();
    }
    long tenantId;
    long sessionId;
    try {
      tenantId = Long.parseLong(tenantIdHeader);
      sessionId = Long.parseLong(sessionIdHeader);
    } catch (NumberFormatException ex) {
      LOG.debug(
          "Invalid cached LOOK identifiers tenant={} session={}",
          tenantIdHeader,
          sessionIdHeader,
          ex);
      return Optional.empty();
    }
    return lookCacheService.get(tenantId, sessionId).map(LookCacheService.CachedLook::protocolText);
  }

  String buildProtocolResponse(String rendered) {
    return "OK LOOK\n" + rendered + "\n\n";
  }

  private String lookRoomId(LookResult lookResult) {
    return lookResult.getRoomInstance().getRoomInstanceId();
  }

  private long effectiveLookCacheKey(SessionContext context) {
    return context.gameInstanceId() > 0 ? context.gameInstanceId() : context.sessionId();
  }
}
