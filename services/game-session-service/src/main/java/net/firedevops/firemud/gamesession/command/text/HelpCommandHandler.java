package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.service.GameAuthoredHelpReader;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Constructor validation only guards injected collaborators before the handler is used.")
@Component
public class HelpCommandHandler {
  private final ConfiguredAuthoredActionCatalog authoredActionCatalog;
  private final GameAuthoredHelpReader authoredHelpReader;
  private final AdmittedTextCommandRegistryResolver admittedRegistryResolver;

  HelpCommandHandler(
      ConfiguredAuthoredActionCatalog authoredActionCatalog,
      GameAuthoredHelpReader authoredHelpReader) {
    this(authoredActionCatalog, authoredHelpReader, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  HelpCommandHandler(
      ConfiguredAuthoredActionCatalog authoredActionCatalog,
      GameAuthoredHelpReader authoredHelpReader,
      AdmittedTextCommandRegistryResolver admittedRegistryResolver) {
    this.authoredActionCatalog =
        Objects.requireNonNull(authoredActionCatalog, "authoredActionCatalog must not be null");
    this.authoredHelpReader =
        Objects.requireNonNull(authoredHelpReader, "authoredHelpReader must not be null");
    this.admittedRegistryResolver = admittedRegistryResolver;
  }

  HelpCommandHandler(ConfiguredAuthoredActionCatalog authoredActionCatalog) {
    this(authoredActionCatalog, (context, topic) -> Optional.empty(), null);
  }

  HelpCommandHandler() {
    this(
        new ConfiguredAuthoredActionCatalog(
            new net.firedevops.firemud.gamesession.config.AuthoredActionProperties()));
  }

  @Timed(value = "gamesession.command.help")
  public TextCommandInterpretationResult handle(TextCommand command) {
    return handle(command, Optional.empty());
  }

  public TextCommandInterpretationResult handle(
      TextCommand command, Optional<SessionContext> maybeContext) {
    if (command == null) {
      throw new IllegalArgumentException("command must not be null");
    }

    List<String> args = command.args();
    if (args.isEmpty()) {
      return success(topicIndex(maybeContext));
    }
    if (args.size() > 1) {
      return unknownTopic(args.get(0));
    }

    Optional<GameAuthoredHelpReader.ResolvedTopic> authoredTopic =
        maybeContext.flatMap(context -> authoredHelpReader.resolve(context, args.get(0)));
    if (authoredTopic.isPresent()) {
      return success(authoredTopic.orElseThrow());
    }

    ResolvedHelpTopic resolvedTopic = canonicalTopic(args.get(0), maybeContext);
    if (resolvedTopic.admittedDefinition() != null) {
      return success(resolvedTopic.admittedDefinition());
    }
    if (resolvedTopic.canonicalTopic().startsWith("AUTHORED:")) {
      return authoredActionCatalog
          .find(resolvedTopic.canonicalTopic().substring("AUTHORED:".length()))
          .map(this::success)
          .orElseGet(() -> unknownTopic(args.get(0)));
    }

    return switch (resolvedTopic.canonicalTopic()) {
      case "HELP" -> success(topicIndex(maybeContext));
      case "LOGIN" ->
          success(
              "LOGIN <email> [secret]\n"
                  + "LOGIN <email>\n"
                  + "Request a one-time email login code when the account allows it.\n"
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
      case "STATUS" ->
          success(
              "STATUS\n"
                  + "Show your evaluated resources and active visible conditions.\n"
                  + "STAT is a short alias.\n"
                  + "You must already be in-world with PLAY.");
      case "FRIENDS" ->
          success(
              "FRIENDS\n"
                  + "List your linked friends with bounded cross-game presence.\n"
                  + "FRIENDS SUMMARY shows canonical linked/online/offline/recent counts.\n"
                  + "FRIENDS ONLINE, FRIENDS OFFLINE, FRIENDS RECENT, FRIENDS PUBLIC, FRIENDS FRIENDS_ONLY, FRIENDS PRIVATE, FRIENDS HIDDEN_STAFF, FRIENDS UNSPECIFIED_VISIBILITY, FRIENDS SHARED, FRIENDS ISOLATED, and FRIENDS UNSPECIFIED_SCOPE filter the same canonical roster without widening WHO.\n"
                  + "FRIENDS SHOW <friendAccountId|characterName|#entryNumber> shows one canonical friend roster entry in detail, including #entryNumber lookups from the rendered roster.\n"
                  + "FRIENDS ADD <friendAccountId|characterName> links another account-scoped friend.\n"
                  + "FRIENDS REMOVE <friendAccountId|characterName|#entryNumber> removes an existing account-scoped friend, including canonical #entryNumber removal.\n"
                  + "FRIENDS VISIBILITY shows your current cross-game friend-presence policy, and FRIENDS VISIBILITY <PUBLIC|FRIENDS_ONLY|PRIVATE> updates it.\n"
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

  private ResolvedHelpTopic canonicalTopic(String topic, Optional<SessionContext> maybeContext) {
    if (!StringUtils.hasText(topic)) {
      return new ResolvedHelpTopic("", null);
    }
    String canonical =
        switch (topic.trim().toUpperCase(Locale.ROOT)) {
          case "HELP" -> "HELP";
          case "LOGIN", "LOGON" -> "LOGIN";
          case "PLAY" -> "PLAY";
          case "WHO" -> "WHO";
          case "STATUS", "STAT" -> "STATUS";
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
      return new ResolvedHelpTopic(canonical, null);
    }
    if (admittedRegistryResolver != null) {
      return maybeContext
          .flatMap(context -> admittedRegistryResolver.resolveDefinition(context, topic))
          .filter(definition -> definition.type() == TextCommandType.AUTHORED)
          .map(
              definition -> new ResolvedHelpTopic("AUTHORED:" + definition.commandId(), definition))
          .orElse(new ResolvedHelpTopic("", null));
    }
    return authoredActionCatalog
        .findByAlias(topic)
        .map(action -> new ResolvedHelpTopic("AUTHORED:" + action.commandId(), null))
        .orElse(new ResolvedHelpTopic("", null));
  }

  private record ResolvedHelpTopic(
      String canonicalTopic, TextCommandDefinition admittedDefinition) {}

  private String topicIndex(Optional<SessionContext> maybeContext) {
    ArrayList<String> lines =
        new ArrayList<>(
            List.of(
                "Help topics:",
                "- HELP LOGIN",
                "- HELP PLAY",
                "- HELP WHO",
                "- HELP STATUS",
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
    List<TextCommandDefinition> admittedDefinitions =
        admittedRegistryResolver == null
            ? List.of()
            : maybeContext.map(admittedRegistryResolver::authoredDefinitions).orElse(List.of());
    if (!admittedDefinitions.isEmpty()) {
      lines.add("Authored topics:");
      for (TextCommandDefinition definition : admittedDefinitions) {
        String topic =
            definition.aliases().isEmpty()
                ? definition.commandId()
                : definition.aliases().getFirst();
        lines.add("- HELP " + topic.toUpperCase(Locale.ROOT));
      }
    } else if (admittedRegistryResolver == null && !authoredActionCatalog.all().isEmpty()) {
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

  private TextCommandInterpretationResult success(TextCommandDefinition definition) {
    String topic =
        definition.aliases().isEmpty()
            ? definition.commandId().toUpperCase(Locale.ROOT)
            : definition.aliases().getFirst().toUpperCase(Locale.ROOT);
    return success(topic + "\nGame-authored action.");
  }

  private TextCommandInterpretationResult success(GameAuthoredHelpReader.ResolvedTopic topic) {
    return success(topic.title().trim() + "\n" + topic.body().trim());
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
