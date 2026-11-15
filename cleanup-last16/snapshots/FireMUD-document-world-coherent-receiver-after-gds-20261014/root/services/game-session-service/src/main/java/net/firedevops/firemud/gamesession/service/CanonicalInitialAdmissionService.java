package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidence;

/** Game Session-owned canonical initial OPEN operation and exact owner readback. */
public interface CanonicalInitialAdmissionService {
  CanonicalInitialAdmissionOwnerProof bind(CanonicalInitialAdmissionRequest request);

  CanonicalInitialAdmissionOwnerProof bindFixture(
      RunOwnedInitialAdmissionLaunchEvidence evidence,
      CanonicalInitialAdmissionRequest request);

  CanonicalInitialAdmissionOwnerProof abort(
      CanonicalInitialAdmissionRequest request, String reason);

  CanonicalInitialAdmissionOwnerProof abortFixture(
      RunOwnedInitialAdmissionLaunchEvidence evidence,
      CanonicalInitialAdmissionRequest request,
      String reason);

  CanonicalInitialAdmissionOwnerProof read(String targetNamespace, String requestId);

  CanonicalInitialAdmissionOwnerProof readFixture(
      RunOwnedInitialAdmissionLaunchEvidence evidence,
      String targetNamespace,
      String requestId);
}
