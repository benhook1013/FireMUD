package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;

/**
 * Remote World owner boundary. Implementations must verify the current ACTIVE lifecycle evidence
 * and exact immutable {@code WorldCanonicalInitialAdmissionHold.HoldIdentity} outside SQL, then
 * return its complete request/hold tuple and World binding digest. A supplied local request or
 * caller authentication context is never owner proof.
 */
public interface CanonicalInitialAdmissionWorldVerifier {
  CanonicalInitialAdmissionWorldProof verify(CanonicalInitialAdmissionRequest request);
}
