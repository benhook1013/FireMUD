package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.PaymentIntentDto;
import net.firedevops.firemud.accountservice.dto.PurchaseRequest;
import net.firedevops.firemud.accountservice.service.PaymentService;
import net.firedevops.firemud.common.saga.SagaRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PurchaseWorkflowServiceImplTest {
  @Mock private PaymentService paymentService;
  @Mock private LoggingAdminClient loggingAdminClient;
  @Mock private SagaRunner sagaRunner;

  private PurchaseWorkflowServiceImpl service;
  private PaymentIntentDto paymentIntent;

  @BeforeEach
  void setup() throws net.firedevops.firemud.common.saga.SagaException {
    MockitoAnnotations.openMocks(this);
    service = new PurchaseWorkflowServiceImpl(paymentService, loggingAdminClient, sagaRunner);
    paymentIntent = new PaymentIntentDto(5L, 1L, 2L, 100L, 5L, 95L, "USD", "secret", false);
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
    when(paymentService.createPaymentIntent(1L, 2L, 100L)).thenReturn(paymentIntent);

    PurchaseRequest req = new PurchaseRequest(1L, 2L, 100L);
    PaymentIntentDto dto = service.processPurchase(req);

    assertEquals(5L, dto.id());
    verify(loggingAdminClient).logPayment(1L, 2L, 5L);
    verify(sagaRunner).run(any());
  }

  @Test
  void processPurchaseRefundsWhenLoggingStepFails() throws Exception {
    when(paymentService.createPaymentIntent(1L, 2L, 100L)).thenReturn(paymentIntent);
    doThrow(new RuntimeException("logging unavailable"))
        .when(loggingAdminClient)
        .logPayment(1L, 2L, 5L);

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> service.processPurchase(new PurchaseRequest(1L, 2L, 100L)));

    assertEquals("Purchase failed", ex.getMessage());
    verify(paymentService).refundPayment(1L, 5L);
    verify(loggingAdminClient).logPayment(1L, 2L, 5L);
  }
}
