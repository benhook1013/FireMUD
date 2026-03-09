package net.firedevops.firemud.accountservice.service;

import net.firedevops.firemud.accountservice.dto.PaymentIntentDto;
import net.firedevops.firemud.accountservice.dto.PurchaseRequest;

/** Saga-based workflow for processing purchases. */
public interface PurchaseWorkflowService {
  PaymentIntentDto processPurchase(PurchaseRequest request);
}
