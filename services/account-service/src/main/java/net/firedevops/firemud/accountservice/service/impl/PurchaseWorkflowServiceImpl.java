package net.firedevops.firemud.accountservice.service.impl;

import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.PaymentIntentDto;
import net.firedevops.firemud.accountservice.dto.PurchaseRequest;
import net.firedevops.firemud.accountservice.service.PaymentService;
import net.firedevops.firemud.accountservice.service.PurchaseWorkflowService;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements purchase workflows using SagaRunner. */
@Service
public class PurchaseWorkflowServiceImpl implements PurchaseWorkflowService {
  private static final Logger logger = LoggingUtil.getLogger(PurchaseWorkflowServiceImpl.class);

  private final PaymentService paymentService;
  private final LoggingAdminClient loggingAdminClient;
  private final SagaRunner sagaRunner;

  public PurchaseWorkflowServiceImpl(
      PaymentService paymentService, LoggingAdminClient loggingAdminClient, SagaRunner sagaRunner) {
    this.paymentService = paymentService;
    this.loggingAdminClient = loggingAdminClient;
    this.sagaRunner = sagaRunner;
  }

  @Override
  @Transactional
  @Timed(value = "payment.purchase")
  public PaymentIntentDto processPurchase(PurchaseRequest request) {
    final PaymentIntentDto[] ref = new PaymentIntentDto[1];
    SagaBuilder builder = new SagaBuilder("purchase");
    builder
        .step(
            "createIntent",
            () ->
                ref[0] =
                    paymentService.createPaymentIntent(
                        request.tenantId(), request.accountId(), request.amountCents()),
            () -> {
              if (ref[0] != null) {
                paymentService.refundPayment(request.tenantId(), ref[0].id());
              }
            })
        .step(
            "log",
            () ->
                loggingAdminClient.logPayment(
                    request.tenantId(), request.accountId(), ref[0].id()));
    try {
      sagaRunner.run(builder.build());
    } catch (SagaException e) {
      logger.warn("Purchase saga failed", e);
      throw new IllegalStateException("Purchase failed", e);
    }
    return ref[0];
  }
}
