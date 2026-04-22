package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamelogic.v1.CommunicationType;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationResponse;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.CommunicationOutputMapper;
import net.firedevops.firemud.gamesession.service.CommunicationRecipientDeliveryService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Handles authenticated built-in communication commands through Game Logic. */
@Component
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected services/configuration are stored internally")
public class CommunicationCommandHandler {
  private static final Logger LOG = LoggerFactory.getLogger(CommunicationCommandHandler.class);
  private static final String COMMUNICATION_INVOCATIONS_METRIC =
      "gamesession.command.communication.invocations";
  private static final String COMMUNICATION_FAILURES_METRIC =
      "gamesession.command.communication.failures";

  private final EntityManagementClient entityManagementClient;
  private final GameplayWorldCatalog gameplayWorldCatalog;
  private final GameLogicClient gameLogicClient;
  private final GameLogicProperties gameLogicProperties;
  private final SessionContextService sessionContextService;
  private final CommunicationRecipientDeliveryService recipientDeliveryService;
  private final CommunicationOutputMapper communicationOutputMapper;
  private final MeterRegistry meterRegistry;

  public CommunicationCommandHandlingResult handle(SessionContext context, TextCommand command) {
    return handle(context, command, null);
  }

  public CommunicationCommandHandlingResult handle(
      SessionContext context, TextCommand command, String effectId) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(command, "command must not be null");

