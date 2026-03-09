package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Objects;

/** Parsed representation of a single text command line. */
public record TextCommand(TextCommandType type, List<String> args, String rawLine) {
  public TextCommand {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(args, "args must not be null");
    Objects.requireNonNull(rawLine, "rawLine must not be null");
    args = List.copyOf(args);
  }
}
