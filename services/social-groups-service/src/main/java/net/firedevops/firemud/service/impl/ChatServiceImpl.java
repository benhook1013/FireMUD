package net.firedevops.firemud.service.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.client.LoggingAdminClient;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.config.ChatProperties;
import net.firedevops.firemud.dto.ChatMessageDto;
import net.firedevops.firemud.dto.SendMessageRequestDto;
import net.firedevops.firemud.entity.ChatMessage;
import net.firedevops.firemud.enums.ChatType;
import net.firedevops.firemud.mapper.ChatMessageMapper;
import net.firedevops.firemud.repository.ChatMessageRepository;
import net.firedevops.firemud.service.ChatService;
import net.firedevops.firemud.util.ProfanityFilter;
import org.slf4j.Logger;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.annotation.Timed;

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
  private final ChatProperties chatProperties;

  private Counter publishCounter;
  private Counter redisErrorCounter;

  @PostConstruct
  void init() {
    this.publishCounter = meterRegistry.counter("chat_messages_published_total");
    this.redisErrorCounter = meterRegistry.counter("chat_redis_errors_total");
  }

  @Override
  @Timed(value = "chat.send")
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
    message.setGuildId(request.guildId());
    message.setRecipientAccountId(request.recipientAccountId());
    message.setCityId(request.cityId());
    message.setType(request.type());
    ChatMessage saved = repository.save(message);
    publishCounter.increment();
    try {
      String key;
      ChatProperties.ChatCacheSettings settings;
      if (request.type() == ChatType.SAY) {
        key = String.format("say:%d:%d", request.tenantId(), request.recipientAccountId());
        settings = chatProperties.getSays();
      } else if (request.type() == ChatType.TELL) {
        key = String.format("tell:%d:%d", request.tenantId(), request.recipientAccountId());
        settings = chatProperties.getTells();
      } else if (request.type() == ChatType.GUILD) {
        key = String.format("guild:%d:%d", request.tenantId(), request.guildId());
        settings = chatProperties.getGuild();
      } else if (request.type() == ChatType.CITY) {
        key = String.format("city:%d:%d", request.tenantId(), request.cityId());
        settings = chatProperties.getCity();
      } else {
        key = String.format("account:%d:%d", request.tenantId(), request.recipientAccountId());
        settings = chatProperties.getAccount();
      }

      redisTemplate.opsForList().leftPush(key, saved.getContent());
      redisTemplate.expire(key, java.time.Duration.ofSeconds(settings.getHistoryTtlSeconds()));
      redisTemplate.opsForList().trim(key, 0, settings.getMaxMessages() - 1);
    } catch (Exception e) {
      redisErrorCounter.increment();
      logger.warn("Failed to publish chat message to Redis", e);
    }
    return mapper.toDto(saved);
  }

  @Override
  @Timed(value = "chat.tells")
  public java.util.List<String> getRecentTells(Long tenantId, Long accountId) {
    String key = String.format("tell:%d:%d", tenantId, accountId);
    try {
      java.util.List<Object> raw = redisTemplate.opsForList().range(key, 0, -1);
      return raw == null
          ? java.util.Collections.emptyList()
          : raw.stream().map(Object::toString).toList();
    } catch (Exception e) {
      logger.warn("Failed to fetch tell history", e);
      return java.util.Collections.emptyList();
    }
  }
}
