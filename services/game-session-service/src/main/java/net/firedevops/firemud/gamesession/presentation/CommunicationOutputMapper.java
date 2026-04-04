package net.firedevops.firemud.gamesession.presentation;

import java.util.Map;
import net.firedevops.firemud.gamelogic.v1.CommunicationPerception;
import net.firedevops.firemud.gamelogic.v1.CommunicationRecipientRole;
import net.firedevops.firemud.gamelogic.v1.CommunicationRecipientView;
import net.firedevops.firemud.gamelogic.v1.CommunicationType;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationResponse;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Maps structured communication metadata into renderer-owned player outputs. */
@Component
public class CommunicationOutputMapper {

  public PlayerOutput actorOutput(TextCommand command, SendCommunicationResponse response) {
    CommunicationType type = response.getType();
    String message = response.getMessage();
    String speakerName = fallbackSpeakerName(response.getSpeakerName(), command);
    String targetName = actorTargetName(command);
    return switch (type) {
      case SAY ->
          PlayerOutput.message(
              "You say, \"" + message + "\"",
              "communication.say.actor",
              Map.of("message", message));
      case WHISPER ->
          PlayerOutput.message(
              "You whisper to " + targetName + ", \"" + message + "\"",
              "communication.whisper.actor",
              Map.of("targetName", targetName, "message", message));
      case TELL ->
          PlayerOutput.message(
              "You tell " + targetName + ", \"" + message + "\"",
              "communication.tell.actor",
              Map.of("targetName", targetName, "message", message));
      default -> PlayerOutput.message(fallbackActorText(command, message, speakerName, targetName));
    };
  }

  public PlayerOutput recipientOutput(
      CommunicationType type, CommunicationRecipientView view, String message) {
    String speakerName =
        StringUtils.hasText(view.getSpeakerName()) ? view.getSpeakerName() : "Unknown";
    String targetName = view.getTargetName();
    if (type == CommunicationType.WHISPER
        && view.getRole() == CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_TARGET) {
      return PlayerOutput.message(
          speakerName + " whispers to you, \"" + message + "\"",
          "communication.whisper.target",
          Map.of("speakerName", speakerName, "message", message));
    }
    if (type == CommunicationType.WHISPER
        && view.getRole() == CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_OBSERVER
        && view.getPerception() == CommunicationPerception.COMMUNICATION_PERCEPTION_METADATA_ONLY) {
      return PlayerOutput.message(
          speakerName + " whispers something to " + targetName + ".",
          "communication.whisper.observer-metadata",
          Map.of("speakerName", speakerName, "targetName", targetName));
    }
    if (type == CommunicationType.TELL
        && view.getRole() == CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_TARGET) {
      return PlayerOutput.message(
          speakerName + " tells you, \"" + message + "\"",
          "communication.tell.target",
          Map.of("speakerName", speakerName, "message", message));
    }
    return PlayerOutput.message(
        fallbackRecipientText(type, view, message, speakerName, targetName));
  }

  private String actorTargetName(TextCommand command) {
    return command
        .targetedMessagePayload()
        .map(targeted -> targeted.target())
        .filter(StringUtils::hasText)
        .orElse("target");
  }

  private String fallbackSpeakerName(String speakerName, TextCommand command) {
    if (StringUtils.hasText(speakerName)) {
      return speakerName;
    }
    return command.aliasUsed();
  }

  private String fallbackActorText(
      TextCommand command, String message, String speakerName, String targetName) {
    return switch (command.type()) {
      case SAY -> "You say, \"" + message + "\"";
      case WHISPER -> "You whisper to " + targetName + ", \"" + message + "\"";
      case TELL -> "You tell " + targetName + ", \"" + message + "\"";
      default -> speakerName + ": " + message;
    };
  }

  private String fallbackRecipientText(
      CommunicationType type,
      CommunicationRecipientView view,
      String message,
      String speakerName,
      String targetName) {
    return switch (type) {
      case WHISPER ->
          view.getRole() == CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_TARGET
              ? speakerName + " whispers to you, \"" + message + "\""
              : speakerName + " whispers something to " + targetName + ".";
      case TELL -> speakerName + " tells you, \"" + message + "\"";
      default -> speakerName + ": " + message;
    };
  }
}
