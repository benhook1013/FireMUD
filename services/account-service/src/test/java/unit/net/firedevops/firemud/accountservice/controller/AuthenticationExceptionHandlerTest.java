package net.firedevops.firemud.accountservice.controller;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import org.junit.jupiter.api.Test;
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
  void retainsUnauthorizedStatusForOrdinaryAuthenticationFailure() {
    var response =
        handler.handleAuthenticationException(
            new AuthenticationException("INVALID_CREDENTIALS", "invalid credentials"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody().error().code()).isEqualTo("INVALID_CREDENTIALS");
  }
}
