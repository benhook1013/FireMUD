package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.shared.v1.ErrorDetail;
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
  private final EffectiveSettingsResolver settingsResolver;
  private final MeterRegistry meterRegistry;
  private final LookCacheService lookCacheService;
  private final TextPlayerOutputRenderer outputRenderer;

  public PlayerOutput describePlayerOutput(String sessionId, boolean includeLongDescription) {
    return describePlayerOutput(
        sessionId,
        includeLongDescription,
        includeLongDescription
            ? net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
                .EXPLICIT_LOOK
            : net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
                .QUICKLOOK);
  }

  public PlayerOutput describePlayerOutput(
      String sessionId,
      boolean includeLongDescription,
      net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason refreshReason) {
    return describePlayerOutput(
        sessionId,
        includeLongDescription,
        refreshReason,
        net.firedevops.firemud.gamesession.presentation.LookViewOutput.defaultBriefRenderingHint(
            refreshReason, includeLongDescription));
  }

  public PlayerOutput describePlayerOutput(
      String sessionId,
      boolean includeLongDescription,
      net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason refreshReason,
      net.firedevops.firemud.gamesession.presentation.LookViewOutput.BriefRenderingHint
          briefRenderingHint) {
    Optional<SessionContext> maybeContext =
        sessionAuthenticationService.resolveSessionContext(sessionId);
    if (maybeContext.isEmpty()) {
      meterRegistry.counter(INVOCATIONS_METRIC).increment();
      return null;
    }
    SessionContext context = maybeContext.get();
    try (GameplayLoggingContext ignored = GameplayLoggingContext.from(context)) {
      String tenantTag = Long.toString(context.tenantId());
      meterRegistry.counter(INVOCATIONS_METRIC).increment();
      try {
        LookResult lookResult = resolveLook(context);
        if (lookResult.hasError()) {
          ErrorDetail error = lookResult.getError();
          String errorCode =
              StringUtils.hasText(error.getCode()) ? error.getCode() : "LOOK_UNAVAILABLE";
          recordFailure(
              context, tenantTag, errorCode, new IllegalStateException(error.getMessage()));
          return PlayerOutput.error(
              errorCode,
              errorMessage(error.getMessage()),
              lookErrorMessageKey(errorCode),
              Map.of());
        }
        return toPlayerOutput(
            context, lookResult, includeLongDescription, refreshReason, briefRenderingHint);
      } catch (StatusRuntimeException ex) {
        String errorCode = mapStatusToError(ex);
        recordFailure(context, tenantTag, errorCode, ex);
        return PlayerOutput.error(
            errorCode,
            errorMessage(ex.getStatus().getDescription()),
            lookErrorMessageKey(errorCode),
            Map.of());
      } catch (RuntimeException ex) {
        recordFailure(context, tenantTag, "UNEXPECTED", ex);
        return PlayerOutput.error(
            "UNEXPECTED", "Internal LOOK failure", "error.look.internal-failure", Map.of());
      }
    }
  }

  PlayerOutput toPlayerOutput(SessionContext context, LookResult lookResult) {
    return toPlayerOutput(
        context,
        lookResult,
        true,
        net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason.EXPLICIT_LOOK);
  }

  PlayerOutput toPlayerOutput(
      SessionContext context, LookResult lookResult, boolean includeLongDescription) {
    return toPlayerOutput(
        context,
        lookResult,
        includeLongDescription,
        includeLongDescription
            ? net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
                .EXPLICIT_LOOK
            : net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
                .QUICKLOOK);
  }

  PlayerOutput toPlayerOutput(
      SessionContext context,
      LookResult lookResult,
      boolean includeLongDescription,
      net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason refreshReason) {
    return toPlayerOutput(
        context,
        lookResult,
        includeLongDescription,
        refreshReason,
        net.firedevops.firemud.gamesession.presentation.LookViewOutput.defaultBriefRenderingHint(
            refreshReason, includeLongDescription));
  }

  PlayerOutput toPlayerOutput(
      SessionContext context,
      LookResult lookResult,
      boolean includeLongDescription,
      net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason refreshReason,
      net.firedevops.firemud.gamesession.presentation.LookViewOutput.BriefRenderingHint
          briefRenderingHint) {
    PlayerOutput output =
        lookTextRenderer.toPlayerOutput(
            lookResult, includeLongDescription, refreshReason, briefRenderingHint);
    cacheLook(context, lookResult, output);
    return output;
  }

  private LookResult resolveLook(SessionContext context) {
    String roomId =
        StringUtils.hasText(context.roomInstanceId())
            ? context.roomInstanceId()
            : gameLogicProperties.getDefaultRoomId();
    return gameLogicClient.resolveLook(
        context, roomId, StringUtils.hasText(context.localeTag()) ? context.localeTag() : "");
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

  private String errorMessage(String description) {
    return description != null && !description.isBlank() ? description : "Look unavailable";
  }

  private void recordFailure(
      SessionContext context, String tenantTag, String errorTag, RuntimeException ex) {
    meterRegistry.counter(FAILURES_METRIC, "error", errorTag).increment();
    LOG.warn(
        "LOOK failed tenantId={} gameInstanceId={} characterId={} error={} reason={}",
        tenantTag,
        context.gameInstanceId(),
        context.characterId(),
        errorTag,
        ex.getMessage(),
        ex);
  }

  private void cacheLook(SessionContext context, LookResult lookResult, PlayerOutput output) {
    long cacheKey = effectiveLookCacheKey(context);
    try {
      PresentationProperties effectivePresentation = settingsResolver.presentation(context);
      String localeTag = StringUtils.hasText(context.localeTag()) ? context.localeTag() : null;
      String rendered = outputRenderer.render(output, localeTag, effectivePresentation);
      lookCacheService.cache(
          context.tenantId(),
          cacheKey,
          lookRoomId(lookResult),
          rendered,
          outputRenderer.renderSuccessfulForCommandType(
              TextCommandType.LOOK, java.util.List.of(output), localeTag, effectivePresentation));
    } catch (RuntimeException ex) {
      LOG.warn(
          "Failed to cache LOOK tenantId={} gameInstanceId={} characterId={} cacheKey={}",
          context.tenantId(),
          context.gameInstanceId(),
          context.characterId(),
          cacheKey,
          ex);
    }
  }

  private String lookRoomId(LookResult lookResult) {
    return lookResult.getRoomInstance().getRoomInstanceId();
  }

  private long effectiveLookCacheKey(SessionContext context) {
    return context.gameInstanceId() > 0 ? context.gameInstanceId() : context.sessionId();
  }

  private String lookErrorMessageKey(String errorCode) {
    if (errorCode == null) {
      return null;
    }
    return switch (errorCode) {
      case "ROOM_NOT_FOUND" -> "error.look.room-not-found";
      case "WORLD_UNAVAILABLE" -> "error.look.world-unavailable";
      case "ENTITY_UNAVAILABLE" -> "error.look.entity-unavailable";
      case "NOT_AUTHORIZED" -> "error.look.not-authorized";
      case "LOOK_UNAVAILABLE" -> "error.look.unavailable";
      default -> null;
    };
  }
}
