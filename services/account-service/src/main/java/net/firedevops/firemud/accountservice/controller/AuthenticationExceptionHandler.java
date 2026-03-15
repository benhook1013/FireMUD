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
  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleAuthenticationException(
      AuthenticationException ex) {
    ErrorDetail detail = new ErrorDetail(ex.getCode(), ex.getMessage());
    return new ResponseEntity<>(ApiResponse.error(detail), HttpStatus.UNAUTHORIZED);
  }
}
