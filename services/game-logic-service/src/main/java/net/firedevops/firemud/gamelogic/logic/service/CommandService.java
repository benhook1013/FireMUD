package net.firedevops.firemud.gamelogic.logic.service;

import net.firedevops.firemud.gamelogic.logic.dto.CommandResult;

public interface CommandService {
  CommandResult handleCommand(String commandText);
}
