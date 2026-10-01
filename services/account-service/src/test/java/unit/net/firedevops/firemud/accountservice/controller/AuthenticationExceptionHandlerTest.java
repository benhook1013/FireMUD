package net.firedevops.firemud.accountservice.controller;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class AuthenticationExceptionHandlerTest {

  private final AuthenticationExceptionHandler handler = new AuthenticationExceptionHandler();

  @Test
  void mapsAuthenticationAuthorityUnavailableToServiceUnavailable() {
    var response =
        handler.handleAuthenticationException(
            new AuthenticationException("AUTH_UNAVAILABLE", "retry later"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody().error().code()).isEqualTo("AUTH_UNAVAILABLE");
  }

  @Test
  void mapsEntitlementAuthorityUnavailableToServiceUnavailable() {
    var response =
        handler.handleAuthenticationException(
            new AuthenticationException("ENTITLEMENT_UNAVAILABLE", "retry later"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody().error().code()).isEqualTo("ENTITLEMENT_UNAVAILABLE");
  }

  @Test
  void retainsUnauthorizedStatusForOrdinaryAuthenticationFailure() {
    var response =
        handler.handleAuthenticationException(
            new AuthenticationException("INVALID_CREDENTIALS", "invalid credentials"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody().error().code()).isEqualTo("INVALID_CREDENTIALS");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CONNECT_TOKEN_REJECTED",
        "NON_PUBLIC_ENROLLMENT_REQUIRED",
        "PUBLIC_PRODUCTION_ADMISSION_DENIED",
        "JOIN_REQUIRED",
        "TENANT_BILLING_BLOCKED"
      })
  void mapsAuthenticatedAdmissionDenialsToForbidden(String code) {
    var response =
        handler.handleAuthenticationException(new AuthenticationException(code, "denied"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody().error().code()).isEqualTo(code);
    assertThat(response.getBody().error().message()).isEqualTo("denied");
  }
}
