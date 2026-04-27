package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.annotation.Timed;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class HelpCommandHandler {
  private final ConfiguredAuthoredActionCatalog authoredActionCatalog;

  HelpCommandHandler(ConfiguredAuthoredActionCatalog authoredActionCatalog) {
    this.authoredActionCatalog =
        Objects.requireNonNull(authoredActionCatalog, "authoredActionCatalog must not be null");
  }

  HelpCommandHandler() {
    this(
        new ConfiguredAuthoredActionCatalog(
            new net.firedevops.firemud.gamesession.config.AuthoredActionProperties()));
  }

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

    String resolvedTopic = canonicalTopic(args.get(0));
    if (resolvedTopic.startsWith("AUTHORED:")) {
      return authoredActionCatalog
          .find(resolvedTopic.substring("AUTHORED:".length()))
          .map(this::success)
          .orElseGet(() -> unknownTopic(args.get(0)));
    }

    return switch (resolvedTopic) {
      case "HELP" -> success(topicIndex());
      case "LOGIN" ->
          success(
              "LOGIN <email> <password> [otp]\n"
                  + "Use LOGON as an alias if your client expects it.\n"
                  + "After login, use PLAY <world> [realm] [character].");
      case "PLAY" ->
          success(
              "PLAY <world> [realm] [character]\n"
                  + "Select the world to enter, optionally name a visible realm, and optionally choose a character.");
      case "REALMS" ->
          success(
              "REALMS <world>\n"
                  + "List visible realms for the selected world.\n"
                  + "Use this before CHARS or PLAY when a world exposes more than one realm.");
      case "CHARS" ->
          success(
              "CHARS <world> [realm]\n"
                  + "List visible characters for the selected world and realm.\n"
                  + "Use REALMS first when the world exposes more than one realm.");
      case "WHO" ->
          success(
              "WHO\n"
                  + "List currently connected players in this game instance.\n"
                  + "Gods appear first, then players.\n"
                  + "You must already be in-world with PLAY.");
      case "FRIENDS" ->
          success(
              "FRIENDS\n"
                  + "List your linked friends with bounded cross-game presence.\n"
                  + "Visible entries show current world/realm labels, character name, and activity state when policy allows.\n"
                  + "Private or hidden-staff friends stay conservative.");
      case "INVENTORY" ->
          success(
              "INVENTORY shows what you are carrying.\n"
                  + "INV HERE lists room-ground items in the current room with management refs.\n"
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
                  + "Inspect a carried or nearby room-ground container's contents.\n"
                  + "LOOK may show which items are containers.\n"
                  + "See HELP PUT and HELP TAKE for transfer syntax.");
      case "PUT" ->
          success(
              "PUT <item> INTO <container>\n"
                  + "Move a carried item into a carried or nearby room-ground container.\n"
                  + "PUT <count> <item> INTO <container> moves that many items.");
      case "TAKE" ->
          success(
              "TAKE <item> FROM <container>\n"
                  + "Move an item out of a carried or nearby room-ground container.\n"
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
      case "BLOCK" ->
          success(
              "BLOCK\n"
                  + "Brace briefly and apply the blocking action state for the next defensive window.");
      case "MOVEMENT" ->
          success(
              "Movement commands: NORTH, SOUTH, EAST, WEST, UP, DOWN\n"
                  + "Shorthand aliases: N, S, E, W, U, D\n"
                  + "You can also type GO <direction>.");
      case "LOOK" ->
          success(
              "LOOK refreshes the current room.\n"
                  + "L is a short alias for LOOK.\n"
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
    String canonical =
        switch (topic.trim().toUpperCase(Locale.ROOT)) {
          case "HELP" -> "HELP";
          case "LOGIN", "LOGON" -> "LOGIN";
          case "PLAY" -> "PLAY";
          case "WHO" -> "WHO";
          case "FRIENDS" -> "FRIENDS";
          case "INVENTORY", "INV", "I" -> "INVENTORY";
          case "EQUIPMENT", "EQUIP", "EQ" -> "EQUIPMENT";
          case "WEAR" -> "WEAR";
          case "REMOVE" -> "REMOVE";
          case "CONTAINER", "CONT" -> "CONTAINER";
          case "PUT" -> "PUT";
          case "TAKE" -> "TAKE";
          case "GET" -> "GET";
          case "DROP" -> "DROP";
          case "BLOCK", "GUARD" -> "BLOCK";
          case "MOVEMENT", "MOVE", "WALK", "GO" -> "MOVEMENT";
          case "LOOK", "QUICKLOOK", "QLOOK" -> "LOOK";
          case "SAY" -> "SAY";
          case "WHISPER" -> "WHISPER";
          case "TELL" -> "TELL";
          default -> "";
        };
    if (!canonical.isEmpty()) {
      return canonical;
    }
    return authoredActionCatalog
        .findByAlias(topic)
        .map(action -> "AUTHORED:" + action.commandId())
        .orElse("");
  }

  private String topicIndex() {
    ArrayList<String> lines =
        new ArrayList<>(
            List.of(
                "Help topics:",
                "- HELP LOGIN",
                "- HELP PLAY",
                "- HELP WHO",
                "- HELP FRIENDS",
                "- HELP INVENTORY",
                "- HELP EQUIPMENT",
                "- HELP CONTAINER",
                "- HELP PUT",
                "- HELP TAKE",
                "- HELP WEAR",
                "- HELP REMOVE",
                "- HELP GET",
                "- HELP DROP",
                "- HELP BLOCK",
                "- HELP MOVEMENT",
                "- HELP LOOK",
                "- HELP SAY",
                "- HELP WHISPER",
                "- HELP TELL"));
    if (!authoredActionCatalog.all().isEmpty()) {
      lines.add("Authored topics:");
      for (ConfiguredAuthoredActionCatalog.ConfiguredAuthoredAction action :
          authoredActionCatalog.all()) {
        lines.add("- HELP " + action.primaryHelpTopic().toUpperCase(Locale.ROOT));
      }
    }
    lines.add("Type HELP <TOPIC> to read one of them.");
    return String.join("\n", lines);
  }

  private TextCommandInterpretationResult success(
      ConfiguredAuthoredActionCatalog.ConfiguredAuthoredAction action) {
    String topic = action.primaryHelpTopic().toUpperCase(Locale.ROOT);
    String body =
        topic
            + "\n"
            + firstNonBlank(action.helpSummary(), "Game-authored action.")
            + (StringUtils.hasText(action.helpDetails()) ? "\n" + action.helpDetails().trim() : "")
            + authoredAliasSuffix(action);
    return success(body);
  }

  private String firstNonBlank(String first, String fallback) {
    return StringUtils.hasText(first) ? first.trim() : fallback;
  }

  private String authoredAliasSuffix(
      ConfiguredAuthoredActionCatalog.ConfiguredAuthoredAction action) {
    if (action.aliases().size() <= 1) {
      return "";
    }
    List<String> alternateAliases =
        action.aliases().stream().skip(1).map(alias -> alias.toUpperCase(Locale.ROOT)).toList();
    return "\nAliases: " + String.join(", ", alternateAliases);
  }
}
