package net.firedevops.firemud.accountservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.UUID;
import java.util.stream.Stream;
import net.firedevops.firemud.accountservice.controller.AccountHostingFirstInvoiceController;
import net.firedevops.firemud.accountservice.controller.AccountHostingFirstInvoiceWebhookController;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService;
import net.firedevops.firemud.accountservice.service.session.AccountHostingFirstInvoicePaymentService;
import net.firedevops.firemud.common.security.HttpAuthProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
    var secureResponse = new MockHttpServletResponse();
    assertThat(interceptor.preHandle(request, secureResponse, handler)).isFalse();
    assertThat(secureResponse.getStatus()).isEqualTo(401);
  }

  @ParameterizedTest
  @MethodSource("unavailableAuthorities")
  void unavailableAuthorityDoesNotMisreportSessionRevocation(RuntimeException failure)
      throws Exception {
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
    doThrow(failure).when(actors).authenticateAccount("controlled");
    var response = new MockHttpServletResponse();
    assertThat(interceptor.preHandle(request, response, handler)).isFalse();
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
    assertThat(response.getContentAsString()).isEmpty();
    verify(actors).authenticateAccount("controlled");
    verifyNoMoreInteractions(actors);
  }

  static Stream<RuntimeException> unavailableAuthorities() {
    return Stream.of(
        registryFailure(() -> null),
        registryFailure(() -> mock(io.lettuce.core.api.StatefulRedisConnection.class)),
        new net.firedevops.firemud.accountservice.service.session.AccountControlUiSignerOwner
            .SignerSourceUnavailableException(),
        new net.firedevops.firemud.common.security.AccountPublicJwksCache
            .SourceUnavailableException(),
        new org.springframework.transaction.CannotCreateTransactionException(
            "private database connection detail"),
        new org.springframework.transaction.TransactionSystemException(
            "private transaction detail"),
        new org.springframework.transaction.UnexpectedRollbackException("private rollback detail"));
  }

  private static RuntimeException registryFailure(
      net.firedevops.firemud.accountservice.service.session
              .AccountCoordinationPinnedConnectionProvider
          provider) {
    try {
      new net.firedevops.firemud.accountservice.service.session.AccountControlUiRegistryReader(
              provider)
          .readActive("a".repeat(64));
      throw new AssertionError("Expected unavailable registry");
    } catch (
        net.firedevops.firemud.accountservice.service.session.AccountControlUiRegistryReader
                .RegistryUnavailableException
            failure) {
      return failure;
    }
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
