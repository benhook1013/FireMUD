package net.firedevops.firemud.accountservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.stream.Stream;
import net.firedevops.firemud.accountservice.service.session.AccountHostingFirstInvoicePaymentService;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class AccountHostingFirstInvoiceControllerTest {
  @Test
  void dependencyUnavailabilityIsNotReportedAsRevocationAndNeverDisclosesSecrets() {
    var controller =
        new AccountHostingFirstInvoiceController(
            mock(AccountHostingFirstInvoicePaymentService.class));
    var unavailable = controller.authenticationUnavailable();
    assertThat(unavailable.getStatusCode().value()).isEqualTo(503);
    assertThat(unavailable.getBody().code()).isEqualTo("AUTH_UNAVAILABLE");
    assertThat(unavailable.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(controller.authenticationRefused().getStatusCode().value()).isEqualTo(401);
  }

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

  @ParameterizedTest
  @MethodSource("ownerFailures")
  void exactControllerExceptionMappingPreservesStatusAndNoStore(
      RuntimeException failure, int expectedStatus, String expectedCode) throws Exception {
    var service = mock(AccountHostingFirstInvoicePaymentService.class);
    UUID subscription = UUID.randomUUID();
    when(service.describeOwner("controlled-bearer", subscription)).thenThrow(failure);
    var mvc = registeredController(service);

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/billing/hosting/subscriptions/" + subscription + "/first-invoice")
                .secure(true)
                .header("Authorization", "Bearer controlled-bearer"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .is(expectedStatus))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Cache-Control", "no-store"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value(expectedCode));
  }

  static Stream<Arguments> ownerFailures() {
    return Stream.of(
        Arguments.of(
            new AccountAsymmetricJwtVerifier.VerificationException(), 401, "AUTH_SESSION_REVOKED"),
        Arguments.of(
            new IllegalArgumentException("private request detail"),
            400,
            "FIRST_INVOICE_INVALID_REQUEST"),
        Arguments.of(
            new IllegalStateException("private evidence detail"),
            409,
            "FIRST_INVOICE_EVIDENCE_REFUSED"));
  }

  @Test
  void typedSourceUnavailableUsesTheExistingOwningAvailabilityHandler() throws Exception {
    var service = mock(AccountHostingFirstInvoicePaymentService.class);
    UUID subscription = UUID.randomUUID();
    when(service.describeOwner("controlled-bearer", subscription))
        .thenThrow(new AccountAsymmetricJwtVerifier.VerificationUnavailableException());
    var mvc = registeredController(service);

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/billing/hosting/subscriptions/" + subscription + "/first-invoice")
                .secure(true)
                .header("Authorization", "Bearer controlled-bearer"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isServiceUnavailable())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Cache-Control", "no-store"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value("AUTH_UNAVAILABLE"));
  }

  private static org.springframework.test.web.servlet.MockMvc registeredController(
      AccountHostingFirstInvoicePaymentService service) {
    // Register only this prepared controller in the test; production activation remains held.
    return MockMvcBuilders.standaloneSetup(new AccountHostingFirstInvoiceController(service))
        .setCustomHandlerMapping(
            () ->
                new RequestMappingHandlerMapping() {
                  @Override
                  protected boolean isHandler(Class<?> beanType) {
                    return AccountHostingFirstInvoiceController.class.equals(beanType);
                  }
                })
        .build();
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
