package net.firedevops.firemud.logic.command;

/** Processes parsed commands and returns outcome text. */
public interface CommandProcessor {
  String process(Command command);
}
