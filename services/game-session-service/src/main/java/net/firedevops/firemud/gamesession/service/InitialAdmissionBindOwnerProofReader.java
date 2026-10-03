package net.firedevops.firemud.gamesession.service;

/** Read-only, authenticated-adapter-facing port for reconciling a World hold against GS state. */
public interface InitialAdmissionBindOwnerProofReader {
  InitialAdmissionBindOwnerProof read(InitialAdmissionBindHoldBinding binding);
}
