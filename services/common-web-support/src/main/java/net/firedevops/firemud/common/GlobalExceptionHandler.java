package net.firedevops.firemud.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleIllegalArgument(
      IllegalArgumentException ex) {
    ErrorDetail detail = new ErrorDetail("INVALID_ARGUMENT", ex.getMessage());
    return new ResponseEntity<>(ApiResponse.error(detail), HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleException(Exception ex) {
    ErrorDetail detail = new ErrorDetail("INTERNAL_ERROR", ex.getMessage());
    return new ResponseEntity<>(ApiResponse.error(detail), HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
