package net.firedevops.firemud.socialgroups.service;

import net.firedevops.firemud.socialgroups.dto.ChatMessageDto;
import net.firedevops.firemud.socialgroups.dto.SendMessageRequestDto;

public interface ChatService {
  ChatMessageDto sendMessage(SendMessageRequestDto request);

  java.util.List<String> getRecentTells(Long tenantId, Long accountId);
}
