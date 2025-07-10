package net.firedevops.firemud.logic.event;

import net.firedevops.firemud.logic.command.Command;

/** Event dispatched after a command is processed. */
public record GameEvent(GameEventType type, Command command, String result) {}
