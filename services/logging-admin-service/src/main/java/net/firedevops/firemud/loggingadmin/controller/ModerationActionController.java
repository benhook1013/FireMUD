package net.firedevops.firemud.loggingadmin.controller;

import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.ApplyModerationActionRequest;
import net.firedevops.firemud.loggingadmin.dto.ModerationActionDto;
import net.firedevops.firemud.loggingadmin.service.ModerationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/moderation")
public class ModerationActionController {
  private static final String MODERATION_ACTION_UNAVAILABLE_CODE = "MODERATION_ACTION_UNAVAILABLE";
  private static final String MODERATION_ACTION_UNAVAILABLE_MESSAGE =
      "Moderation actions are unavailable until the shared mutation gate is implemented";

  private final ModerationService service;

  public ModerationActionController(ModerationService service) {
    this.service = service;
  }

  @PostMapping("/actions")
  @Timed(value = "applyModerationAction", description = "Apply moderation action")
  public ResponseEntity<ApiResponse<ModerationActionDto>> apply(
      @Valid @RequestBody ApplyModerationActionRequest request) {
    SessionContext.requireTenantAccess(request.tenantId());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(
            ApiResponse.error(
                new ErrorDetail(
                    MODERATION_ACTION_UNAVAILABLE_CODE, MODERATION_ACTION_UNAVAILABLE_MESSAGE)));
  }
}
