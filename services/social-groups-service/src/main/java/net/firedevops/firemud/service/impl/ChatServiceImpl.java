package net.firedevops.firemud.service.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.client.LoggingAdminClient;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.ChatMessageDto;
import net.firedevops.firemud.dto.SendMessageRequestDto;
import net.firedevops.firemud.entity.ChatMessage;
import net.firedevops.firemud.mapper.ChatMessageMapper;
import net.firedevops.firemud.repository.ChatMessageRepository;
import net.firedevops.firemud.service.ChatService;
import net.firedevops.firemud.util.ProfanityFilter;
import org.slf4j.Logger;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {
  private static final Logger logger = LoggingUtil.getLogger(ChatServiceImpl.class);

  private final ChatMessageRepository repository;
  private final ChatMessageMapper mapper;
  private final ProfanityFilter profanityFilter;
  private final LoggingAdminClient loggingAdminClient;
  private final RedisTemplate<String, Object> redisTemplate;
  private final MeterRegistry meterRegistry;

  private Counter publishCounter;
  private Counter redisErrorCounter;

  @PostConstruct
  void init() {
    this.publishCounter = meterRegistry.counter("chat_messages_published_total");
    this.redisErrorCounter = meterRegistry.counter("chat_redis_errors_total");
  }

  @Override
  @Transactional
  public ChatMessageDto sendMessage(SendMessageRequestDto request) {
    logger.info("Chat message from {}", request.senderAccountId());
    String filtered = profanityFilter.filter(request.content());
    if (!filtered.equals(request.content())) {
      loggingAdminClient.reportChatViolation(
          request.tenantId(), request.senderAccountId(), "Filtered profanity");
    }

    ChatMessage message = new ChatMessage();
    message.setTenantId(request.tenantId());
    message.setSenderAccountId(request.senderAccountId());
    message.setContent(filtered);
    message.setTimestamp(Instant.now());
    message.setGuildId(null);
    message.setRecipientAccountId(null);
    ChatMessage saved = repository.save(message);
    publishCounter.increment();
    try {
      String key = String.format("chat:%d:%s", request.tenantId(), request.channelId());
      redisTemplate.opsForList().leftPush(key, saved.getContent());
    } catch (Exception e) {
      redisErrorCounter.increment();
      logger.warn("Failed to publish chat message to Redis", e);
    }
    return mapper.toDto(saved);
  }
}
