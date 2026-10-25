package net.firedevops.firemud.worldmanagement.client;

import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;

/** Authenticated read-only boundary for Game Session's canonical initial-admission outcome. */
public interface GameSessionCanonicalInitialAdmissionOwnerClient {
  /** Reads the exact owner observation bound to the supplied World-issued hold identity. */
  GameSessionCanonicalInitialAdmissionOwnerProof read(HoldIdentity identity);
}
