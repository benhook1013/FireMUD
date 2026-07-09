package net.firedevops.firemud.loggingadmin.controller;

import java.util.function.Supplier;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;

final class LoggingAdminRequestReaders {
  private LoggingAdminRequestReaders() {}

  static long requireTenantAccess(String tenantId) {
    long parsedTenantId = RequestIdValidation.requirePositiveLong(tenantId, "tenantId");
    SessionContext.requireTenantAccess(parsedTenantId);
    return parsedTenantId;
  }

  static long requirePositiveLong(String value, String fieldName) {
    return RequestIdValidation.requirePositiveLong(value, fieldName);
  }

  static String requireOptionalPositiveLongText(String value, String fieldName) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return Long.toString(requirePositiveLong(value, fieldName));
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
