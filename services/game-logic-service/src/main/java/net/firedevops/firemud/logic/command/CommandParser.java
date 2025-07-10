package net.firedevops.firemud.logic.command;

/** Parses raw player input into a command structure. */
public interface CommandParser {
  Command parse(String input);
}
