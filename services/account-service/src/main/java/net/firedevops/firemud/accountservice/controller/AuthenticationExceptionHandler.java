package net.firedevops.firemud.accountservice.controller;

import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link AuthenticationException} to a structured API error so clients keep seeing predictable
 * responses.
 */
@RestControllerAdvice
public class AuthenticationExceptionHandler {
  private static final java.util.Set<String> FORBIDDEN_CODES =
      java.util.Set.of(
          "CONNECT_TOKEN_REJECTED",
          "NON_PUBLIC_ENROLLMENT_REQUIRED",
          "PUBLIC_PRODUCTION_ADMISSION_DENIED",
          "JOIN_REQUIRED",
          "TENANT_BILLING_BLOCKED");
  private static final java.util.Set<String> CONFLICT_CODES =
      java.util.Set.of("ADMISSION_POINTER_UNAVAILABLE", "CONNECT_SCOPE_MISMATCH");

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleAuthenticationException(
      AuthenticationException ex) {
    ErrorDetail detail = new ErrorDetail(ex.getCode(), ex.getMessage());
    HttpStatus status =
        "AUTH_UNAVAILABLE".equals(ex.getCode()) || "ENTITLEMENT_UNAVAILABLE".equals(ex.getCode())
            ? HttpStatus.SERVICE_UNAVAILABLE
            : FORBIDDEN_CODES.contains(ex.getCode())
                ? HttpStatus.FORBIDDEN
                : CONFLICT_CODES.contains(ex.getCode())
                    ? HttpStatus.CONFLICT
                    : HttpStatus.UNAUTHORIZED;
    return new ResponseEntity<>(ApiResponse.error(detail), status);
  }
}
