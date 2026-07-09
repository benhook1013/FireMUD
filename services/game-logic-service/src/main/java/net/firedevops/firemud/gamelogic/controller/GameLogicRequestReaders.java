package net.firedevops.firemud.gamelogic.controller;

import java.util.function.Supplier;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import net.firedevops.firemud.common.security.RequestIdValidation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

final class GameLogicRequestReaders {
  private GameLogicRequestReaders() {}

  static Long requireOptionalPositiveLong(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return RequestIdValidation.requirePositiveLong(value, fieldName);
  }

  static <T> ResponseEntity<ApiResponse<T>> withBadRequest(
      Supplier<ResponseEntity<ApiResponse<T>>> action) {
    try {
      return action.get();
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(ApiResponse.error(new ErrorDetail("INVALID_ARGUMENT", ex.getMessage())));
    }
  }
}
