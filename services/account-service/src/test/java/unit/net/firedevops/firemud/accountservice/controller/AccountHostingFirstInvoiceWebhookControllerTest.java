package net.firedevops.firemud.accountservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.session.AccountHostingFirstInvoicePaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class AccountHostingFirstInvoiceWebhookControllerTest {
  @Test
  void exactRawBodyAndSignatureReachTheSignedCompletionOwner() throws Exception {
    var payments = mock(AccountHostingFirstInvoicePaymentService.class);
    var controller = new AccountHostingFirstInvoiceWebhookController(payments);
    String raw = "{ \"id\": \"evt_controlled\",\n\"data\":{} }";
    var request = request(raw.getBytes(StandardCharsets.UTF_8));
    var result =
        new AccountHostingFirstInvoicePaymentService.Completion("COMMITTED", UUID.randomUUID());
    when(payments.completeSigned(raw, "t=1,v1=controlled")).thenReturn(result);
    var response = controller.receive(request);
    assertThat(response.getBody()).isEqualTo(result);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    verify(payments).completeSigned(raw, "t=1,v1=controlled");
  }

  @Test
  void exactUnrelatedEventClassificationIsAcknowledgedWithoutCache() throws Exception {
    var payments = mock(AccountHostingFirstInvoicePaymentService.class);
    var completion = new AccountHostingFirstInvoicePaymentService.Completion("IGNORED", null);
    when(payments.completeSigned("{}", "t=1,v1=controlled")).thenReturn(completion);

    var response =
        new AccountHostingFirstInvoiceWebhookController(payments)
            .receive(request("{}".getBytes(StandardCharsets.UTF_8)));

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isEqualTo(completion);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getHeaders().getFirst("Pragma")).isEqualTo("no-cache");
    verify(payments).completeSigned("{}", "t=1,v1=controlled");
  }

  @Test
  void exactLateConflictClassificationIsAcknowledgedForOperatorReconciliation() throws Exception {
    var payments = mock(AccountHostingFirstInvoicePaymentService.class);
    UUID requestId = UUID.randomUUID();
    var completion =
        new AccountHostingFirstInvoicePaymentService.Completion(
            "OPERATOR_RECONCILIATION_REQUIRED", requestId);
    when(payments.completeSigned("{}", "t=1,v1=controlled")).thenReturn(completion);

    var response =
        new AccountHostingFirstInvoiceWebhookController(payments)
            .receive(request("{}".getBytes(StandardCharsets.UTF_8)));

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isEqualTo(completion);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getHeaders().getFirst("Pragma")).isEqualTo("no-cache");
    verify(payments).completeSigned("{}", "t=1,v1=controlled");
  }

  @Test
  void retainedButNonterminalCompletionIsNotAcknowledgedAsProcessed() throws Exception {
    var payments = mock(AccountHostingFirstInvoicePaymentService.class);
    when(payments.completeSigned("{}", "t=1,v1=controlled"))
        .thenReturn(
            new AccountHostingFirstInvoicePaymentService.Completion(
                "RECONCILING", UUID.randomUUID()));
    var result =
        new AccountHostingFirstInvoiceWebhookController(payments)
            .receive(request("{}".getBytes(StandardCharsets.UTF_8)));
    assertThat(result.getStatusCode().value()).isEqualTo(503);
    assertThat(result.getBody().state()).isEqualTo("RECONCILING");
    assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
  }

  @Test
  void insecureUnsignedAmbiguousMalformedAndOversizeRequestsNeverReachOwner() {
    var payments = mock(AccountHostingFirstInvoicePaymentService.class);
    var controller = new AccountHostingFirstInvoiceWebhookController(payments);
    var insecure = request(new byte[] {123, 125});
    insecure.setSecure(false);
    assertThatThrownBy(() -> controller.receive(insecure))
        .isInstanceOf(ResponseStatusException.class);
    var unsigned = request(new byte[] {123, 125});
    unsigned.removeHeader("Stripe-Signature");
    assertThatThrownBy(() -> controller.receive(unsigned))
        .isInstanceOf(ResponseStatusException.class);
    var ambiguous = request(new byte[] {123, 125});
    ambiguous.addHeader("Stripe-Signature", "another");
    assertThatThrownBy(() -> controller.receive(ambiguous))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> controller.receive(request(new byte[] {(byte) 0xc3, 0x28})))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                controller.receive(
                    request(
                        new byte[AccountHostingFirstInvoiceWebhookController.MAX_BODY_BYTES + 1])))
        .isInstanceOf(ResponseStatusException.class);
    verifyNoInteractions(payments);
  }

  private static MockHttpServletRequest request(byte[] bytes) {
    var request = new MockHttpServletRequest("POST", "/billing/hosting/webhook");
    request.setSecure(true);
    request.setContent(bytes);
    request.addHeader("Stripe-Signature", "t=1,v1=controlled");
    return request;
  }
}
