package net.firedevops.firemud.gamesession.command.text;

import java.util.Arrays;
import java.util.List;

/** Parses player-provided text lines into {@link TextCommand} objects. */
public class TextCommandParser {
  public TextCommand parse(String rawLine) {
    String source = rawLine == null ? "" : rawLine;
    String trimmed = source.trim();
    if (trimmed.isEmpty()) {
      return new TextCommand(TextCommandType.NOOP, List.of(), source);
    }

    String[] tokens = trimmed.split("\\s+");
    TextCommandType type = TextCommandType.fromToken(tokens[0]);
    List<String> args =
        switch (type) {
          case WORLDS, LOOK, NOOP -> List.of();
          case LOGIN -> parseRemainingTokens(tokens);
          case PLAY -> parseRemainingTokens(tokens);
          case SAY -> extractSayMessage(trimmed);
          case MOVE -> extractMoveArguments(tokens);
          case UNKNOWN -> parseRemainingTokens(tokens);
        };
    return new TextCommand(type, args, source);
  }

  private List<String> parseRemainingTokens(String[] tokens) {
    if (tokens.length <= 1) {
      return List.of();
    }
    return List.of(Arrays.copyOfRange(tokens, 1, tokens.length));
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

  private List<String> extractMoveArguments(String[] tokens) {
    if (tokens.length == 0) {
      return List.of();
    }
    String verb = tokens[0];
    if (tokens.length == 1) {
      return switch (verb.trim().toUpperCase()) {
        case "NORTH", "SOUTH", "EAST", "WEST" -> List.of(verb.trim().toLowerCase());
        default -> List.of();
      };
    }
    return List.of(Arrays.copyOfRange(tokens, 1, tokens.length));
  }
}
