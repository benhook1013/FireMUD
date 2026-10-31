package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataResponse;

/** Authenticated, read-only Account lookup for its current protected receiver metadata. */
@FunctionalInterface
public interface GameSessionJwtReadinessReceiverMetadataReadPort {
  GetCurrentReadinessReceiverMetadataResponse readCurrent(
      GetCurrentReadinessReceiverMetadataRequest request);
}
