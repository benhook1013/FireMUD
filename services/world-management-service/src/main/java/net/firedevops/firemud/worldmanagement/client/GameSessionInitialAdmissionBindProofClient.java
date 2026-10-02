package net.firedevops.firemud.worldmanagement.client;

import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindOwnerProof;
import net.firedevops.firemud.worldmanagement.entity.InitialAdmissionBindHold;

/** Authenticated Game Session owner-read boundary used by World hold reconciliation. */
public interface GameSessionInitialAdmissionBindProofClient {
  InitialAdmissionBindOwnerProof readOwnerProof(InitialAdmissionBindHold hold);
}
