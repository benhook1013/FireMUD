package net.firedevops.firemud.socialgroups.controller;

import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.socialgroups.dto.ChatMessageDto;
import net.firedevops.firemud.socialgroups.dto.SendMessageRequestDto;
import net.firedevops.firemud.socialgroups.security.SocialAccessGuard;
import net.firedevops.firemud.socialgroups.service.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat")
public class ChatController {
  private final ChatService chatService;
  private final SocialAccessGuard socialAccessGuard;

  public ChatController(ChatService chatService, SocialAccessGuard socialAccessGuard) {
    this.chatService = chatService;
    this.socialAccessGuard = socialAccessGuard;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<ChatMessageDto>> sendMessage(
      @Valid @RequestBody SendMessageRequestDto request) {
    socialAccessGuard.requireAccountAccess(request.tenantId(), request.senderAccountId());
    ChatMessageDto dto = chatService.sendMessage(request);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }
}
