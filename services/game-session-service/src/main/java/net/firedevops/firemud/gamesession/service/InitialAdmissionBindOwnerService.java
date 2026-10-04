package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindCatalog;

/** Internal GS owner operations for durable first-pointer binding and reconciliation. */
public interface InitialAdmissionBindOwnerService extends InitialAdmissionBindOwnerProofReader {
  InitialAdmissionBindCatalog registerPublicSharedFixtureCatalog(
      InitialAdmissionBindCatalogDescriptor descriptor);

  InitialAdmissionBindAttempt beginIntent(InitialAdmissionBindRequest request);

  InitialAdmissionBindAttempt attachHold(InitialAdmissionBindHoldBinding binding);

  InitialAdmissionBindOwnerProof commit(InitialAdmissionBindHoldBinding binding);

  InitialAdmissionBindOwnerProof abort(InitialAdmissionBindHoldBinding binding);
}
