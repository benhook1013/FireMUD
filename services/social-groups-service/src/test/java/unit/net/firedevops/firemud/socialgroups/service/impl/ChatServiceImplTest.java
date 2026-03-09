package net.firedevops.firemud.socialgroups.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import net.firedevops.firemud.socialgroups.client.LoggingAdminClient;
import net.firedevops.firemud.socialgroups.config.ChatProperties;
import net.firedevops.firemud.socialgroups.dto.ChatMessageDto;
import net.firedevops.firemud.socialgroups.dto.SendMessageRequestDto;
import net.firedevops.firemud.socialgroups.entity.ChatMessage;
import net.firedevops.firemud.socialgroups.enums.ChatType;
import net.firedevops.firemud.socialgroups.mapper.ChatMessageMapper;
import net.firedevops.firemud.socialgroups.repository.ChatMessageRepository;
import net.firedevops.firemud.socialgroups.util.ProfanityFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

class ChatServiceImplTest {
  private ChatMessageRepository repository;
  private ProfanityFilter profanityFilter;
  private LoggingAdminClient loggingAdminClient;
  private RedisTemplate<String, Object> redisTemplate;
  private ListOperations<String, Object> listOps;
  private SimpleMeterRegistry meterRegistry;
  private ChatServiceImpl service;
  private ChatProperties props;

  @BeforeEach
  void setup() {
    repository = mock(ChatMessageRepository.class);
    profanityFilter = mock(ProfanityFilter.class);
    loggingAdminClient = mock(LoggingAdminClient.class);
    redisTemplate = mockRedisTemplate();
    listOps = mockListOperations();
    when(redisTemplate.opsForList()).thenReturn(listOps);
    when(profanityFilter.filter(any())).thenAnswer(i -> i.getArgument(0));
    meterRegistry = new SimpleMeterRegistry();
    props = new ChatProperties();
    service =
        new ChatServiceImpl(
            repository,
            Mappers.getMapper(ChatMessageMapper.class),
            profanityFilter,
            loggingAdminClient,
            redisTemplate,
            meterRegistry,
            props);
    service.init();
    when(repository.save(any(ChatMessage.class)))
        .thenAnswer(
            inv -> {
              ChatMessage m = inv.getArgument(0);
              m.setId(1L);
              return m;
            });
  }

  @Test
  void sendMessageCachesMessageAndIncrementsMetrics() {
    SendMessageRequestDto req =
        new SendMessageRequestDto(1L, 2L, ChatType.SAY, null, 1L, null, null, "hello");

    ChatMessageDto dto = service.sendMessage(req);

    assertEquals(1L, dto.id());
    verify(listOps).leftPush("say:1:1", "hello");
    verify(redisTemplate)
        .expire("say:1:1", Duration.ofSeconds(props.getSays().historyTtlSeconds()));
    verify(listOps).trim("say:1:1", 0, props.getSays().maxMessages() - 1);
    assertEquals(1.0, meterRegistry.get("chat_messages_published_total").counter().count(), 0.001);
    assertEquals(0.0, meterRegistry.get("chat_redis_errors_total").counter().count(), 0.001);
  }

  @Test
  void redisFailureIncrementsErrorMetric() {
    doThrow(new RuntimeException("fail")).when(listOps).leftPush(any(), any());

    SendMessageRequestDto req =
        new SendMessageRequestDto(1L, 2L, ChatType.SAY, null, 1L, null, null, "hi");
    service.sendMessage(req);

    assertEquals(1.0, meterRegistry.get("chat_redis_errors_total").counter().count(), 0.001);
  }

  @SuppressWarnings("unchecked")
  private static RedisTemplate<String, Object> mockRedisTemplate() {
    return mock(RedisTemplate.class);
  }

  @SuppressWarnings("unchecked")
  private static ListOperations<String, Object> mockListOperations() {
    return mock(ListOperations.class);
  }
}
