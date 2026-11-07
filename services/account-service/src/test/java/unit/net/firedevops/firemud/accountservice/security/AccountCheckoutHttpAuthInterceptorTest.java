package net.firedevops.firemud.accountservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.UUID;
import net.firedevops.firemud.accountservice.controller.AccountHostingFirstInvoiceController;
import net.firedevops.firemud.accountservice.controller.AccountHostingFirstInvoiceWebhookController;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService;
import net.firedevops.firemud.accountservice.service.session.AccountHostingFirstInvoicePaymentService;
import net.firedevops.firemud.common.security.HttpAuthProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class AccountCheckoutHttpAuthInterceptorTest {
  @Test
  void exactOwnerUsesActualAccountActorAndDoesNotInvokeHmacMiddleware() throws Exception {
    var legacy = mock(JwtUtil.class);
    var actors = mock(AccountControlUiActorService.class);
    var interceptor =
        new AccountCheckoutHttpAuthInterceptor(legacy, new HttpAuthProperties(), actors);
    var request =
        new MockHttpServletRequest(
            "GET", "/billing/hosting/subscriptions/" + UUID.randomUUID() + "/first-invoice");
    request.setSecure(true);
    request.addHeader("Authorization", "Bearer asymmetric-control-ui");
    when(actors.authenticateAccount("asymmetric-control-ui"))
        .thenReturn(mock(AccountControlUiActorService.AccountAuthenticatedActor.class));
    var controller =
        new AccountHostingFirstInvoiceController(
            mock(AccountHostingFirstInvoicePaymentService.class));
    var handler =
        new HandlerMethod(
            controller,
            AccountHostingFirstInvoiceController.class.getMethod(
                "read", jakarta.servlet.http.HttpServletRequest.class, String.class, UUID.class));
    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler)).isTrue();
    verify(actors).authenticateAccount("asymmetric-control-ui");
    verifyNoInteractions(legacy);
  }

  @Test
  void forgedForwardedSchemeAndMissingOrRejectedBearerNeverReachOwner() throws Exception {
    var legacy = mock(JwtUtil.class);
    var actors = mock(AccountControlUiActorService.class);
    var interceptor =
        new AccountCheckoutHttpAuthInterceptor(legacy, new HttpAuthProperties(), actors);
    var request =
        new MockHttpServletRequest("GET", "/billing/hosting/subscriptions/x/first-invoice");
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader("Authorization", "Bearer rejected");
    var controller =
        new AccountHostingFirstInvoiceController(
            mock(AccountHostingFirstInvoicePaymentService.class));
    var handler =
        new HandlerMethod(
            controller,
            AccountHostingFirstInvoiceController.class.getMethod(
                "read", jakarta.servlet.http.HttpServletRequest.class, String.class, UUID.class));
    var response = new MockHttpServletResponse();
    assertThat(interceptor.preHandle(request, response, handler)).isFalse();
    assertThat(response.getStatus()).isEqualTo(401);
    verifyNoInteractions(actors);
    request.setSecure(true);
    doThrow(new IllegalStateException("denied")).when(actors).authenticateAccount("rejected");
    assertThat(interceptor.preHandle(request, response, handler)).isFalse();
    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void unavailableJwksDoesNotMisreportSessionRevocation() throws Exception {
    var actors = mock(AccountControlUiActorService.class);
    var interceptor =
        new AccountCheckoutHttpAuthInterceptor(
            mock(JwtUtil.class), new HttpAuthProperties(), actors);
    var controller =
        new AccountHostingFirstInvoiceController(
            mock(AccountHostingFirstInvoicePaymentService.class));
    var handler =
        new HandlerMethod(
            controller,
            AccountHostingFirstInvoiceController.class.getMethod(
                "read", jakarta.servlet.http.HttpServletRequest.class, String.class, UUID.class));
    var request =
        new MockHttpServletRequest("GET", "/billing/hosting/subscriptions/x/first-invoice");
    request.setSecure(true);
    request.addHeader("Authorization", "Bearer controlled");
    doThrow(
            new net.firedevops.firemud.common.security.AccountPublicJwksCache
                .SourceUnavailableException())
        .when(actors)
        .authenticateAccount("controlled");
    var response = new MockHttpServletResponse();
    assertThat(interceptor.preHandle(request, response, handler)).isFalse();
    assertThat(response.getStatus()).isEqualTo(503);
  }

  @Test
  void unrelatedBillingHandlerDoesNotInheritTheExactOwnerException() throws Exception {
    var actors = mock(AccountControlUiActorService.class);
    var interceptor =
        new AccountCheckoutHttpAuthInterceptor(
            mock(JwtUtil.class), new HttpAuthProperties(), actors);
    var response = new MockHttpServletResponse();
    assertThat(
            interceptor.preHandle(
                new MockHttpServletRequest("POST", "/billing/arbitrary"), response, new Object()))
        .isFalse();
    assertThat(response.getStatus()).isEqualTo(401);
    verifyNoInteractions(actors);
  }

  @Test
  void webhookUsesItsOwnSignatureBoundaryWithoutAcceptingUserAuthority() throws Exception {
    var actors = mock(AccountControlUiActorService.class);
    var interceptor =
        new AccountCheckoutHttpAuthInterceptor(
            mock(JwtUtil.class), new HttpAuthProperties(), actors);
    var controller =
        new AccountHostingFirstInvoiceWebhookController(
            mock(AccountHostingFirstInvoicePaymentService.class));
    var handler =
        new HandlerMethod(
            controller,
            AccountHostingFirstInvoiceWebhookController.class.getMethod(
                "receive", jakarta.servlet.http.HttpServletRequest.class));
    var request = new MockHttpServletRequest("POST", "/billing/hosting/webhook");
    request.setSecure(true);
    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler)).isTrue();
    verifyNoInteractions(actors);
  }
}
