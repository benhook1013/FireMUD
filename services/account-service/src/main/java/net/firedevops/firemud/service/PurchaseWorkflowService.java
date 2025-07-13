package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.PaymentIntentDto;
import net.firedevops.firemud.dto.PurchaseRequest;

/** Saga-based workflow for processing purchases. */
public interface PurchaseWorkflowService {
  PaymentIntentDto processPurchase(PurchaseRequest request);
}
