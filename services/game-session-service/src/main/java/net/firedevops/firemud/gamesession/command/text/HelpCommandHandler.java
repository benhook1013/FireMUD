package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class HelpCommandHandler {
  @Timed(value = "gamesession.command.help")
  public TextCommandInterpretationResult handle(TextCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("command must not be null");
    }

    List<String> args = command.args();
    if (args.isEmpty()) {
      return success(topicIndex());
    }
    if (args.size() > 1) {
      return unknownTopic(args.get(0));
    }

    return switch (canonicalTopic(args.get(0))) {
      case "HELP" -> success(topicIndex());
      case "LOGIN" ->
          success(
              "LOGIN <email> <password> [otp]\n"
                  + "Use LOGON as an alias if your client expects it.\n"
                  + "After login, use PLAY <world> [character].");
      case "PLAY" ->
          success(
              "PLAY <world> [character]\n"
                  + "Select the world to enter and optional character name.");
      case "MOVEMENT" ->
          success(
              "Movement commands: NORTH, SOUTH, EAST, WEST, UP, DOWN\n"
                  + "Shorthand aliases: N, S, E, W, U, D\n"
                  + "You can also type GO <direction>.");
      case "LOOK" ->
          success("LOOK refreshes the current room.\nQUICKLOOK is the shorter room refresh.");
      case "SAY" -> success("SAY <message>\nSpeak to everyone in the room.");
      case "WHISPER" ->
          success("WHISPER <target> <message>\nSpeak privately to one nearby character.");
      case "TELL" ->
          success("TELL <target> <message>\nSend a direct message to an available character.");
      default -> unknownTopic(args.get(0));
    };
  }

  private TextCommandInterpretationResult success(String body) {
    return new TextCommandInterpretationResult(
        net.firedevops.firemud.gamesession.dto.CommandEnqueueResult.success(),
        List.of(net.firedevops.firemud.gamesession.presentation.PlayerOutput.notice(body)));
  }

  private TextCommandInterpretationResult unknownTopic(String topic) {
    String normalized = StringUtils.hasText(topic) ? topic.trim() : "";
    return new TextCommandInterpretationResult(
        net.firedevops.firemud.gamesession.dto.CommandEnqueueResult.failure(
            "HELP_UNKNOWN_TOPIC", "Unknown help topic: " + normalized),
        List.of(
            net.firedevops.firemud.gamesession.presentation.PlayerOutput.error(
                "HELP_UNKNOWN_TOPIC",
                "Unknown help topic: " + normalized,
                "error.help.unknown-topic",
                Map.of("topic", normalized))));
  }

  private String canonicalTopic(String topic) {
    if (!StringUtils.hasText(topic)) {
      return "";
    }
    return switch (topic.trim().toUpperCase(Locale.ROOT)) {
      case "HELP" -> "HELP";
      case "LOGIN", "LOGON" -> "LOGIN";
      case "PLAY" -> "PLAY";
      case "MOVEMENT", "MOVE", "WALK", "GO" -> "MOVEMENT";
      case "LOOK", "QUICKLOOK", "QLOOK" -> "LOOK";
      case "SAY" -> "SAY";
      case "WHISPER" -> "WHISPER";
      case "TELL" -> "TELL";
      default -> "";
    };
  }

  private String topicIndex() {
    return "Help topics:\n"
        + "- HELP LOGIN\n"
        + "- HELP PLAY\n"
        + "- HELP MOVEMENT\n"
        + "- HELP LOOK\n"
        + "- HELP SAY\n"
        + "- HELP WHISPER\n"
        + "- HELP TELL\n"
        + "Type HELP <TOPIC> to read one of them.";
  }
}
