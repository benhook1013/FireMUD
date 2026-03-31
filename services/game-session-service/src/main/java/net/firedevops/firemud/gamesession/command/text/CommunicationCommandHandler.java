package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamelogic.v1.CommunicationType;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationResponse;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
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
  private final GameLogicClient gameLogicClient;
  private final GameLogicProperties gameLogicProperties;
  private final SessionContextService sessionContextService;
  private final CommunicationRecipientDeliveryService recipientDeliveryService;
  private final MeterRegistry meterRegistry;

  public CommunicationCommandHandlingResult handle(SessionContext context, TextCommand command) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(command, "command must not be null");

    String tenantTag = Long.toString(context.tenantId());
    String typeTag = command.type().name().toLowerCase(Locale.ROOT);
    meterRegistry
        .counter(COMMUNICATION_INVOCATIONS_METRIC, "tenantId", tenantTag, "type", typeTag)
        .increment();
    meterRegistry
        .counter(metricPrefix(command.type()) + ".invocations", "tenantId", tenantTag)
        .increment();

    ParsedCommunication parsed = parseCommunication(context, command);
    if (!parsed.valid()) {
      logFailure("INVALID_ARGUMENT", parsed.errorMessage(), tenantTag, command.type(), null);
      return new CommunicationCommandHandlingResult(
          CommandEnqueueResult.failure("INVALID_ARGUMENT", parsed.errorMessage()), null);
    }

    try {
      SendCommunicationResponse response =
          gameLogicClient.sendCommunication(
              Long.toString(context.tenantId()),
              Long.toString(context.sessionId()),
              Long.toString(context.characterId()),
              Long.toString(context.accountId()),
              Long.toString(context.gameInstanceId()),
              StringUtils.hasText(context.characterName())
                  ? context.characterName()
                  : context.loginName(),
              StringUtils.hasText(context.roomInstanceId())
                  ? context.roomInstanceId()
                  : gameLogicProperties.getDefaultRoomId(),
              mapType(command.type()),
              parsed.message(),
              parsed.targetCharacterId().orElse(""),
              parsed.targetCharacterName().orElse(""));
      if (!response.getSuccess()) {
        String errorMessage =
            response.hasError() && response.getError().getMessage() != null
                ? response.getError().getMessage()
                : "Message delivery failed";
        String errorTag =
            response.hasError() && response.getError().getCode() != null
                ? response.getError().getCode()
                : "UNAVAILABLE";
        logFailure(errorTag, errorMessage, tenantTag, command.type(), null);
        return new CommunicationCommandHandlingResult(
            CommandEnqueueResult.failure("COMMUNICATION_NOT_DELIVERED", errorMessage), null);
      }

      recipientDeliveryService.deliver(context, response);

      return new CommunicationCommandHandlingResult(
          CommandEnqueueResult.success(), formatSuccessResponse(command, response));
    } catch (RuntimeException ex) {
      logFailure("UNAVAILABLE", "Game Logic unavailable", tenantTag, command.type(), ex);
      return new CommunicationCommandHandlingResult(
          CommandEnqueueResult.failure("COMMUNICATION_NOT_DELIVERED", "Game Logic unavailable"),
          null);
    }
  }

  private ParsedCommunication parseCommunication(SessionContext context, TextCommand command) {
    return switch (command.type()) {
      case SAY -> parseSay(command.args());
      case WHISPER -> parseWhisper(command.args());
      case TELL -> parseTell(context, command.args());
      default ->
          new ParsedCommunication(
              false, null, Optional.empty(), Optional.empty(), "Unsupported communication command");
    };
  }

  private ParsedCommunication parseSay(List<String> args) {
    if (args.isEmpty()) {
      return new ParsedCommunication(
          false, null, Optional.empty(), Optional.empty(), "SAY command requires a message");
    }
    return new ParsedCommunication(true, args.get(0), Optional.empty(), Optional.empty(), null);
  }

  private ParsedCommunication parseWhisper(List<String> args) {
    if (args.size() < 2) {
      return new ParsedCommunication(
          false,
          null,
          Optional.empty(),
          Optional.empty(),
          "WHISPER command requires a target and a message");
    }
    return new ParsedCommunication(
        true, args.get(1), Optional.empty(), Optional.of(args.get(0)), null);
  }

  private ParsedCommunication parseTell(SessionContext context, List<String> args) {
    if (args.size() < 2) {
      return new ParsedCommunication(
          false,
          null,
          Optional.empty(),
          Optional.empty(),
          "TELL command requires a target and a message");
    }
    String targetName = args.get(0);
    Optional<net.firedevops.firemud.entitymanagement.v1.Character> maybeTargetCharacter =
        entityManagementClient.findCharacterByName(Long.toString(context.tenantId()), targetName);
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
        args.get(1),
        Optional.of(targetCharacter.getId()),
        Optional.of(targetCharacter.getName()),
        null);
  }

  private String formatSuccessResponse(TextCommand command, SendCommunicationResponse response) {
    if (StringUtils.hasText(response.getActorView())) {
      return response.getActorView();
    }
    return switch (command.type()) {
      case SAY -> "You say, \"" + command.args().get(0) + "\"";
      case WHISPER ->
          "You whisper to " + command.args().get(0) + ", \"" + command.args().get(1) + "\"";
      case TELL -> "You tell " + command.args().get(0) + ", \"" + command.args().get(1) + "\"";
      default -> "Message sent.";
    };
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
      String tenantTag,
      TextCommandType commandType,
      RuntimeException ex) {
    String typeTag = commandType.name().toLowerCase(Locale.ROOT);
    meterRegistry
        .counter(
            COMMUNICATION_FAILURES_METRIC,
            "tenantId",
            tenantTag,
            "type",
            typeTag,
            "error",
            errorTag)
        .increment();
    meterRegistry
        .counter(metricPrefix(commandType) + ".failures", "tenantId", tenantTag, "error", errorTag)
        .increment();
    if (ex == null) {
      LOG.warn(
          "Communication failed tenantId={} type={} error={} reason={}",
          tenantTag,
          typeTag,
          errorTag,
          reason);
    } else {
      LOG.warn(
          "Communication failed tenantId={} type={} error={} reason={}",
          tenantTag,
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

  private record ParsedCommunication(
      boolean valid,
      String message,
      Optional<String> targetCharacterId,
      Optional<String> targetCharacterName,
      String errorMessage) {}
}
