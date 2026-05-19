package net.firedevops.firemud.gamesession.service.impl;

record TickQueuedCommandEnvelope(boolean requiresSoloTick, String commandId, String command) {}
