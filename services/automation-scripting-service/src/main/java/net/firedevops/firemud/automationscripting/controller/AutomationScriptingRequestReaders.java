package net.firedevops.firemud.automationscripting.controller;

import java.util.function.Supplier;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

final class AutomationScriptingRequestReaders {
  private AutomationScriptingRequestReaders() {}

  static long requirePositiveLong(String value, String fieldName) {
    return RequestIdValidation.requirePositiveLong(value, fieldName);
  }

  static int requireInteger(String value, String fieldName) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(fieldName + " must be numeric", ex);
    }
  }

  static PlayableStateScope requirePlayableStateScope(String value) {
    try {
      return PlayableStateScope.valueOf(value);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("playableStateScope is invalid", ex);
    }
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
