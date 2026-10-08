package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;

/** Game Session-owned canonical initial OPEN operation and exact owner readback. */
public interface CanonicalInitialAdmissionService {
  CanonicalInitialAdmissionOwnerProof bind(CanonicalInitialAdmissionRequest request);

  CanonicalInitialAdmissionOwnerProof abort(
      CanonicalInitialAdmissionRequest request, String reason);

  CanonicalInitialAdmissionOwnerProof read(String targetNamespace, String requestId);
}
