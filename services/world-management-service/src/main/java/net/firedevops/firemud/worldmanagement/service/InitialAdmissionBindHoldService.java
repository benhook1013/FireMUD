package net.firedevops.firemud.worldmanagement.service;

import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindHoldDto;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindHoldRequest;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindOwnerProof;

public interface InitialAdmissionBindHoldService {
  InitialAdmissionBindHoldDto acquire(InitialAdmissionBindHoldRequest request);

  InitialAdmissionBindHoldDto reconcileOwnerProof(
      String holdId, InitialAdmissionBindOwnerProof ownerProof);

  InitialAdmissionBindHoldDto requireReconciliation(String holdId, String errorCode);
}
