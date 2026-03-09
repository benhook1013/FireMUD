package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.accountservice.dto.PaymentIntentDto;
import net.firedevops.firemud.accountservice.dto.PurchaseRequest;
import net.firedevops.firemud.accountservice.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PurchaseWorkflowServiceImplTest {
  @Mock private PaymentService paymentService;
  @Mock private LoggingAdminClient loggingAdminClient;
  @Mock private SagaRunner sagaRunner;

  private PurchaseWorkflowServiceImpl service;

  @BeforeEach
  void setup() throws net.firedevops.firemud.common.saga.SagaException {
    MockitoAnnotations.openMocks(this);
    service = new PurchaseWorkflowServiceImpl(paymentService, loggingAdminClient, sagaRunner);
    doAnswer(
            inv -> {
              ((net.firedevops.firemud.common.saga.Saga) inv.getArgument(0)).run();
              return null;
            })
        .when(sagaRunner)
        .run(any());
  }

  @Test
  void processPurchaseRunsSaga() throws Exception {
    when(paymentService.createPaymentIntent(1L, 2L, 100L))
        .thenReturn(new PaymentIntentDto(5L, 1L, 2L, 100L, 5L, 95L, "USD", "secret", false));

    PurchaseRequest req = new PurchaseRequest(1L, 2L, 100L);
    PaymentIntentDto dto = service.processPurchase(req);

    assertEquals(5L, dto.id());
    verify(loggingAdminClient).logPayment(1L, 2L, 5L);
    verify(sagaRunner).run(any());
  }
}
