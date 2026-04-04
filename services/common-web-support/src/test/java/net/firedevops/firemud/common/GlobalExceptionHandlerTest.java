package net.firedevops.firemud.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GlobalExceptionHandlerTest {
  @Test
  void handleExceptionDoesNotExposeRawMessage() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();

    ApiResponse<ErrorDetail> response =
        handler.handleException(new RuntimeException("boom")).getBody();

    assertEquals("INTERNAL_ERROR", response.error().code());
    assertEquals("Internal server error", response.error().message());
  }
}
