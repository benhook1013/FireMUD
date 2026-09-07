package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamelogic.v1.MoveResult;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.service.GameplayRuntimeRoomIds;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Handles authenticated MOVE-family commands through Game Logic. */
@Component
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected services/configuration are stored internally")
public class MoveCommandHandler {
  private static final Logger LOG = LoggerFactory.getLogger(MoveCommandHandler.class);
  private static final String INVOCATIONS_METRIC = "gamesession.command.move.invocations";
  private static final String FAILURES_METRIC = "gamesession.command.move.failures";

  private final GameLogicClient gameLogicClient;
  private final LookCommandHandler lookCommandHandler;
  private final GameLogicProperties gameLogicProperties;
  private final EffectiveSettingsResolver settingsResolver;
  private final MeterRegistry meterRegistry;

  public PreparedMoveCommandResult prepare(SessionContext context, TextCommand command) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(command, "command must not be null");

    String tenantTag = Long.toString(context.tenantId());
    try (GameplayLoggingContext ignored = GameplayLoggingContext.from(context)) {
      meterRegistry.counter(INVOCATIONS_METRIC).increment();
      String direction = extractDirection(command);
      if (!StringUtils.hasText(direction)) {
        return failureResult(
            "INVALID_ARGUMENT",
            "MOVE command requires a direction",
            "error.move.direction-required",
            Map.of(),
            tenantTag,
            Long.toString(context.gameInstanceId()),
            Long.toString(context.characterId()),
            null);
      }

      try {
        MoveResult response =
            gameLogicClient.resolveMove(
                context,
                StringUtils.hasText(context.roomInstanceId())
                    ? context.roomInstanceId()
                    : gameLogicProperties.getDefaultRoomId(),
                direction,
                StringUtils.hasText(context.localeTag()) ? context.localeTag() : "");
        if (!response.getSuccess()) {
          String code =
              response.hasError() && StringUtils.hasText(response.getError().getCode())
                  ? response.getError().getCode()
                  : "MOVE_UNAVAILABLE";
          String errorMessage =
              response.hasError() && StringUtils.hasText(response.getError().getMessage())
                  ? response.getError().getMessage()
                  : "Move unavailable";
          return failureResult(
              code,
              errorMessage,
              moveErrorMessageKey(code),
              moveErrorArguments(code, direction, context.roomInstanceId()),
              tenantTag,
              Long.toString(context.gameInstanceId()),
              Long.toString(context.characterId()),
              null);
        }

        if (!response.hasDestinationRoomInstance()) {
          return failureResult(
              "MOVE_UNAVAILABLE",
              "Move destination identity unavailable",
              "error.move.destination-unavailable",
              Map.of(),
              tenantTag,
              Long.toString(context.gameInstanceId()),
              Long.toString(context.characterId()),
              null);
        }

        RoomInstanceRef destinationRoom = response.getDestinationRoomInstance();
        if (!validDestination(context, destinationRoom)) {
          return failureResult(
              "MOVE_UNAVAILABLE",
              "Move destination identity is invalid",
              "error.move.destination-unavailable",
              Map.of(),
              tenantTag,
              Long.toString(context.gameInstanceId()),
              Long.toString(context.characterId()),
              null);
        }

        SessionContext updatedContext = updatedContext(context, destinationRoom);
        if (!settingsResolver.movement(updatedContext).postMoveLookEnabled()) {
          return new PreparedMoveCommandResult(
              CommandEnqueueResult.success(), null, updatedContext);
        }
        LookResult destinationLook;
        try {
          destinationLook = lookCommandHandler.resolveLook(updatedContext);
        } catch (StatusRuntimeException ex) {
          String code = LookCommandHandler.mapStatusToError(ex);
          String message =
              StringUtils.hasText(ex.getStatus().getDescription())
                  ? ex.getStatus().getDescription()
                  : "Move destination unavailable";
          return failureResult(
              code,
              message,
              moveErrorMessageKey(code),
              Map.of(),
              tenantTag,
              Long.toString(context.gameInstanceId()),
              Long.toString(context.characterId()),
              ex);
        }
        if (destinationLook.hasError()) {
          ErrorDetail error = destinationLook.getError();
          String code = StringUtils.hasText(error.getCode()) ? error.getCode() : "MOVE_UNAVAILABLE";
          String message =
              StringUtils.hasText(error.getMessage())
                  ? error.getMessage()
                  : "Move destination unavailable";
          return failureResult(
              code,
              message,
              moveErrorMessageKey(code),
              Map.of(),
              tenantTag,
              Long.toString(context.gameInstanceId()),
              Long.toString(context.characterId()),
              null);
        }
        if (!matchesDestination(destinationRoom, destinationLook)) {
          return failureResult(
              "MOVE_UNAVAILABLE",
              "Move destination LOOK identity is invalid",
              "error.move.destination-unavailable",
              Map.of(),
              tenantTag,
              Long.toString(context.gameInstanceId()),
              Long.toString(context.characterId()),
              null);
        }
        return new PreparedMoveCommandResult(
            CommandEnqueueResult.success(),
            lookCommandHandler.toPlayerOutput(
                updatedContext,
                destinationLook,
                true,
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
                    .MOVE_REFRESH,
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.BriefRenderingHint
                    .PREFER_BRIEF),
            updatedContext);
      } catch (RuntimeException ex) {
        return failureResult(
            "MOVE_UNAVAILABLE",
            "Game Logic unavailable",
            "error.move.unavailable",
            Map.of(),
            tenantTag,
            Long.toString(context.gameInstanceId()),
            Long.toString(context.characterId()),
            ex);
      }
    }
  }

  private String extractDirection(TextCommand command) {
    return command.directionalPayload().map(TextCommandPayload.Directional::direction).orElse("");
  }

  private SessionContext updatedContext(SessionContext current, RoomInstanceRef destinationRoom) {
    String destinationRoomId = destinationRoom.getRoomInstanceId();
    return new SessionContext(
        current.sessionId(),
        current.tenantId(),
        current.accountId(),
        current.loginName(),
        current.characterId(),
        current.characterName(),
        current.gameInstanceId(),
        destinationRoomId,
        current.jwt(),
        current.localeTag(),
        current.bootstrapGameInstanceId(),
        current.worldSlug(),
        current.realmSlug(),
        current.pointerVersion(),
        current.playableStateScope(),
        current.connectScopeId(),
        current.connectRequestId());
  }

  private boolean validDestination(SessionContext current, RoomInstanceRef destination) {
    if (destination == null
        || !GameplayRuntimeRoomIds.isCanonical(destination.getRoomInstanceId())) {
      return false;
    }
    return Long.toString(current.tenantId()).equals(destination.getTenantId())
        && Long.toString(current.gameInstanceId()).equals(destination.getGameInstanceId());
  }

  private boolean matchesDestination(RoomInstanceRef destination, LookResult lookResult) {
    return lookResult != null
        && lookResult.hasRoomInstance()
        && destination.equals(lookResult.getRoomInstance());
  }

  private PreparedMoveCommandResult failureResult(
      String errorCode,
      String message,
      String messageKey,
      Map<String, String> arguments,
      String tenantTag,
      String gameInstanceTag,
      String characterTag,
      RuntimeException ex) {
    meterRegistry.counter(FAILURES_METRIC, "error", errorCode).increment();
    if (ex == null) {
      LOG.warn(
          "MOVE failed tenantId={} gameInstanceId={} characterId={} error={} reason={}",
          tenantTag,
          gameInstanceTag,
          characterTag,
          errorCode,
          message);
    } else {
      LOG.warn(
          "MOVE failed tenantId={} gameInstanceId={} characterId={} error={} reason={}",
          tenantTag,
          gameInstanceTag,
          characterTag,
          errorCode,
          message,
          ex);
    }
    return new PreparedMoveCommandResult(
        CommandEnqueueResult.failure(errorCode, message),
        net.firedevops.firemud.gamesession.presentation.PlayerOutput.error(
            errorCode, message, messageKey, arguments),
        null);
  }

  private String moveErrorMessageKey(String errorCode) {
    if (errorCode == null) {
      return null;
    }
    return switch (errorCode) {
      case "INVALID_EXIT" -> "error.move.invalid-exit";
      case "WORLD_UNAVAILABLE" -> "error.move.world-unavailable";
      case "ENTITY_UNAVAILABLE" -> "error.move.entity-unavailable";
      case "NOT_AUTHORIZED" -> "error.move.not-authorized";
      case "ROOM_NOT_FOUND", "LOOK_UNAVAILABLE" -> "error.move.destination-unavailable";
      case "MOVE_UNAVAILABLE" -> "error.move.unavailable";
      default -> null;
    };
  }

  private Map<String, String> moveErrorArguments(
      String errorCode, String direction, String roomInstanceId) {
    if ("INVALID_EXIT".equals(errorCode)) {
      return Map.of(
          "direction", direction == null ? "" : direction.toUpperCase(java.util.Locale.ROOT),
          "roomId", roomInstanceId == null ? "" : roomInstanceId);
    }
    return Map.of();
  }
}