    try (GameplayLoggingContext ignored = GameplayLoggingContext.from(context)) {
      String tenantTag = Long.toString(context.tenantId());
      String typeTag = command.type().name().toLowerCase(Locale.ROOT);
      meterRegistry.counter(COMMUNICATION_INVOCATIONS_METRIC, "type", typeTag).increment();
      meterRegistry.counter(metricPrefix(command.type()) + ".invocations").increment();

      ParsedCommunication parsed = parseCommunication(context, command);
      if (!parsed.valid()) {
        logFailure(
            "INVALID_ARGUMENT", parsed.errorMessage(), context, tenantTag, command.type(), null);
        return new CommunicationCommandHandlingResult(
            CommandEnqueueResult.failure("INVALID_ARGUMENT", parsed.errorMessage()), List.of());
      }

      try {
        SendCommunicationResponse response =
            gameLogicClient.sendCommunication(
                context,
                StringUtils.hasText(context.characterName())
                    ? context.characterName()
                    : context.loginName(),
                StringUtils.hasText(context.roomInstanceId())
                    ? context.roomInstanceId()
                    : gameLogicProperties.getDefaultRoomId(),
                mapType(command.type()),
                parsed.message(),
                parsed.targetCharacterId().orElse(""),
                parsed.targetCharacterName().orElse(""),
                effectId);
        if (!response.getSuccess()) {
          String errorMessage =
              response.hasError() && response.getError().getMessage() != null
                  ? response.getError().getMessage()
                  : "Message delivery failed";
          String errorTag =
              response.hasError() && response.getError().getCode() != null
                  ? response.getError().getCode()
                  : "UNAVAILABLE";
          logFailure(errorTag, errorMessage, context, tenantTag, command.type(), null);
          return new CommunicationCommandHandlingResult(
              CommandEnqueueResult.failure("COMMUNICATION_NOT_DELIVERED", errorMessage), List.of());
        }

        recipientDeliveryService.deliver(context, response);

        return new CommunicationCommandHandlingResult(
            CommandEnqueueResult.success(),
            List.of(communicationOutputMapper.actorOutput(command, response)));
      } catch (RuntimeException ex) {
        logFailure("UNAVAILABLE", "Game Logic unavailable", context, tenantTag, command.type(), ex);
        return new CommunicationCommandHandlingResult(
            CommandEnqueueResult.failure("COMMUNICATION_NOT_DELIVERED", "Game Logic unavailable"),
            List.of());
      }
    }
  }

  private ParsedCommunication parseCommunication(SessionContext context, TextCommand command) {
    return switch (command.type()) {
      case SAY -> parseSay(command);
      case WHISPER -> parseWhisper(command);
      case TELL -> parseTell(context, command);
      default ->
          new ParsedCommunication(
              false, null, Optional.empty(), Optional.empty(), "Unsupported communication command");
    };
  }

  private ParsedCommunication parseSay(TextCommand command) {
    String message = command.messagePayload().map(TextCommandPayload.Message::text).orElse(null);
    if (!StringUtils.hasText(message)) {
      return new ParsedCommunication(
          false, null, Optional.empty(), Optional.empty(), "SAY command requires a message");
    }
    return new ParsedCommunication(true, message, Optional.empty(), Optional.empty(), null);
  }

  private ParsedCommunication parseWhisper(TextCommand command) {
    Optional<TextCommandPayload.TargetedMessage> payload = command.targetedMessagePayload();
    if (payload.isPresent() && StringUtils.hasText(payload.get().target())) {
      return new ParsedCommunication(
          true,
          payload.get().message(),
          Optional.empty(),
          Optional.of(payload.get().target()),
          null);
    }
    return new ParsedCommunication(
        false,
        null,
        Optional.empty(),
        Optional.empty(),
        "WHISPER command requires a target and a message");
  }

  private ParsedCommunication parseTell(SessionContext context, TextCommand command) {
    Optional<TextCommandPayload.TargetedMessage> payload = command.targetedMessagePayload();
    if (payload.isEmpty()) {
      return new ParsedCommunication(
          false,
          null,
          Optional.empty(),
          Optional.empty(),
          "TELL command requires a target and a message");
    }
    String targetName = payload.orElseThrow().target();
    GameplayCatalogProperties.Realm currentRealm =
        gameplayWorldCatalog
            .resolveRealmByRuntimeTarget(context.tenantId(), context.gameInstanceId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No visible realm matches the current gameplay runtime target"));
    Optional<net.firedevops.firemud.entitymanagement.v1.Character> maybeTargetCharacter =
        entityManagementClient.findCharacterByName(
            context, toPlayableStateScope(currentRealm), targetName);
    if (maybeTargetCharacter.isEmpty()) {
      return new ParsedCommunication(
          false,
          null,
          Optional.empty(),
          Optional.of(targetName),
          "Character not found: " + targetName);
    }

    net.firedevops.firemud.entitymanagement.v1.Character targetCharacter =
        maybeTargetCharacter.orElseThrow();
    boolean onlineInGame =
        sessionContextService
            .findByGameplayName(context.tenantId(), context.gameInstanceId(), targetName)
            .isPresent();
    if (!onlineInGame) {
      return new ParsedCommunication(
          false,
          null,
          Optional.of(targetCharacter.getId()),
          Optional.of(targetCharacter.getName()),
          "Target is not available: " + targetName);
    }

    return new ParsedCommunication(
        true,
        payload.orElseThrow().message(),
        Optional.of(targetCharacter.getId()),
        Optional.of(targetCharacter.getName()),
        null);
  }

  private CommunicationType mapType(TextCommandType type) {
    return switch (type) {
      case SAY -> CommunicationType.SAY;
      case WHISPER -> CommunicationType.WHISPER;
      case TELL -> CommunicationType.TELL;
      default -> CommunicationType.COMMUNICATION_TYPE_UNSPECIFIED;
    };
  }

  private void logFailure(
      String errorTag,
      String reason,
      SessionContext context,
      String tenantTag,
      TextCommandType commandType,
      RuntimeException ex) {
    String typeTag = commandType.name().toLowerCase(Locale.ROOT);
    meterRegistry
        .counter(COMMUNICATION_FAILURES_METRIC, "type", typeTag, "error", errorTag)
        .increment();
    meterRegistry.counter(metricPrefix(commandType) + ".failures", "error", errorTag).increment();
    if (ex == null) {
      LOG.warn(
          "Communication failed tenantId={} gameInstanceId={} characterId={} type={} error={} reason={}",
          tenantTag,
          context.gameInstanceId(),
          context.characterId(),
          typeTag,
          errorTag,
          reason);
    } else {
      LOG.warn(
          "Communication failed tenantId={} gameInstanceId={} characterId={} type={} error={} reason={}",
          tenantTag,
          context.gameInstanceId(),
          context.characterId(),
          typeTag,
          errorTag,
          reason,
          ex);
    }
  }

  private String metricPrefix(TextCommandType type) {
    return switch (type) {
      case SAY -> "gamesession.command.say";
      case WHISPER -> "gamesession.command.whisper";
      case TELL -> "gamesession.command.tell";
      default -> "gamesession.command.communication";
    };
  }

  private PlayableStateScope toPlayableStateScope(GameplayCatalogProperties.Realm realm) {
    return switch (realm.getStateScope()) {
      case SHARED -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case ISOLATED -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
    };
  }

  private record ParsedCommunication(
      boolean valid,
      String message,
      Optional<String> targetCharacterId,
      Optional<String> targetCharacterName,
      String errorMessage) {}
}
