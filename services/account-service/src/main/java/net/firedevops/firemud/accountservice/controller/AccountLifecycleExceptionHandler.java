package net.firedevops.firemud.accountservice.controller;

import net.firedevops.firemud.accountservice.service.exception.AccountLifecycleException;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AccountLifecycleExceptionHandler {
  @ExceptionHandler(AccountLifecycleException.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleAccountLifecycleException(
      AccountLifecycleException ex) {
    ErrorDetail detail = new ErrorDetail(ex.getCode(), ex.getMessage());
    return new ResponseEntity<>(ApiResponse.error(detail), HttpStatus.CONFLICT);
  }
}
