package net.firedevops.firemud.controller;

import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.dto.ApplyModerationActionRequest;
import net.firedevops.firemud.dto.ModerationActionDto;
import net.firedevops.firemud.service.ModerationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/moderation")
public class ModerationActionController {
  private final ModerationService service;

  public ModerationActionController(ModerationService service) {
    this.service = service;
  }

  @PostMapping("/actions")
  @Timed(value = "applyModerationAction", description = "Apply moderation action")
  public ResponseEntity<ApiResponse<ModerationActionDto>> apply(
      @Valid @RequestBody ApplyModerationActionRequest request) {
    return ResponseEntity.ok(ApiResponse.success(service.applyAction(request)));
  }
}
