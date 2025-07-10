package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.ChatMessageDto;
import net.firedevops.firemud.dto.SendMessageRequestDto;

public interface ChatService {
  ChatMessageDto sendMessage(SendMessageRequestDto request);
}
