package net.firedevops.firemud.socialgroups.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.socialgroups.client.LoggingAdminClient;
import net.firedevops.firemud.socialgroups.config.ChatProperties;
import net.firedevops.firemud.socialgroups.dto.ChatMessageDto;
import net.firedevops.firemud.socialgroups.dto.SendMessageRequestDto;
import net.firedevops.firemud.socialgroups.entity.ChatMessage;
import net.firedevops.firemud.socialgroups.enums.ChatType;
import net.firedevops.firemud.socialgroups.mapper.ChatMessageMapper;
import net.firedevops.firemud.socialgroups.repository.ChatMessageRepository;
import net.firedevops.firemud.socialgroups.service.ChatService;
import net.firedevops.firemud.socialgroups.util.ProfanityFilter;
import org.slf4j.Logger;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected services are managed by Spring")
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

  public ChatServiceImpl(
      ChatMessageRepository repository,
      ChatMessageMapper mapper,
      ProfanityFilter profanityFilter,
      LoggingAdminClient loggingAdminClient,
      RedisTemplate<String, Object> redisTemplate,
      MeterRegistry meterRegistry,
      ChatProperties chatProperties) {
    this.repository = repository;
    this.mapper = mapper;
    this.profanityFilter = profanityFilter;
    this.loggingAdminClient = loggingAdminClient;
    this.redisTemplate = redisTemplate;
    this.meterRegistry = meterRegistry;
    this.chatProperties = copyProps(chatProperties);
  }

  private static ChatProperties copyProps(ChatProperties src) {
    var copy = new ChatProperties();
    copy.setSays(src.getSays());
    copy.setWhispers(src.getWhispers());
    copy.setTells(src.getTells());
    copy.setGuild(src.getGuild());
    copy.setCity(src.getCity());
    copy.setAccount(src.getAccount());
    return copy;
  }

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
      runAfterCommit(
          () ->
              safeReportModeration(
                  request.tenantId(), request.senderAccountId(), "Filtered profanity"));
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
      String key = historyKey(request);
      ChatProperties.ChatCacheSettings settings = settingsFor(request);

      redisTemplate.opsForList().leftPush(key, saved.getContent());
      redisTemplate.expire(key, java.time.Duration.ofSeconds(settings.historyTtlSeconds()));
      redisTemplate.opsForList().trim(key, 0, settings.maxMessages() - 1);
    } catch (Exception e) {
      redisErrorCounter.increment();
      logger.warn("Failed to publish chat message to Redis", e);
    }
    return mapper.toDto(saved);
  }

  private void safeReportModeration(long tenantId, long accountId, String description) {
    try {
      loggingAdminClient.reportChatViolation(tenantId, accountId, description);
    } catch (RuntimeException e) {
      logger.warn(
          "Failed to report chat moderation event for tenant {} account {}",
          tenantId,
          accountId,
          e);
    }
  }

  private void runAfterCommit(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      action.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
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

  private String historyKey(SendMessageRequestDto request) {
    if (request.type() == ChatType.SAY) {
      return String.format("say:%d:%d", request.tenantId(), request.senderAccountId());
    }
    if (request.type() == ChatType.WHISPER) {
      return String.format("whisper:%d:%d", request.tenantId(), request.recipientAccountId());
    }
    if (request.type() == ChatType.TELL) {
      return String.format("tell:%d:%d", request.tenantId(), request.recipientAccountId());
    }
    if (request.type() == ChatType.GUILD) {
      return String.format("guild:%d:%d", request.tenantId(), request.guildId());
    }
    if (request.type() == ChatType.CITY) {
      return String.format("city:%d:%d", request.tenantId(), request.cityId());
    }
    return String.format("account:%d:%d", request.tenantId(), request.recipientAccountId());
  }

  private ChatProperties.ChatCacheSettings settingsFor(SendMessageRequestDto request) {
    if (request.type() == ChatType.SAY) {
      return chatProperties.getSays();
    }
    if (request.type() == ChatType.WHISPER) {
      return chatProperties.getWhispers();
    }
    if (request.type() == ChatType.TELL) {
      return chatProperties.getTells();
    }
    if (request.type() == ChatType.GUILD) {
      return chatProperties.getGuild();
    }
    if (request.type() == ChatType.CITY) {
      return chatProperties.getCity();
    }
    return chatProperties.getAccount();
  }
}
