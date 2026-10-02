package net.firedevops.firemud.worldmanagement.client;

import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindOwnerProof;
import net.firedevops.firemud.worldmanagement.entity.InitialAdmissionBindHold;

/** Authenticated Game Session owner-read boundary; no implementation is wired in this slice. */
public interface GameSessionInitialAdmissionBindProofClient {
  InitialAdmissionBindOwnerProof readOwnerProof(InitialAdmissionBindHold hold);
}
