package net.firedevops.firemud.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import net.firedevops.firemud.service.tick.ScriptTickService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class ScriptTickServiceImplTest {
  private RedisTemplate<String, Object> redisTemplate;
  private org.springframework.data.redis.core.ListOperations<String, Object> listOps;
  private org.springframework.data.redis.core.ValueOperations<String, Object> valueOps;
  private SimpleMeterRegistry meterRegistry;
  private ScriptTickService service;

  @BeforeEach
  void setup() {
    redisTemplate = mock(RedisTemplate.class);
    listOps = mock(org.springframework.data.redis.core.ListOperations.class);
    valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
    when(redisTemplate.opsForList()).thenReturn(listOps);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    meterRegistry = new SimpleMeterRegistry();
    service = new ScriptTickServiceImpl(redisTemplate, meterRegistry);
    ((ScriptTickServiceImpl) service).init();
  }

  @Test
  void enqueueEventPushesToQueue() {
    service.enqueueEvent(1L, 2L, "evt");
    verify(listOps).rightPush(any(String.class), any());
  }

  @Test
  void processTickAttemptsLockAndExecutesScript() {
    when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(true);
    service.processTick(1L, 2L);
    ArgumentCaptor<RedisScript> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
    verify(redisTemplate, org.mockito.Mockito.atLeastOnce())
        .execute(scriptCaptor.capture(), any(List.class));
  }
}
