package net.firedevops.firemud.gamesession.command.text;

import java.util.Arrays;
import java.util.List;

/** Parses player-provided text lines into {@link TextCommand} objects. */
public class TextCommandParser {
  public TextCommand parse(String rawLine) {
    String source = rawLine == null ? "" : rawLine;
    String trimmed = source.trim();
    if (trimmed.isEmpty()) {
      return new TextCommand(
          TextCommandType.NOOP, List.of(), source, "", new TextCommandPayload.None());
    }

    String[] tokens = trimmed.split("\\s+");
    String aliasUsed = tokens[0];
    TextCommandType type = TextCommandType.fromToken(aliasUsed);
    ParsedCommandData parsed =
        switch (type) {
          case WORLDS ->
              new ParsedCommandData(List.of(), new TextCommandPayload.ViewRequest("WORLDS"));
          case HELP -> parseHelp(tokens);
          case INVENTORY ->
              new ParsedCommandData(List.of(), new TextCommandPayload.ViewRequest("INVENTORY"));
          case GET, DROP, WEAR, REMOVE -> parseItemReference(type, tokens);
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
    return new TextCommand(type, parsed.args(), source, aliasUsed, parsed.payload());
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
    if (!args.isEmpty()) {
      return new ParsedCommandData(
          args,
          new TextCommandPayload.Selection(args.get(0), args.size() > 1 ? args.get(1) : null));
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

  private ParsedCommandData parseItemReference(TextCommandType type, String[] tokens) {
    List<String> args = parseRemainingTokens(tokens);
    if (args.isEmpty()) {
      return new ParsedCommandData(args, new TextCommandPayload.Tokens(args));
    }
    return new ParsedCommandData(args, TextCommandPayload.fromLegacy(type, args));
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
    return List.of(Arrays.copyOfRange(tokens, 1, tokens.length));
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
