package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Parses player-provided text lines into {@link TextCommand} objects. */
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Constructor validation only guards injected collaborators before the parser is used.")
@Component
public class TextCommandParser {
  private final TextCommandRegistry registry;

  @Autowired
  public TextCommandParser(TextCommandRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
  }

  TextCommandParser() {
    this(new AggregatingTextCommandRegistry(List.of(new BuiltInTextCommandDefinitionProvider())));
  }

  public TextCommand parse(String rawLine) {
    String source = rawLine == null ? "" : rawLine;
    String trimmed = source.trim();
    if (trimmed.isEmpty()) {
      return new TextCommand(
          TextCommandType.NOOP.name().toLowerCase(java.util.Locale.ROOT),
          TextCommandType.NOOP,
          List.of(),
          source,
          "",
          new TextCommandPayload.None());
    }

    String[] tokens = trimmed.split("\\s+");
    String aliasUsed = tokens[0];
    TextCommandDefinition resolvedDefinition =
        registry.findDefinitionByAlias(aliasUsed).orElse(null);
    TextCommandType type =
        resolvedDefinition == null ? TextCommandType.UNKNOWN : resolvedDefinition.type();
    String commandId =
        resolvedDefinition == null
            ? TextCommandType.fromToken(aliasUsed).name().toLowerCase(java.util.Locale.ROOT)
            : resolvedDefinition.commandId();
    ParsedCommandData parsed =
        switch (type) {
          case WORLDS ->
              new ParsedCommandData(List.of(), new TextCommandPayload.ViewRequest("WORLDS"));
          case REALMS -> parseRealms(tokens);
          case CHARS -> parseChars(tokens);
          case LOGOUT -> new ParsedCommandData(List.of(), new TextCommandPayload.None());
          case AFK -> parseAfk(tokens);
          case BLOCK ->
              new ParsedCommandData(parseRemainingTokens(tokens), new TextCommandPayload.None());
          case HELP -> parseHelp(tokens);
          case WHO -> new ParsedCommandData(List.of(), new TextCommandPayload.ViewRequest("WHO"));
          case FRIENDS ->
              new ParsedCommandData(
                  parseRemainingTokens(tokens), new TextCommandPayload.ViewRequest("FRIENDS"));
          case AUTHORED ->
              new ParsedCommandData(
                  parseRemainingTokens(tokens),
                  new TextCommandPayload.AuthoredActionInvocation(
                      commandId, parseRemainingTokens(tokens)));
          case INVENTORY ->
              new ParsedCommandData(
                  parseRemainingTokens(tokens), new TextCommandPayload.ViewRequest("INVENTORY"));
          case EQUIPMENT ->
              new ParsedCommandData(
                  parseRemainingTokens(tokens), new TextCommandPayload.ViewRequest("EQUIPMENT"));
          case CONTAINER -> parseContainerView(tokens);
          case GET, DROP, WEAR, REMOVE -> parseItemReference(type, tokens);
          case PUT, TAKE -> parseContainerTransfer(type, tokens);
          case LOOK -> new ParsedCommandData(List.of(), new TextCommandPayload.ViewRequest("LOOK"));
          case QUICKLOOK ->
              new ParsedCommandData(List.of(), new TextCommandPayload.ViewRequest("QUICKLOOK"));
          case NOOP -> new ParsedCommandData(List.of(), new TextCommandPayload.None());
          case LOGIN -> parseLogin(tokens);
          case PLAY -> parsePlay(tokens);
          case SAY -> parseSay(trimmed);
          case WHISPER, TELL -> parseTargetedCommunication(trimmed);
          case MOVE -> parseMove(aliasUsed, tokens);
          case UNKNOWN -> parseUnknown(tokens);
        };
    return new TextCommand(commandId, type, parsed.args(), source, aliasUsed, parsed.payload());
  }

  private ParsedCommandData parseLogin(String[] tokens) {
    List<String> args = parseRemainingTokens(tokens);
    if (args.size() >= 2) {
      return new ParsedCommandData(
          args,
          new TextCommandPayload.Credentials(
              args.get(0), args.get(1), args.size() > 2 ? args.get(2) : ""));
    }
    return new ParsedCommandData(args, new TextCommandPayload.Tokens(args));
  }

