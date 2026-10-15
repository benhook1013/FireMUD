package net.firedevops.firemud.worldmanagement.controller;

import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {GenerationRuleController.class, RegionController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorldDesignPublicationConflictAdvice {
  private static final String SAFE_MESSAGE =
      "World design publication state conflicts with the request";

  @ExceptionHandler(WorldDesignPublicationFenceRepository.ConflictException.class)
  public ResponseEntity<ApiResponse<ErrorDetail>> handlePublicationConflict(
      WorldDesignPublicationFenceRepository.ConflictException exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiResponse.error(new ErrorDetail("CONFLICT", SAFE_MESSAGE)));
  }
}
