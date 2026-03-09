package net.firedevops.firemud.loggingadmin.controller;

import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.loggingadmin.dto.FeatureFlagDto;
import net.firedevops.firemud.loggingadmin.dto.ToggleFeatureFlagRequest;
import net.firedevops.firemud.loggingadmin.service.FeatureFlagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/feature-flags")
public class FeatureFlagController {
  private final FeatureFlagService service;

  public FeatureFlagController(FeatureFlagService service) {
    this.service = service;
  }

  @PostMapping("/toggle")
  @Timed(value = "featureFlagToggle", description = "Toggle a runtime feature flag")
  public ResponseEntity<ApiResponse<FeatureFlagDto>> toggle(
      @Valid @RequestBody ToggleFeatureFlagRequest request) {
    FeatureFlagDto dto = service.toggleFlag(request);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }
}
