package net.firedevops.firemud.gamelogic.logic.event;

import net.firedevops.firemud.gamelogic.logic.command.Command;

/** Event dispatched after a command is processed. */
public record GameEvent(GameEventType type, Command command, String result) {}
