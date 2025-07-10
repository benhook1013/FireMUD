package net.firedevops.firemud.service.impl;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.ChatMessageDto;
import net.firedevops.firemud.dto.SendMessageRequestDto;
import net.firedevops.firemud.entity.ChatMessage;
import net.firedevops.firemud.mapper.ChatMessageMapper;
import net.firedevops.firemud.repository.ChatMessageRepository;
import net.firedevops.firemud.service.ChatService;
import net.firedevops.firemud.util.ProfanityFilter;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {
  private static final Logger logger = LoggingUtil.getLogger(ChatServiceImpl.class);

  private final ChatMessageRepository repository;
  private final ChatMessageMapper mapper;
  private final ProfanityFilter profanityFilter;

  @Override
  @Transactional
  public ChatMessageDto sendMessage(SendMessageRequestDto request) {
    logger.info("Chat message from {}", request.senderAccountId());
    ChatMessage message = new ChatMessage();
    message.setTenantId(request.tenantId());
    message.setSenderAccountId(request.senderAccountId());
    message.setContent(profanityFilter.filter(request.content()));
    message.setTimestamp(Instant.now());
    message.setGuildId(null);
    message.setRecipientAccountId(null);
    return mapper.toDto(repository.save(message));
  }
}
