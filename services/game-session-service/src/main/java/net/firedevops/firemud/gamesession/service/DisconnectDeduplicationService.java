package net.firedevops.firemud.gamesession.service;

/** Deduplicates disconnect hints so late or repeated bridge notices can be ignored safely. */
public interface DisconnectDeduplicationService {
  boolean shouldProcess(String proxyConnectionId, long disconnectSequence);
}
