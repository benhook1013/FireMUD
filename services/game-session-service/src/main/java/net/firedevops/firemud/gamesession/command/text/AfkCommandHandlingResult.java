package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;

record AfkCommandHandlingResult(CommandEnqueueResult commandResult, List<PlayerOutput> outputs) {}
