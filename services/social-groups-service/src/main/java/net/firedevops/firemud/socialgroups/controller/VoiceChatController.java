package net.firedevops.firemud.socialgroups.controller;

import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.socialgroups.dto.VoiceTokenDto;
import net.firedevops.firemud.socialgroups.dto.VoiceTokenRequestDto;
import net.firedevops.firemud.socialgroups.service.VoiceChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoint for obtaining voice chat tokens. */
@RestController
@RequestMapping("/voice/token")
public class VoiceChatController {
  private final VoiceChatService service;

  public VoiceChatController(VoiceChatService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<VoiceTokenDto>> createToken(
      @Valid @RequestBody VoiceTokenRequestDto request) {
    VoiceTokenDto token = service.createToken(request);
    return ResponseEntity.ok(ApiResponse.success(token));
  }
}