  private ParsedCommandData parsePlay(String[] tokens) {
    List<String> args = parseRemainingTokens(tokens);
    if (!args.isEmpty()
        && TextCommandPayload.fromLegacy(TextCommandType.PLAY, args)
            instanceof TextCommandPayload.PlayRequest playRequest) {
      return new ParsedCommandData(args, playRequest);
    }
    return new ParsedCommandData(args, new TextCommandPayload.Tokens(args));
  }

  private ParsedCommandData parseRealms(String[] tokens) {
    List<String> args = parseRemainingTokens(tokens);
    if (!args.isEmpty()
        && TextCommandPayload.fromLegacy(TextCommandType.REALMS, args)
            instanceof TextCommandPayload.RealmBrowseRequest browseRequest) {
      return new ParsedCommandData(args, browseRequest);
    }
    return new ParsedCommandData(args, new TextCommandPayload.Tokens(args));
  }

  private ParsedCommandData parseChars(String[] tokens) {
    List<String> args = parseRemainingTokens(tokens);
    if (!args.isEmpty()
        && TextCommandPayload.fromLegacy(TextCommandType.CHARS, args)
            instanceof TextCommandPayload.CharacterBrowseRequest browseRequest) {
      return new ParsedCommandData(args, browseRequest);
    }
    return new ParsedCommandData(args, new TextCommandPayload.Tokens(args));
  }

  private ParsedCommandData parseHelp(String[] tokens) {
    List<String> args = parseRemainingTokens(tokens);
    if (args.isEmpty()) {
      return new ParsedCommandData(args, new TextCommandPayload.None());
    }
    return new ParsedCommandData(args, new TextCommandPayload.Tokens(args));
  }

  private ParsedCommandData parseAfk(String[] tokens) {
    List<String> args = parseRemainingTokens(tokens);
    if (args.isEmpty()) {
      return new ParsedCommandData(args, new TextCommandPayload.AfkRequest(true));
    }
    if (args.size() == 1) {
      String mode = args.get(0).trim();
      if (mode.equalsIgnoreCase("ON")) {
        return new ParsedCommandData(args, new TextCommandPayload.AfkRequest(true));
      }
      if (mode.equalsIgnoreCase("OFF")) {
        return new ParsedCommandData(args, new TextCommandPayload.AfkRequest(false));
      }
    }
    return new ParsedCommandData(args, new TextCommandPayload.Tokens(args));
  }

  private ParsedCommandData parseItemReference(TextCommandType type, String[] tokens) {
    List<String> args = parseRemainingTokens(tokens);
    if (args.isEmpty()) {
      return new ParsedCommandData(args, new TextCommandPayload.Tokens(args));
    }
    return new ParsedCommandData(args, TextCommandPayload.fromLegacy(type, args));
  }

  private ParsedCommandData parseContainerTransfer(TextCommandType type, String[] tokens) {
    List<String> args = parseRemainingTokens(tokens);
    if (args.isEmpty()) {
      return new ParsedCommandData(args, new TextCommandPayload.Tokens(args));
    }
    return new ParsedCommandData(args, TextCommandPayload.fromLegacy(type, args));
  }

  private ParsedCommandData parseContainerView(String[] tokens) {
    List<String> args = parseRemainingTokens(tokens);
    if (args.isEmpty()) {
      return new ParsedCommandData(args, new TextCommandPayload.Tokens(args));
    }
    return new ParsedCommandData(
        args, TextCommandPayload.fromLegacy(TextCommandType.CONTAINER, args));
  }

  private List<String> parseRemainingTokens(String[] tokens) {
    if (tokens.length <= 1) {
      return List.of();
    }
    return List.of(Arrays.copyOfRange(tokens, 1, tokens.length));
  }

  private ParsedCommandData parseSay(String trimmed) {
    List<String> args = extractSayMessage(trimmed);
    if (!args.isEmpty()) {
      return new ParsedCommandData(args, new TextCommandPayload.Message(args.get(0)));
    }
    return new ParsedCommandData(args, new TextCommandPayload.Tokens(args));
  }

