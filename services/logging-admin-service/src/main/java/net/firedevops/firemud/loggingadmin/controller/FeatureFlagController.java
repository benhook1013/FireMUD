package net.firedevops.firemud.loggingadmin.controller;

import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.FeatureFlagDto;
import net.firedevops.firemud.loggingadmin.dto.ToggleFeatureFlagRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/feature-flags")
public class FeatureFlagController {
  private static final String FEATURE_FLAG_TOGGLE_UNAVAILABLE_CODE =
      "FEATURE_FLAG_TOGGLE_UNAVAILABLE";
  private static final String FEATURE_FLAG_TOGGLE_UNAVAILABLE_MESSAGE =
      "Feature-flag toggles are unavailable until the shared mutation gate is implemented";

  @PostMapping("/toggle")
  @Timed(value = "featureFlagToggle", description = "Toggle a runtime feature flag")
  public ResponseEntity<ApiResponse<FeatureFlagDto>> toggle(
      @Valid @RequestBody ToggleFeatureFlagRequest request) {
    SessionContext.requireTenantAccess(request.tenantId());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(
            ApiResponse.error(
                new ErrorDetail(
                    FEATURE_FLAG_TOGGLE_UNAVAILABLE_CODE,
                    FEATURE_FLAG_TOGGLE_UNAVAILABLE_MESSAGE)));
  }
}
