package net.firedevops.firemud.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerTest {
  @Test
  void handleExceptionDoesNotExposeRawMessage() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();

    ApiResponse<ErrorDetail> response =
        handler.handleException(new RuntimeException("boom")).getBody();

    assertEquals("INTERNAL_ERROR", response.error().code());
    assertEquals("Internal server error", response.error().message());
  }

  @Test
  void handleBindExceptionNormalizesNumericTypeMismatch() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    BeanPropertyBindingResult bindingResult =
        new BeanPropertyBindingResult(new Object(), "request");
    bindingResult.addError(
        new FieldError(
            "request",
            "pointerVersion",
            "abc",
            false,
            new String[] {"typeMismatch"},
            null,
            "Failed to convert property value of type 'java.lang.String' to required type"
                + " 'java.lang.Long' for property 'pointerVersion'; For input string: \"abc\""));

    ApiResponse<ErrorDetail> response =
        handler.handleBindException(new BindException(bindingResult)).getBody();

    Assertions.assertNotNull(response);
    assertEquals("INVALID_ARGUMENT", response.error().code());
    assertEquals("pointerVersion must be numeric", response.error().message());
  }

  @Test
  void handleResponseStatusPreservesCanonicalNotFoundEnvelope() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();

    ApiResponse<ErrorDetail> response =
        handler
            .handleResponseStatus(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Missing thing"))
            .getBody();

    Assertions.assertNotNull(response);
    assertEquals("NOT_FOUND", response.error().code());
    assertEquals("Missing thing", response.error().message());
  }

  @Test
  void handleResponseStatusNormalizesBadRequestToInvalidArgument() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();

    ApiResponse<ErrorDetail> response =
        handler
            .handleResponseStatus(
                new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "pointerVersion must be positive"))
            .getBody();

    Assertions.assertNotNull(response);
    assertEquals("INVALID_ARGUMENT", response.error().code());
    assertEquals("pointerVersion must be positive", response.error().message());
  }
}
