package net.firedevops.firemud.accountservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.session.AccountHostingFirstInvoicePaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AccountHostingFirstInvoiceControllerTest {
  @Test
  void insecureTransportAndMissingControlUiBearerCannotReadOrDiscloseCheckout() {
    var service = mock(AccountHostingFirstInvoicePaymentService.class);
    var controller = new AccountHostingFirstInvoiceController(service);
    var transport = mock(HttpServletRequest.class);
    assertThatThrownBy(() -> controller.read(transport, "Bearer controlled", UUID.randomUUID()))
        .isInstanceOf(ResponseStatusException.class);
    when(transport.isSecure()).thenReturn(true);
    assertThatThrownBy(() -> controller.read(transport, null, UUID.randomUUID()))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> controller.read(transport, "Bearer ", UUID.randomUUID()))
        .isInstanceOf(ResponseStatusException.class);
    verifyNoInteractions(service);
  }

  @Test
  void passwordAndClientSecretNeverAppearInRequestOrResponseDiagnosticStrings() {
    assertThat(
            new AccountHostingFirstInvoiceController.ReauthenticationRequest(
                    UUID.randomUUID(), UUID.randomUUID(), "owner@example.test", "controlled-secret")
                .toString())
        .doesNotContain("controlled-secret", "owner@example.test");
    assertThat(
            new AccountHostingFirstInvoiceController.ConfirmationResponse(
                    "requires_action", "pi_exact_secret_controlled")
                .toString())
        .doesNotContain("secret_controlled");
  }

  @Test
  void exactChargeConsentIsRequiredBeforeProviderWork() {
    var service = mock(AccountHostingFirstInvoicePaymentService.class);
    var controller = new AccountHostingFirstInvoiceController(service);
    var transport = mock(HttpServletRequest.class);
    when(transport.isSecure()).thenReturn(true);
    var request =
        new AccountHostingFirstInvoiceController.ConfirmationRequest(
            UUID.randomUUID(), 3, UUID.randomUUID(), "sha256:" + "a".repeat(64), false);
    assertThatThrownBy(
            () -> controller.confirm(transport, "Bearer controlled", UUID.randomUUID(), request))
        .isInstanceOf(ResponseStatusException.class);
    verifyNoInteractions(service);
  }

  @Test
  void renewedConsentAlsoRequiresSecureCurrentOwnerAndExplicitChargeApproval() {
    var service = mock(AccountHostingFirstInvoicePaymentService.class);
    var controller = new AccountHostingFirstInvoiceController(service);
    var transport = mock(HttpServletRequest.class);
    var request =
        new AccountHostingFirstInvoiceController.RenewalRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            3,
            1,
            UUID.randomUUID(),
            "sha256:" + "a".repeat(64),
            false);
    assertThatThrownBy(
            () -> controller.renew(transport, "Bearer controlled", UUID.randomUUID(), request))
        .isInstanceOf(ResponseStatusException.class);
    when(transport.isSecure()).thenReturn(true);
    assertThatThrownBy(
            () -> controller.renew(transport, "Bearer controlled", UUID.randomUUID(), request))
        .isInstanceOf(ResponseStatusException.class);
    verifyNoInteractions(service);
  }
}
