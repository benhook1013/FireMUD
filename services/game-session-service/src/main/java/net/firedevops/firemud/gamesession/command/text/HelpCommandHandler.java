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
      case "INVENTORY" ->
          success(
              "INVENTORY shows what you are carrying.\n"
                  + "If nothing is listed, you are empty-handed.\n"
                  + "GET <item> picks up a matching room-ground item and refreshes your inventory.\n"
                  + "GET <count> <item> picks up that many matching room-ground items.\n"
                  + "DROP <item> places a carried item on the room ground and refreshes your inventory.\n"
                  + "DROP <count> <item> drops that many carried items.");
      case "EQUIPMENT" ->
          success(
              "EQUIPMENT shows what you are currently wearing.\n"
                  + "EQ is a short alias for EQUIPMENT.\n"
                  + "WEAR <item> equips a carried item.\n"
                  + "REMOVE <item|slot> takes an equipped item off.\n"
                  + "LOOK may show wearable:<slot> tags for items that can be worn.");
      case "CONTAINER" ->
          success(
              "CONTAINER <container>\n"
                  + "Inspect a carried container's contents.\n"
                  + "LOOK may show which items are containers.\n"
                  + "See HELP PUT and HELP TAKE for transfer syntax.");
      case "PUT" ->
          success(
              "PUT <item> INTO <container>\n"
                  + "Move a carried item into a carried container.\n"
                  + "PUT <count> <item> INTO <container> moves that many items.");
      case "TAKE" ->
          success(
              "TAKE <item> FROM <container>\n"
                  + "Move an item out of a carried container.\n"
                  + "TAKE <count> <item> FROM <container> moves that many items.");
      case "WEAR" ->
          success(
              "WEAR <item>\n"
                  + "Equip a carried item that has an equipment slot.\n"
                  + "See HELP EQUIPMENT for the broader command surface.");
      case "REMOVE" ->
          success(
              "REMOVE <item|slot>\n"
                  + "Take an equipped item off by name or slot.\n"
                  + "See HELP EQUIPMENT for the broader command surface.");
      case "GET" ->
          success(
              "GET <item>\n"
                  + "Pick up a matching room-ground item and refresh your inventory.\n"
                  + "GET <count> <item> picks up that many matching room-ground items.");
      case "DROP" ->
          success(
              "DROP <item>\n"
                  + "Place a carried item on the room ground and refresh your inventory.\n"
                  + "DROP <count> <item> drops that many carried items.");
      case "MOVEMENT" ->
          success(
              "Movement commands: NORTH, SOUTH, EAST, WEST, UP, DOWN\n"
                  + "Shorthand aliases: N, S, E, W, U, D\n"
                  + "You can also type GO <direction>.");
      case "LOOK" ->
          success(
              "LOOK refreshes the current room.\n"
                  + "When available, LOOK shows lightweight item affordances like container and wearable tags.\n"
                  + "QUICKLOOK is the shorter room refresh.");
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
      case "INVENTORY", "INV", "I" -> "INVENTORY";
      case "EQUIPMENT", "EQUIP", "EQ", "WEAR", "REMOVE" -> "EQUIPMENT";
      case "CONTAINER", "CONT" -> "CONTAINER";
      case "PUT" -> "PUT";
      case "TAKE" -> "TAKE";
      case "GET" -> "GET";
      case "DROP" -> "DROP";
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
        + "- HELP INVENTORY\n"
        + "- HELP EQUIPMENT\n"
        + "- HELP CONTAINER\n"
        + "- HELP PUT\n"
        + "- HELP TAKE\n"
        + "- HELP WEAR\n"
        + "- HELP REMOVE\n"
        + "- HELP GET\n"
        + "- HELP DROP\n"
        + "- HELP MOVEMENT\n"
        + "- HELP LOOK\n"
        + "- HELP SAY\n"
        + "- HELP WHISPER\n"
        + "- HELP TELL\n"
        + "Type HELP <TOPIC> to read one of them.";
  }
}
