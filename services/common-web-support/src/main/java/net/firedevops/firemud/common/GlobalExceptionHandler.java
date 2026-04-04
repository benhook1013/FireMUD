package net.firedevops.firemud.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleIllegalArgument(
      IllegalArgumentException ex) {
    ErrorDetail detail = new ErrorDetail("INVALID_ARGUMENT", ex.getMessage());
    return new ResponseEntity<>(ApiResponse.error(detail), HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleResponseStatus(ResponseStatusException ex) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    String code = status == HttpStatus.FORBIDDEN ? "FORBIDDEN" : status.name();
    String message = ex.getReason() == null ? status.getReasonPhrase() : ex.getReason();
    ErrorDetail detail = new ErrorDetail(code, message);
    return new ResponseEntity<>(ApiResponse.error(detail), status);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handleException(Exception ex) {
    ErrorDetail detail = new ErrorDetail("INTERNAL_ERROR", "Internal server error");
    return new ResponseEntity<>(ApiResponse.error(detail), HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
