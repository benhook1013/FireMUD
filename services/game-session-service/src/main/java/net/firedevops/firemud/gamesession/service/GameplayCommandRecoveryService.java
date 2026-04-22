package net.firedevops.firemud.gamesession.service;

import java.time.Instant;

public interface GameplayCommandRecoveryService {
  int convergeAcceptedButUnstagedCommands(Instant acceptedBefore);
}
