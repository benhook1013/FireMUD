package net.firedevops.firemud.entitymanagement.controller;

import java.util.function.Supplier;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import net.firedevops.firemud.common.security.RequestIdValidation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

final class EntityManagementRequestReaders {
  private EntityManagementRequestReaders() {}

  static long requirePositivePathId(String value, String fieldName) {
    return RequestIdValidation.requirePositiveLong(value, fieldName);
  }

  static AccountScope requireAccountScope(String tenantId, String accountId) {
    return new AccountScope(
        requirePositivePathId(tenantId, "tenantId"), requirePositivePathId(accountId, "accountId"));
  }

  static CharacterScope requireCharacterScope(String tenantId, String characterId) {
    return new CharacterScope(
        requirePositivePathId(tenantId, "tenantId"),
        requirePositivePathId(characterId, "characterId"));
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

  record AccountScope(long tenantId, long accountId) {}

  record CharacterScope(long tenantId, long characterId) {}
}
