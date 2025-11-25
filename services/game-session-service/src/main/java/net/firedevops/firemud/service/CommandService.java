package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.CommandEnqueueResult;

/** Handles player command enqueue requests across transports. */
public interface CommandService {
  CommandEnqueueResult enqueue(String sessionId, String command, boolean requiresSoloTick);
}
