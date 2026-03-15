package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;

/** Handles player command enqueue requests across transports. */
public interface CommandService {
  CommandEnqueueResult enqueue(String sessionId, String command, boolean requiresSoloTick);
}
