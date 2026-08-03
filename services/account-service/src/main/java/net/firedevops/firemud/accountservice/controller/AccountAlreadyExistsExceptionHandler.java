package net.firedevops.firemud.accountservice.controller;

import net.firedevops.firemud.accountservice.service.exception.AccountAlreadyExistsException;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AccountAlreadyExistsExceptionHandler {
  @ExceptionHandler(AccountAlreadyExistsException.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleAccountAlreadyExists(
      AccountAlreadyExistsException ex) {
    ErrorDetail detail = new ErrorDetail("ALREADY_EXISTS", ex.getMessage());
    return new ResponseEntity<>(ApiResponse.error(detail), HttpStatus.CONFLICT);
  }
}
