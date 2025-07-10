package net.firedevops.firemud.logic.service;

import net.firedevops.firemud.logic.dto.CommandResult;

public interface CommandService {
  CommandResult handleCommand(String commandText);
}