  private List<String> extractSayMessage(String trimmed) {
    int firstSpace = trimmed.indexOf(' ');
    if (firstSpace < 0 || firstSpace == trimmed.length() - 1) {
      return List.of();
    }
    String message = trimmed.substring(firstSpace + 1).trim();
    if (message.isEmpty()) {
      return List.of();
    }
    return List.of(message);
  }

  private ParsedCommandData parseTargetedCommunication(String trimmed) {
    List<String> args = extractTargetedCommunicationArguments(trimmed);
    if (args.size() >= 2) {
      return new ParsedCommandData(
          args, new TextCommandPayload.TargetedMessage(args.get(0), args.get(1)));
    }
    return new ParsedCommandData(args, new TextCommandPayload.Tokens(args));
  }

  private List<String> extractTargetedCommunicationArguments(String trimmed) {
    int firstSpace = trimmed.indexOf(' ');
    if (firstSpace < 0 || firstSpace == trimmed.length() - 1) {
      return List.of();
    }
    String remainder = trimmed.substring(firstSpace + 1).trim();
    if (remainder.isEmpty()) {
      return List.of();
    }
    int secondSpace = remainder.indexOf(' ');
    if (secondSpace < 0 || secondSpace == remainder.length() - 1) {
      return List.of(remainder);
    }
    String target = remainder.substring(0, secondSpace).trim();
    String message = remainder.substring(secondSpace + 1).trim();
    if (target.isEmpty()) {
      return List.of();
    }
    if (message.isEmpty()) {
      return List.of(target);
    }
    return List.of(target, message);
  }

  private ParsedCommandData parseMove(String aliasUsed, String[] tokens) {
    List<String> args = extractMoveArguments(tokens);
    if (!args.isEmpty()) {
      String canonicalDirection = canonicalDirection(args.get(0));
      List<String> canonicalArgs =
          canonicalDirection.isEmpty() ? args : List.of(canonicalDirection);
      return new ParsedCommandData(
          canonicalArgs,
          new TextCommandPayload.Directional(
              canonicalDirection.isEmpty()
                  ? args.get(0).trim().toLowerCase()
                  : canonicalDirection));
    }
    String canonicalAliasDirection = canonicalDirection(aliasUsed);
    if (!canonicalAliasDirection.isEmpty()) {
      return new ParsedCommandData(
          List.of(canonicalAliasDirection),
          new TextCommandPayload.Directional(canonicalAliasDirection));
    }
    return new ParsedCommandData(args, new TextCommandPayload.Tokens(args));
  }

  private List<String> extractMoveArguments(String[] tokens) {
    if (tokens.length == 0) {
      return List.of();
    }
    String verb = tokens[0];
    if (tokens.length == 1) {
      String canonical = canonicalDirection(verb);
      return canonical.isEmpty() ? List.of() : List.of(canonical);
    }
    return normalizeGoSyntax(verb, Arrays.copyOfRange(tokens, 1, tokens.length));
  }

  private List<String> normalizeGoSyntax(String verb, String[] remainingTokens) {
    if (!verb.equalsIgnoreCase("go")) {
      return List.of(remainingTokens);
    }
    if (remainingTokens.length == 0) {
      return List.of();
    }
    String canonicalDirection = canonicalDirection(remainingTokens[0]);
    if (canonicalDirection.isEmpty()) {
      return List.of(remainingTokens);
    }
    ArrayList<String> normalized = new ArrayList<>();
    normalized.add(canonicalDirection);
    if (remainingTokens.length > 1) {
      normalized.addAll(List.of(Arrays.copyOfRange(remainingTokens, 1, remainingTokens.length)));
    }
    return List.copyOf(normalized);
  }

  private String canonicalDirection(String token) {
    if (token == null || token.isBlank()) {
      return "";
    }
    return switch (token.trim().toUpperCase()) {
      case "N", "NORTH" -> "north";
      case "S", "SOUTH" -> "south";
      case "E", "EAST" -> "east";
      case "W", "WEST" -> "west";
      case "U", "UP" -> "up";
      case "D", "DOWN" -> "down";
      default -> "";
    };
  }

  private ParsedCommandData parseUnknown(String[] tokens) {
    List<String> args = parseRemainingTokens(tokens);
    return new ParsedCommandData(args, new TextCommandPayload.Tokens(args));
  }

  private record ParsedCommandData(List<String> args, TextCommandPayload payload) {}
}
