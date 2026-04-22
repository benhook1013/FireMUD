package net.firedevops.firemud.automationscripting.service.impl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

@SuppressWarnings("unchecked")
class AutomationQueueServiceImplTest {
  private RedisTemplate<String, Object> redisTemplate;
  private ListOperations<String, Object> listOps;
  private AutomationQueueService service;

  @BeforeEach
  void setup() {
    redisTemplate = mock(RedisTemplate.class);
    listOps = mock(ListOperations.class);
    when(redisTemplate.opsForList()).thenReturn(listOps);
    service = new AutomationQueueServiceImpl(redisTemplate, new SimpleMeterRegistry());
    ((AutomationQueueServiceImpl) service).init();
  }

  @Test
  void enqueueEventPushesToRedis() {
    service.enqueueEvent("tenant-1", "instance-1", "entity-1", "evt");
    verify(listOps)
        .rightPush("automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1", "evt");
  }

  @Test
  void drainEventsRetrievesAndDeletes() {
    when(listOps.range("automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1", 0, -1))
        .thenReturn(List.of("evt1", "evt2"));
    service.drainEvents("tenant-1", "instance-1", "entity-1");
    verify(redisTemplate).delete("automation:queue:{tenant:tenant-1:instance:instance-1}:entity-1");
  }
}
