package net.firedevops.firemud.gamelogic.logic.command;

/** Parses raw player input into a command structure. */
public interface CommandParser {
  Command parse(String input);
}
